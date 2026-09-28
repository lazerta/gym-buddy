#!/usr/bin/env python3
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import math
from pathlib import Path
import shutil
import urllib.request

import cv2
import numpy as np

from harness.frame_contract import FrameRecord, FrameSessionManifest, save_manifest
from harness.rgb import MediaPipePoseProvider
from harness.video_motion_pipeline import download_public_video

TARGET_FPS = 10.0
MAX_WIDTH = 640
JPEG_QUALITY = 90
REQUIRED = {
    "incline_db_press": (11, 12, 13, 14, 15, 16),
    "smith_squat": (23, 24, 25, 26, 27, 28),
    "lateral_raise": (11, 12, 13, 14, 23, 24),
}


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def direct_http_video(url: str, target: Path) -> Path:
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and target.stat().st_size > 0:
        return target
    tmp = target.with_suffix(target.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": "GymBuddy-G2-Harness/1"})
    with urllib.request.urlopen(req, timeout=120) as response, tmp.open("wb") as out:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            out.write(chunk)
    if tmp.stat().st_size < 1024:
        raise RuntimeError(f"downloaded video unexpectedly small: {url}")
    tmp.replace(target)
    return target


def acquire(url: str, exercise: str, root: Path) -> Path:
    if url.lower().split("?", 1)[0].endswith((".mp4", ".mov", ".m4v", ".webm")):
        suffix = Path(url.split("?", 1)[0]).suffix or ".mp4"
        digest = hashlib.sha256(url.encode("utf-8")).hexdigest()[:16]
        return direct_http_video(url, root / "_downloads" / exercise / f"url_{digest}{suffix}")
    return download_public_video(url, exercise, work_root=root / "_yt")


def angle(a: np.ndarray, b: np.ndarray, c: np.ndarray) -> float:
    v1 = a[:2] - b[:2]
    v2 = c[:2] - b[:2]
    n1 = float(np.linalg.norm(v1))
    n2 = float(np.linalg.norm(v2))
    if n1 < 1e-6 or n2 < 1e-6:
        return math.nan
    x = float(np.dot(v1, v2) / (n1 * n2))
    return math.degrees(math.acos(max(-1.0, min(1.0, x))))


def pose_signal(exercise: str, lm: np.ndarray) -> float:
    if exercise == "incline_db_press":
        return float(np.nanmean([
            angle(lm[11], lm[13], lm[15]),
            angle(lm[12], lm[14], lm[16]),
        ]))
    if exercise == "smith_squat":
        return float(np.nanmean([
            angle(lm[23], lm[25], lm[27]),
            angle(lm[24], lm[26], lm[28]),
        ]))
    if exercise == "lateral_raise":
        return float(np.nanmean([
            angle(lm[23], lm[11], lm[13]),
            angle(lm[24], lm[12], lm[14]),
        ]))
    raise KeyError(exercise)


def median_filter(values: list[float], radius: int = 2) -> np.ndarray:
    arr = np.asarray(values, dtype=np.float64)
    out = arr.copy()
    for i in range(len(arr)):
        lo, hi = max(0, i - radius), min(len(arr), i + radius + 1)
        finite = arr[lo:hi][np.isfinite(arr[lo:hi])]
        if finite.size:
            out[i] = float(np.median(finite))
    return out


def oracle_from_video(video: Path, exercise: str, model: Path) -> dict:
    provider = MediaPipePoseProvider(str(model), num_poses=2)
    cap = cv2.VideoCapture(str(video))
    if not cap.isOpened():
        raise RuntimeError(f"cannot open {video}")
    native_fps = float(cap.get(cv2.CAP_PROP_FPS) or 30.0)
    frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    duration_s = frame_count / native_fps if frame_count > 0 else 0.0
    stride = max(1, int(round(native_fps / TARGET_FPS)))
    times, values, valid = [], [], []
    idx = 0
    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            if idx % stride:
                idx += 1
                continue
            ts_ms = 1000.0 * idx / native_fps
            pose = provider.infer(frame, idx, ts_ms)
            if pose is None or pose.landmarks.shape[0] <= max(REQUIRED[exercise]):
                times.append(ts_ms / 1000.0)
                values.append(math.nan)
                valid.append(False)
            else:
                required_conf = pose.confidence[list(REQUIRED[exercise])]
                good = bool(np.all(required_conf >= 0.45))
                val = pose_signal(exercise, pose.landmarks) if good else math.nan
                times.append(ts_ms / 1000.0)
                values.append(val)
                valid.append(good and math.isfinite(val))
            idx += 1
    finally:
        cap.release()
        close = getattr(provider, "landmarker", None)
        if close is not None:
            close.close()

    if len(times) < 10:
        raise AssertionError(f"{exercise}: too few sampled frames")
    coverage = sum(valid) / len(valid)
    if coverage < 0.55:
        raise AssertionError(f"{exercise}: independent pose coverage too low: {coverage:.3f}")

    smooth = median_filter(values)
    finite = smooth[np.isfinite(smooth)]
    low = float(np.percentile(finite, 20))
    high = float(np.percentile(finite, 80))
    if high - low < 12.0:
        raise AssertionError(f"{exercise}: insufficient movement range: {low:.2f}..{high:.2f}")

    starts_high = exercise in {"incline_db_press", "smith_squat"}
    state = "home"
    started_at = None
    windows = []
    last_end = -1e9
    for t, x in zip(times, smooth):
        if not math.isfinite(float(x)):
            continue
        away = (x <= low) if starts_high else (x >= high)
        home = (x >= high) if starts_high else (x <= low)
        if state == "home" and away and t - last_end >= 0.30:
            state = "away"
            started_at = t
        elif state == "away" and home and started_at is not None:
            if 0.35 <= t - started_at <= 12.0:
                windows.append({"start_s": round(started_at, 4), "end_s": round(t, 4)})
                last_end = t
            state = "home"
            started_at = None

    if not windows:
        raise AssertionError(f"{exercise}: independent oracle found zero reps")

    return {
        "schema_version": 1,
        "oracle": "external_harness_mediapipe_adaptive_cycle_v1",
        "exercise_id": exercise,
        "expected_reps": len(windows),
        "rep_windows": windows,
        "pose_coverage": round(coverage, 6),
        "signal_low_deg": round(low, 4),
        "signal_high_deg": round(high, 4),
        "source_duration_s": round(duration_s, 4),
        "source_fps": round(native_fps, 4),
    }


def resize_frame(frame: np.ndarray) -> np.ndarray:
    h, w = frame.shape[:2]
    if w <= MAX_WIDTH:
        return frame
    scale = MAX_WIDTH / w
    return cv2.resize(frame, (MAX_WIDTH, max(1, int(round(h * scale)))), interpolation=cv2.INTER_AREA)


def load_sampled_frames(video: Path) -> tuple[float, list[tuple[int, np.ndarray]]]:
    cap = cv2.VideoCapture(str(video))
    if not cap.isOpened():
        raise RuntimeError(f"cannot open {video}")
    fps = float(cap.get(cv2.CAP_PROP_FPS) or 30.0)
    stride = max(1, int(round(fps / TARGET_FPS)))
    samples = []
    idx = 0
    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            if idx % stride == 0:
                samples.append((int(round(idx * 1_000_000.0 / fps)), resize_frame(frame)))
            idx += 1
    finally:
        cap.release()
    if not samples:
        raise AssertionError(f"no RGB frames extracted from {video}")
    return fps, samples


def mutate_variant(name: str, samples: list[tuple[int, np.ndarray]]) -> list[tuple[int, np.ndarray]]:
    if name == "clean":
        return [(ts, frame.copy()) for ts, frame in samples]
    n = len(samples)
    lo, hi = n // 3, min(n, n // 3 + max(4, n // 10))
    out = []
    for i, (ts, frame) in enumerate(samples):
        if name == "frame_drop" and lo <= i < hi and i % 2 == 0:
            continue
        x = frame.copy()
        if name == "motion_blur" and lo <= i < hi:
            x = cv2.GaussianBlur(x, (21, 21), 0)
        elif name == "low_light" and lo <= i < hi:
            x = cv2.convertScaleAbs(x, alpha=0.28, beta=0)
        out.append((ts, x))
    return out


def write_session(
    root: Path,
    production_exercise: str,
    source_hash: str,
    oracle: dict,
    variant: str,
    samples: list[tuple[int, np.ndarray]],
) -> None:
    if root.exists():
        shutil.rmtree(root)
    frames_dir = root / "frames"
    gt_dir = root / "ground_truth"
    frames_dir.mkdir(parents=True)
    gt_dir.mkdir(parents=True)
    records = []
    width = height = None
    for i, (ts, frame) in enumerate(samples):
        h, w = frame.shape[:2]
        width = w if width is None else width
        height = h if height is None else height
        if (w, h) != (width, height):
            frame = cv2.resize(frame, (width, height), interpolation=cv2.INTER_AREA)
        img_rel = f"frames/{i:06d}.jpg"
        gt_rel = f"ground_truth/{i:06d}.json"
        ok, enc = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY])
        if not ok:
            raise RuntimeError(f"JPEG encoding failed at {i}")
        (root / img_rel).write_bytes(enc.tobytes())
        (root / gt_rel).write_text(json.dumps({
            "schema_version": 1,
            "session_id": root.name,
            "exercise_id": production_exercise,
            "frame_id": i,
            "timestamp_us": ts,
            "oracle_private": True,
            "variant": variant,
        }) + "\n", encoding="utf-8")
        records.append(FrameRecord(i, ts, width, height, "image/jpeg", img_rel, gt_rel))

    manifest = FrameSessionManifest(
        session_id=root.name,
        exercise_id=production_exercise,
        fps=TARGET_FPS,
        width=int(width),
        height=int(height),
        frames=tuple(records),
        source=f"real-rgb:{source_hash[:16]}:{variant}",
    )
    save_manifest(root / "manifest.json", manifest)
    (root / "oracle.json").write_text(json.dumps({
        **oracle,
        "production_exercise_id": production_exercise,
        "source_sha256": source_hash,
        "variant": variant,
    }, indent=2) + "\n", encoding="utf-8")


