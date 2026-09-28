#!/usr/bin/env python3
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.parse
import urllib.request

import cv2
import numpy as np

MP = {
    "left_shoulder": 11,
    "right_shoulder": 12,
    "left_elbow": 13,
    "right_elbow": 14,
    "left_wrist": 15,
    "right_wrist": 16,
    "left_hip": 23,
    "right_hip": 24,
    "left_knee": 25,
    "right_knee": 26,
    "left_ankle": 27,
    "right_ankle": 28,
}


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def direct_http_download(url: str, target: Path) -> Path:
    target.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "GymBuddyEvidence/1.0"})
    with urllib.request.urlopen(request, timeout=90) as response, target.open("wb") as out:
        shutil.copyfileobj(response, out)
    if target.stat().st_size <= 0:
        raise RuntimeError(f"downloaded empty file: {url}")
    return target


def acquire(url: str, exercise_id: str, work_root: Path, harness_root: Path | None) -> Path:
    parsed = urllib.parse.urlparse(url)
    ext = Path(parsed.path).suffix.lower()
    target = work_root / "downloads" / f"{exercise_id}.mp4"
    if target.exists() and target.stat().st_size > 0:
        return target
    if ext in {".mp4", ".mov", ".m4v", ".webm"}:
        try:
            return direct_http_download(url, target)
        except Exception:
            if harness_root is None:
                raise
    if harness_root is None:
        raise RuntimeError("non-direct URL requires --harness-root")
    sys.path.insert(0, str(harness_root))
    try:
        from harness.video_motion_pipeline import download_public_video
        downloaded = download_public_video(url, exercise_id, work_root=work_root / "video_motion")
    finally:
        if sys.path and sys.path[0] == str(harness_root):
            sys.path.pop(0)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(downloaded, target)
    return target


def probe_video(path: Path) -> dict:
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        raise RuntimeError(f"cannot open video: {path}")
    try:
        fps = float(cap.get(cv2.CAP_PROP_FPS) or 0.0)
        width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH) or 0)
        height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0)
        frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
        if fps <= 0 or width <= 0 or height <= 0 or frame_count <= 0:
            raise RuntimeError(f"invalid video metadata: fps={fps} size={width}x{height} frames={frame_count}")
        duration_s = frame_count / fps
        ok, frame = cap.read()
        if not ok or frame is None or frame.size == 0:
            raise RuntimeError("first video frame is unreadable")
        return {
            "fps": fps,
            "width": width,
            "height": height,
            "frame_count": frame_count,
            "duration_s": duration_s,
            "size_bytes": path.stat().st_size,
        }
    finally:
        cap.release()


def resized(frame: np.ndarray, max_long_side: int) -> np.ndarray:
    h, w = frame.shape[:2]
    scale = min(1.0, max_long_side / max(h, w))
    if scale == 1.0:
        return frame
    return cv2.resize(frame, (max(1, round(w * scale)), max(1, round(h * scale))), interpolation=cv2.INTER_AREA)


def extract_frames(video: Path, session_root: Path, *, session_id: str, exercise_id: str, target_fps: float, max_long_side: int, jpeg_quality: int) -> dict:
    if session_root.exists():
        shutil.rmtree(session_root)
    frames_dir = session_root / "frames"
    gt_dir = session_root / "ground_truth"
    frames_dir.mkdir(parents=True)
    gt_dir.mkdir(parents=True)

    cap = cv2.VideoCapture(str(video))
    if not cap.isOpened():
        raise RuntimeError(f"cannot open video: {video}")
    source_fps = float(cap.get(cv2.CAP_PROP_FPS) or 30.0)
    next_sample_s = 0.0
    records = []
    out_w = out_h = None
    source_idx = 0
    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            t_s = source_idx / source_fps
            source_idx += 1
            if t_s + 1e-9 < next_sample_s:
                continue
            next_sample_s += 1.0 / target_fps
            frame = resized(frame, max_long_side)
            h, w = frame.shape[:2]
            if out_w is None:
                out_w, out_h = w, h
            elif (w, h) != (out_w, out_h):
                frame = cv2.resize(frame, (out_w, out_h), interpolation=cv2.INTER_AREA)
            frame_id = len(records)
            ts_us = int(round(t_s * 1_000_000.0))
            image_rel = f"frames/{frame_id:06d}.jpg"
            gt_rel = f"ground_truth/{frame_id:06d}.json"
            ok2, encoded = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, int(jpeg_quality)])
            if not ok2:
                raise RuntimeError(f"jpeg encode failed for frame {frame_id}")
            (session_root / image_rel).write_bytes(encoded.tobytes())
            (session_root / gt_rel).write_text(json.dumps({
                "schema_version": 1,
                "session_id": session_id,
                "exercise_id": exercise_id,
                "frame_id": frame_id,
                "timestamp_us": ts_us,
                "source_frame_index": source_idx - 1,
                "source_time_s": t_s,
            }, separators=(",", ":")) + "\n", encoding="utf-8")
            records.append({
                "frame_id": frame_id,
                "timestamp_us": ts_us,
                "width": out_w,
                "height": out_h,
                "mime_type": "image/jpeg",
                "image_path": image_rel,
                "ground_truth_path": gt_rel,
            })
    finally:
        cap.release()
    if len(records) < 12:
        raise RuntimeError(f"too few sampled frames: {len(records)}")
    manifest = {
        "schema_version": 1,
        "session_id": session_id,
        "exercise_id": exercise_id,
        "fps": target_fps,
        "width": out_w,
        "height": out_h,
        "source": "real-public-rgb",
        "frame_count": len(records),
        "frames": records,
    }
    (session_root / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def angle_deg(a: np.ndarray, b: np.ndarray, c: np.ndarray) -> float:
    ba = a[:2] - b[:2]
    bc = c[:2] - b[:2]
    nba = float(np.linalg.norm(ba))
    nbc = float(np.linalg.norm(bc))
    if nba < 1e-9 or nbc < 1e-9:
        return math.nan
    cosine = float(np.clip(np.dot(ba, bc) / (nba * nbc), -1.0, 1.0))
    return math.degrees(math.acos(cosine))


def pose_signal(landmarks: np.ndarray, confidence: np.ndarray, exercise_id: str) -> float:
    def p(name: str) -> np.ndarray:
        return landmarks[MP[name]]
    def q(name: str) -> float:
        return float(confidence[MP[name]])
    if exercise_id == "incline_db_press":
        triples = [
            ("left_shoulder", "left_elbow", "left_wrist"),
            ("right_shoulder", "right_elbow", "right_wrist"),
        ]
    elif exercise_id == "smith_squat":
        triples = [
            ("left_hip", "left_knee", "left_ankle"),
            ("right_hip", "right_knee", "right_ankle"),
        ]
    elif exercise_id == "lateral_raise":
        triples = [
            ("left_hip", "left_shoulder", "left_elbow"),
            ("right_hip", "right_shoulder", "right_elbow"),
        ]
    else:
        raise KeyError(exercise_id)
    values = []
    for a, b, c in triples:
        if min(q(a), q(b), q(c)) < 0.45:
            continue
        v = angle_deg(p(a), p(b), p(c))
        if math.isfinite(v):
            values.append(v)
    return float(np.mean(values)) if values else math.nan


def smooth_signal(values: list[float]) -> np.ndarray:
    x = np.asarray(values, dtype=float)
    if x.size < 5:
        return x
    # interpolate short NaN gaps before robust smoothing
    valid = np.isfinite(x)
    if valid.sum() < 5:
        return x
    idx = np.arange(x.size)
    x = np.interp(idx, idx[valid], x[valid])
    med = np.array([np.median(x[max(0, i-2):min(x.size, i+3)]) for i in range(x.size)])
    kernel = np.ones(3) / 3.0
    return np.convolve(np.pad(med, (1,1), mode="edge"), kernel, mode="valid")


def detect_cycles(timestamps_us: list[int], values: list[float], exercise_id: str) -> dict:
    sm = smooth_signal(values)
    finite = sm[np.isfinite(sm)]
    if finite.size < 8:
        return {"expected_reps": 0, "rep_windows": [], "quality": {"reason": "insufficient_signal"}}
    low = float(np.percentile(finite, 20))
    high = float(np.percentile(finite, 80))
    amplitude = high - low
    if amplitude < 18.0:
        return {"expected_reps": 0, "rep_windows": [], "quality": {"reason": "low_amplitude", "amplitude_deg": amplitude}}
    mode = "low_high_low" if exercise_id == "lateral_raise" else "high_low_high"
    start_threshold = low + 0.18 * amplitude if mode == "low_high_low" else high - 0.18 * amplitude
    turn_threshold = high - 0.18 * amplitude if mode == "low_high_low" else low + 0.18 * amplitude
    state = "seek_start"
    start_i = turn_i = None
    windows = []
    dwell = 0
    for i, v in enumerate(sm):
        if not math.isfinite(float(v)):
            dwell = 0
            continue
        at_start = v <= start_threshold if mode == "low_high_low" else v >= start_threshold
        at_turn = v >= turn_threshold if mode == "low_high_low" else v <= turn_threshold
        if state == "seek_start":
            dwell = dwell + 1 if at_start else 0
            if dwell >= 2:
                start_i = i
                state = "seek_turn"
                dwell = 0
        elif state == "seek_turn":
            dwell = dwell + 1 if at_turn else 0
            if dwell >= 2:
                turn_i = i
                state = "seek_complete"
                dwell = 0
        else:
            dwell = dwell + 1 if at_start else 0
            if dwell >= 2 and start_i is not None and turn_i is not None:
                complete_i = i
                duration_s = (timestamps_us[complete_i] - timestamps_us[start_i]) / 1_000_000.0
                if 0.35 <= duration_s <= 12.0:
                    windows.append({
                        "ordinal": len(windows) + 1,
                        "start_us": timestamps_us[start_i],
                        "turn_us": timestamps_us[turn_i],
                        "complete_us": timestamps_us[complete_i],
                    })
                start_i = complete_i
                turn_i = None
                state = "seek_turn"
                dwell = 0
    return {
        "expected_reps": len(windows),
        "rep_windows": windows,
        "quality": {
            "reason": "ok",
            "low_threshold_deg": low,
            "high_threshold_deg": high,
            "amplitude_deg": amplitude,
            "cycle_mode": mode,
        },
    }


def build_oracle(session_root: Path, exercise_id: str, model_path: Path, harness_root: Path) -> dict:
    sys.path.insert(0, str(harness_root))
    try:
        from harness.rgb import MediaPipePoseProvider
        provider = MediaPipePoseProvider(str(model_path), num_poses=2)
        manifest = json.loads((session_root / "manifest.json").read_text())
        timestamps = []
        signals = []
        pose_frames = 0
        for frame_meta in manifest["frames"]:
            frame = cv2.imread(str(session_root / frame_meta["image_path"]))
            pose = provider.infer(frame, frame_meta["frame_id"], frame_meta["timestamp_us"] / 1000.0)
            timestamps.append(int(frame_meta["timestamp_us"]))
            if pose is None:
                signals.append(math.nan)
                continue
            pose_frames += 1
            signals.append(pose_signal(pose.landmarks, pose.confidence, exercise_id))
    finally:
        if sys.path and sys.path[0] == str(harness_root):
            sys.path.pop(0)
    detected = detect_cycles(timestamps, signals, exercise_id)
    coverage = pose_frames / max(1, len(timestamps))
    detected["pose_coverage"] = coverage
    detected["form_labels_complete"] = False
    detected["cue_labels"] = "NOT_ASSESSED"
    detected["label_method"] = "independent_python_mediapipe_temporal_oracle_v1"
    (session_root / "oracle.json").write_text(json.dumps(detected, indent=2) + "\n", encoding="utf-8")
    if detected["expected_reps"] <= 0:
        raise AssertionError(f"{exercise_id}: independent oracle detected no complete reps")
    if coverage < 0.55:
        raise AssertionError(f"{exercise_id}: oracle pose coverage too low: {coverage:.3f}")
    return detected


def apply_variant(clean_root: Path, out_root: Path, kind: str, oracle: dict) -> dict:
    if out_root.exists():
        shutil.rmtree(out_root)
    (out_root / "frames").mkdir(parents=True)
    (out_root / "ground_truth").mkdir(parents=True)
    clean = json.loads((clean_root / "manifest.json").read_text())
    selected = clean["frames"]
    if kind == "frame_drop":
        selected = [f for i, f in enumerate(selected) if (i % 4) != 2]
    new_records = []
    for new_id, src in enumerate(selected):
        img = cv2.imread(str(clean_root / src["image_path"]))
        if kind == "low_light":
            img = np.clip(img.astype(np.float32) * 0.34, 0, 255).astype(np.uint8)
        elif kind == "camera_bump":
            if new_id >= len(selected) // 2:
                h, w = img.shape[:2]
                matrix = np.float32([[1, 0, 0.10 * w], [0, 1, -0.07 * h]])
                img = cv2.warpAffine(img, matrix, (w, h), borderMode=cv2.BORDER_REFLECT)
        elif kind != "frame_drop":
            raise KeyError(kind)
        rel = f"frames/{new_id:06d}.jpg"
        ok, encoded = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 90])
        if not ok:
            raise RuntimeError("stress jpeg encode failed")
        (out_root / rel).write_bytes(encoded.tobytes())
        gt_rel = f"ground_truth/{new_id:06d}.json"
        gt = {
            "schema_version": 1,
            "session_id": f"{clean['session_id']}--{kind}",
            "exercise_id": clean["exercise_id"],
            "frame_id": new_id,
            "timestamp_us": src["timestamp_us"],
            "stress_variant": kind,
            "oracle_expected_reps_clean": oracle["expected_reps"],
            "expected_policy": "NO_OVERCOUNT",
        }
        (out_root / gt_rel).write_text(json.dumps(gt, separators=(",", ":")) + "\n", encoding="utf-8")
        new_records.append({
            "frame_id": new_id,
            "timestamp_us": src["timestamp_us"],
            "width": clean["width"],
            "height": clean["height"],
            "mime_type": "image/jpeg",
            "image_path": rel,
            "ground_truth_path": gt_rel,
        })
    manifest = {
        **{k: clean[k] for k in ["schema_version", "exercise_id", "fps", "width", "height"]},
        "session_id": f"{clean['session_id']}--{kind}",
        "source": f"g3-stress:{kind}",
        "frame_count": len(new_records),
        "frames": new_records,
    }
    (out_root / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (out_root / "oracle.json").write_text(json.dumps({
        "expected_policy": "NO_OVERCOUNT",
        "oracle_expected_reps_clean": oracle["expected_reps"],
        "stress_variant": kind,
    }, indent=2) + "\n", encoding="utf-8")
    return manifest


def prepare_one(source: dict, cfg: dict, output: Path, harness_root: Path, model_path: Path) -> dict:
    exercise_id = source["exercise_id"]
    video = acquire(source["url"], exercise_id, output / "work", harness_root)
    digest = sha256_file(video)
    expected = source.get("expected_sha256")
    if expected and expected != digest:
        raise AssertionError(f"{exercise_id}: SHA-256 changed: expected {expected}, got {digest}")
    meta = probe_video(video)
    clean_root = output / "sessions" / f"{exercise_id}--clean"
    manifest = extract_frames(
        video, clean_root,
        session_id=f"g2-{exercise_id}-{digest[:12]}",
        exercise_id=exercise_id,
        target_fps=float(cfg["target_fps"]),
        max_long_side=int(cfg["max_long_side"]),
        jpeg_quality=int(cfg["jpeg_quality"]),
    )
    oracle = build_oracle(clean_root, exercise_id, model_path, harness_root)
    sessions = [{"session_id": manifest["session_id"], "kind": "g2_clean", "path": str(clean_root.relative_to(output)), "exercise_id": exercise_id, "expected_reps": oracle["expected_reps"]}]
    for kind in cfg.get("g3_variants", []):
        stress_root = output / "sessions" / f"{exercise_id}--{kind}"
        stress = apply_variant(clean_root, stress_root, kind, oracle)
        sessions.append({"session_id": stress["session_id"], "kind": f"g3_{kind}", "path": str(stress_root.relative_to(output)), "exercise_id": exercise_id, "expected_reps": oracle["expected_reps"]})
    return {
        "exercise_id": exercise_id,
        "display_name": source.get("display_name"),
        "url": source["url"],
        "source_name": source.get("source_name"),
        "equipment": source.get("equipment"),
        "view": source.get("view"),
        "sha256": digest,
        "hash_pinned": bool(expected),
        "video": meta,
        "oracle": oracle,
        "sessions": sessions,
    }


def prepare(args) -> int:
    cfg = json.loads(args.registry.read_text())
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    sources = cfg["sources"]
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(sources)) as pool:
        futures = [pool.submit(prepare_one, s, cfg, output, args.harness_root.resolve(), args.model.resolve()) for s in sources]
        items = [f.result() for f in futures]
    all_sessions = [s for item in items for s in item["sessions"]]
    prepared = {
        "schema_version": 1,
        "pipeline": "link->acquire->oracle->frame-session->android->score",
        "sources": items,
        "sessions": all_sessions,
        "all_hashes_pinned": all(x["hash_pinned"] for x in items),
    }
    (output / "prepared.json").write_text(json.dumps(prepared, indent=2) + "\n", encoding="utf-8")
    for item in items:
        print(f"G2G3_SOURCE_HASH exercise={item['exercise_id']} sha256={item['sha256']}")
    print(json.dumps({"prepared": str(output / 'prepared.json'), "sessions": len(all_sessions), "all_hashes_pinned": prepared["all_hashes_pinned"]}, indent=2))
    return 0