def prepare_one(entry: dict, output: Path, model: Path) -> dict:
    h_ex = entry["harness_exercise_id"]
    p_ex = entry["production_exercise_id"]
    urls = list(entry.get("urls") or [entry["url"]])
    expected = entry.get("expected_sha256")
    failures: list[str] = []
    chosen_url = None
    video = None
    digest = None
    oracle = None

    # Source selection is independent of Gym Buddy production behavior. A candidate
    # is admitted only if the external harness can actually observe the required
    # joints and recover a non-trivial exercise cycle from real RGB.
    for url in urls:
        try:
            candidate = acquire(url, h_ex, output)
            candidate_digest = sha256_file(candidate)
            if expected and candidate_digest != expected:
                raise AssertionError(
                    f"{h_ex}: source hash changed: {candidate_digest} != {expected}"
                )
            candidate_oracle = oracle_from_video(candidate, h_ex, model)
        except Exception as exc:
            failures.append(f"{url}: {exc}")
            print(f"SOURCE_REJECT {h_ex} url={url} reason={exc}")
            continue
        chosen_url = url
        video = candidate
        digest = candidate_digest
        oracle = candidate_oracle
        break

    if video is None or digest is None or oracle is None or chosen_url is None:
        raise AssertionError(
            f"{h_ex}: no candidate source passed independent quality gates: "
            + " | ".join(failures)
        )

    _, samples = load_sampled_frames(video)
    sessions = []
    for variant in ("clean", "frame_drop", "motion_blur", "low_light"):
        mutated = mutate_variant(variant, samples)
        name = f"{p_ex}--{variant}"
        write_session(output / "sessions" / name, p_ex, digest, oracle, variant, mutated)
        sessions.append(f"sessions/{name}")
    return {
        "harness_exercise_id": h_ex,
        "production_exercise_id": p_ex,
        "url": chosen_url,
        "sha256": digest,
        "hash_pinned": bool(expected),
        "bytes": video.stat().st_size,
        "expected_reps": oracle["expected_reps"],
        "pose_coverage": oracle["pose_coverage"],
        "sessions": sessions,
    }