def collect_android_result(path: Path) -> dict:
    payload = json.loads(path.read_text())
    if not isinstance(payload, list) or not payload:
        raise AssertionError(f"missing Android frame results: {path}")
    analyses = [x["analysis"] for x in payload]
    final_count = int(analyses[-1].get("rep_count", 0))
    counts = [int(x.get("rep_count", 0)) for x in analyses]
    pose_fraction = sum(int(x.get("pose_count", 0)) > 0 for x in analyses) / len(analyses)
    cues = [cue for x in analyses for cue in (x.get("cues") or [])]
    return {
        "frames": len(payload),
        "final_rep_count": final_count,
        "max_rep_count": max(counts),
        "monotonic_rep_count": all(a <= b for a, b in zip(counts, counts[1:])),
        "android_pose_fraction": pose_fraction,
        "cue_count": len(cues),
        "tracking_states": sorted(set(str(x.get("tracking_state")) for x in analyses)),
        "camera_guidance": sorted(set(str(x.get("camera_guidance")) for x in analyses)),
    }


def score(args) -> int:
    prepared = json.loads(args.prepared.read_text())
    sim_gym = json.loads(args.sim_gym.read_text())
    source_map = {x["exercise_id"]: x for x in prepared["sources"]}
    rows = []
    g2_pass = True
    g3_stress_pass = True
    for session in prepared["sessions"]:
        result_path = args.results / f"{session['session_id']}.json"
        obs = collect_android_result(result_path)
        expected = int(session["expected_reps"])
        if session["kind"] == "g2_clean":
            passed = (
                obs["final_rep_count"] == expected
                and obs["max_rep_count"] == expected
                and obs["monotonic_rep_count"]
                and obs["android_pose_fraction"] >= 0.55
                and source_map[session["exercise_id"]]["oracle"]["pose_coverage"] >= 0.55
            )
            g2_pass &= passed
        else:
            passed = obs["max_rep_count"] <= expected and obs["monotonic_rep_count"]
            g3_stress_pass &= passed
        rows.append({**session, **obs, "passed": passed})
    sim_pass = sim_gym.get("static_failures") == 0 and sim_gym.get("temporal_failures") == 0
    hashes_pinned = bool(prepared.get("all_hashes_pinned"))
    runtime_passed = bool(g2_pass and g3_stress_pass and sim_pass)
    report = {
        "schema_version": 1,
        "g2": {"passed": g2_pass, "all_source_hashes_pinned": hashes_pinned},
        "g3": {"passed": runtime_passed, "rgb_stress_passed": g3_stress_pass, "sim_gym_passed": sim_pass},
        "sessions": rows,
        "sim_gym": sim_gym,
        "runtime_passed": runtime_passed,
        "release_passed": bool(runtime_passed and hashes_pinned),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    if not runtime_passed:
        raise SystemExit(2)
    if not hashes_pinned and not args.allow_unpinned:
        raise SystemExit(3)
    return 0


def self_test() -> int:
    # Cycle detection tests production-independent temporal semantics.
    ts = [i * 100_000 for i in range(80)]
    # lateral: low-high-low twice
    x = []
    for _ in range(2):
        x += list(np.linspace(15, 95, 20)) + list(np.linspace(95, 15, 20))
    out = detect_cycles(ts, x, "lateral_raise")
    assert out["expected_reps"] == 2, out
    # press: high-low-high twice
    y = []