def prepare(args) -> int:
    sources = json.loads(args.sources.read_text(encoding="utf-8"))
    args.output.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        futures = [pool.submit(prepare_one, e, args.output, args.model) for e in sources["sources"]]
        rows = [f.result() for f in futures]
    rows.sort(key=lambda x: x["production_exercise_id"])
    lock = {"schema_version": 1, "sources": rows}
    (args.output / "source-lock.json").write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
    sessions = [s for row in rows for s in row["sessions"]]
    (args.output / "index.json").write_text(json.dumps({
        "schema_version": 1,
        "sessions": sessions,
    }, indent=2) + "\n", encoding="utf-8")
    print("G2_LINK_PREPARATION_PASS")
    for row in rows:
        print(f"SOURCE_LOCK {row['production_exercise_id']} sha256={row['sha256']} reps={row['expected_reps']} coverage={row['pose_coverage']}")
    return 0


def score_one(session: Path) -> dict:
    oracle = json.loads((session / "oracle.json").read_text())
    results = json.loads((session / "android-results.json").read_text())
    if not results:
        raise AssertionError(f"{session.name}: no Android results")
    final_count = max(int(x["analysis"].get("rep_count", 0)) for x in results)
    cues = [c for x in results for c in x["analysis"].get("cues", [])]
    pose_frames = sum(int(x["analysis"].get("pose_count", 0)) > 0 for x in results)
    expected = int(oracle["expected_reps"])
    variant = oracle["variant"]
    if variant == "clean":
        passed = final_count == expected and pose_frames / len(results) >= 0.50 and len(cues) == 0
    else:
        passed = 0 <= final_count <= expected
    return {
        "session": session.name,
        "exercise_id": oracle["production_exercise_id"],
        "variant": variant,
        "expected_reps": expected,
        "observed_reps": final_count,
        "cue_count": len(cues),
        "pose_frame_fraction": round(pose_frames / len(results), 6),
        "passed": passed,
    }


def score(args) -> int:
    index = json.loads((args.root / "index.json").read_text())
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        rows = list(pool.map(lambda rel: score_one(args.root / rel), index["sessions"]))
    sim = json.loads(args.sim_summary.read_text())
    g2 = [x for x in rows if x["variant"] == "clean"]
    g3 = [x for x in rows if x["variant"] != "clean"]
    source_lock = json.loads((args.root / "source-lock.json").read_text())
    all_hashes_pinned = all(x["hash_pinned"] for x in source_lock["sources"])
    g2_pass = len(g2) == 3 and all(x["passed"] for x in g2)
    sim_pass = sim.get("static_failures") == 0 and sim.get("temporal_failures") == 0
    g3_pass = g2_pass and sim_pass and len(g3) == 9 and all(x["passed"] for x in g3)
    release_pass = g2_pass and g3_pass and all_hashes_pinned
    report = {
        "schema_version": 1,
        "g2_pass": g2_pass,
        "g3_pass": g3_pass,
        "all_source_hashes_pinned": all_hashes_pinned,
        "release_pass": release_pass,
        "sim_gym_pass": sim_pass,
        "sessions": rows,
        "source_lock": source_lock,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    if not g2_pass or not g3_pass:
        return 1
    if not all_hashes_pinned:
        print("G2_G3_EVIDENCE_GREEN_HASH_DISCOVERY_ONLY")
        return 0
    print("G2_G3_RELEASE_PASS")
    return 0


def self_test() -> int:
    def synthetic(home_high: bool) -> int:
        xs = []
        for _ in range(3):
            cycle = np.r_[np.full(4, 160 if home_high else 20), np.linspace(160 if home_high else 20, 70 if home_high else 90, 6), np.full(3, 70 if home_high else 90), np.linspace(70 if home_high else 90, 160 if home_high else 20, 6)]
            xs.extend(cycle.tolist())
        smooth = median_filter(xs)
        low, high = np.percentile(smooth, [20, 80])
        state = "home"
        count = 0
        for x in smooth:
            away = x <= low if home_high else x >= high
            home = x >= high if home_high else x <= low
            if state == "home" and away:
                state = "away"
            elif state == "away" and home:
                count += 1
                state = "home"
        return count
    assert synthetic(True) == 3
    assert synthetic(False) == 3
    print("G2G3_PIPELINE_SELF_TEST_PASS")
    return 0


def main() -> int:
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    prep = sub.add_parser("prepare")
    prep.add_argument("--sources", type=Path, required=True)
    prep.add_argument("--output", type=Path, required=True)
    prep.add_argument("--model", type=Path, required=True)
    sc = sub.add_parser("score")
    sc.add_argument("--root", type=Path, required=True)
    sc.add_argument("--sim-summary", type=Path, required=True)
    sc.add_argument("--output", type=Path, required=True)
    sub.add_parser("self-test")
    args = p.parse_args()
    if args.cmd == "prepare":
        return prepare(args)
    if args.cmd == "score":
        return score(args)
    return self_test()


if __name__ == "__main__":
    raise SystemExit(main())
