#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import math
import shutil
import subprocess
import sys
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import cv2
import numpy as np

SCHEMA_VERSION = 1
DEFAULT_ANALYSIS_FPS = 10.0


@dataclass(frozen=True)
class SourceSpec:
    exercise_id: str
    source_url: str | None
    source_title: str
    source_page: str | None = None
    local_path: str | None = None
    expected_view: str | None = None
    equipment: str | None = None


@dataclass(frozen=True)
class CycleWindow:
    start_index: int
    turn_index: int
    end_index: int
    score: float


def _load_specs(path: Path) -> list[SourceSpec]:
    data = json.loads(path.read_text(encoding="utf-8"))
    specs = [SourceSpec(**item) for item in data["sources"]]
    if {s.exercise_id for s in specs} != {
        "incline_dumbbell_press", "smith_machine_squat", "dumbbell_lateral_raise"
    }:
        raise ValueError("source registry must contain exactly the three release exercises")
    return specs


def _download(spec: SourceSpec, cache: Path) -> Path:
    if spec.local_path:
        p = Path(spec.local_path).expanduser().resolve()
        if not p.exists():
            raise FileNotFoundError(p)
        return p
    if not spec.source_url:
        raise ValueError(f"{spec.exercise_id}: source_url or local_path required")
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / f"{spec.exercise_id}.mp4"
    if target.exists() and target.stat().st_size > 0:
        return target
    source_url = spec.source_url
    lowered = source_url.lower().split("?", 1)[0]
    if lowered.endswith((".mp4", ".webm", ".mov", ".m4v")):
        request = urllib.request.Request(
            source_url,
            headers={"User-Agent": "GymBuddyReleaseEvidence/1.0 (+https://github.com/lazerta/gym-buddy)"},
        )
        with urllib.request.urlopen(request, timeout=120) as response, target.open("wb") as out:
            shutil.copyfileobj(response, out, length=1024 * 1024)
        if target.stat().st_size <= 0:
            raise ValueError(f"direct media download was empty: {source_url}")
        return target

    cmd = [
        sys.executable, "-m", "yt_dlp", "--no-playlist", "--no-part",
        "--merge-output-format", "mp4", "-S", "res:720",
        "-o", str(target.with_suffix(".%(ext)s")), "--print", "after_move:filepath",
        source_url,
    ]
    proc = subprocess.run(cmd, check=True, text=True, capture_output=True)
    candidates = [Path(x.strip()) for x in proc.stdout.splitlines() if x.strip()]
    if candidates and candidates[-1].exists():
        actual = candidates[-1].resolve()
        if actual != target.resolve():
            shutil.copyfile(actual, target)
    if not target.exists():
        fallbacks = sorted(cache.glob(f"{spec.exercise_id}.*"))
        if not fallbacks:
            raise FileNotFoundError(f"download produced no file for {source_url}")
        target = fallbacks[0]
    return target


def _sample_video(path: Path, fps: float, max_seconds: float = 90.0) -> tuple[list[np.ndarray], float]:
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        raise FileNotFoundError(f"cannot open video: {path}")
    src_fps = float(cap.get(cv2.CAP_PROP_FPS) or 30.0)
    frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    duration = frame_count / src_fps if frame_count > 0 else max_seconds
    duration = min(duration, max_seconds)
    frames: list[np.ndarray] = []
    t = 0.0
    while t <= duration + 1e-9:
        cap.set(cv2.CAP_PROP_POS_MSEC, t * 1000.0)
        ok, frame = cap.read()
        if not ok:
            break
        frames.append(frame)
        t += 1.0 / fps
    cap.release()
    if len(frames) < max(12, int(fps * 1.5)):
        raise ValueError(f"video too short/readable frames too few: {len(frames)}")
    return frames, fps


def _motion_signals(frames: list[np.ndarray]) -> tuple[np.ndarray, np.ndarray]:
    grays = []
    for frame in frames:
        h, w = frame.shape[:2]
        scale = min(1.0, 240.0 / max(h, w))
        small = cv2.resize(frame, (max(32, int(w * scale)), max(32, int(h * scale))))
        gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
        grays.append(cv2.GaussianBlur(gray, (5, 5), 0))
    vertical = [0.0]
    energy = [0.0]
    for a, b in zip(grays, grays[1:]):
        flow = cv2.calcOpticalFlowFarneback(a, b, None, .5, 3, 19, 3, 5, 1.2, 0)
        mag = np.sqrt(flow[..., 0] ** 2 + flow[..., 1] ** 2)
        threshold = float(np.percentile(mag, 82))
        mask = mag >= max(threshold, 0.05)
        if np.count_nonzero(mask) < 20:
            vertical.append(0.0)
            energy.append(float(np.mean(mag)))
            continue
        weights = mag[mask]
        vertical.append(float(np.average(flow[..., 1][mask], weights=weights)))
        energy.append(float(np.average(weights)))
    vertical = np.asarray(vertical, dtype=np.float64)
    energy = np.asarray(energy, dtype=np.float64)
    kernel = np.ones(5, dtype=np.float64) / 5.0
    vertical = np.convolve(vertical, kernel, mode="same")
    energy = np.convolve(energy, kernel, mode="same")
    return vertical, energy


def _runs(signal: np.ndarray, threshold: float) -> list[tuple[int, int, int]]:
    signs = np.zeros(len(signal), dtype=np.int8)
    signs[signal >= threshold] = 1
    signs[signal <= -threshold] = -1
    out: list[tuple[int, int, int]] = []
    start = None
    sign = 0
    for i, s in enumerate(signs):
        s = int(s)
        if s == 0:
            continue
        if start is None:
            start, sign = i, s
            continue
        if s != sign:
            out.append((start, i - 1, sign))
            start, sign = i, s
    if start is not None:
        out.append((start, len(signs) - 1, sign))
    return out


def find_best_cycle(frames: list[np.ndarray], fps: float) -> CycleWindow:
    vertical, energy = _motion_signals(frames)
    abs_v = np.abs(vertical)
    nonzero = abs_v[abs_v > 1e-5]
    if nonzero.size < 8:
        raise ValueError("independent motion oracle found insufficient directional motion")
    threshold = max(float(np.percentile(nonzero, 55)) * 0.45, 0.015)
    runs = _runs(vertical, threshold)
    min_run = max(2, int(round(.20 * fps)))
    max_run = max(min_run + 1, int(round(4.0 * fps)))
    candidates: list[CycleWindow] = []
    for a, b in zip(runs, runs[1:]):
        a0, a1, sa = a
        b0, b1, sb = b
        if sa == sb or b0 - a1 > int(round(1.0 * fps)):
            continue
        da, db = a1 - a0 + 1, b1 - b0 + 1
        if not (min_run <= da <= max_run and min_run <= db <= max_run):
            continue
        ia = float(np.sum(np.abs(vertical[a0:a1 + 1])))
        ib = float(np.sum(np.abs(vertical[b0:b1 + 1])))
        if ia <= 0 or ib <= 0:
            continue
        balance = min(ia, ib) / max(ia, ib)
        duration = (b1 - a0 + 1) / fps
        if duration < .7 or duration > 8.0:
            continue
        motion = float(np.sum(energy[a0:b1 + 1]))
        score = math.sqrt(ia * ib) * (0.35 + 0.65 * balance) * math.log1p(max(0.0, motion))
        candidates.append(CycleWindow(a0, b0, b1, score))
    if not candidates:
        raise ValueError(f"independent motion oracle found no complete up/down cycle; runs={runs[:12]}")
    return max(candidates, key=lambda x: x.score)


def _motion_bbox(frames: list[np.ndarray]) -> tuple[int, int, int, int] | None:
    # Temporal variance identifies the moving lifter/weights without using pose landmarks.
    h0, w0 = frames[0].shape[:2]
    scale = min(1.0, 320.0 / max(h0, w0))
    size = (max(64, int(w0 * scale)), max(64, int(h0 * scale)))
    sample = np.stack([
        cv2.cvtColor(cv2.resize(f, size), cv2.COLOR_BGR2GRAY).astype(np.float32)
        for f in frames[::max(1, len(frames)//32)]
    ])
    var = np.std(sample, axis=0)
    thr = max(float(np.percentile(var, 84)), 4.0)
    mask = (var >= thr).astype(np.uint8) * 255
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((7, 7), np.uint8))
    ys, xs = np.where(mask > 0)
    if len(xs) < 100:
        return None
    x0, x1 = int(xs.min()), int(xs.max())
    y0, y1 = int(ys.min()), int(ys.max())
    inv = 1.0 / scale
    return int(x0 * inv), int(y0 * inv), int((x1 + 1) * inv), int((y1 + 1) * inv)


def _square_crop(frame: np.ndarray, bbox: tuple[int, int, int, int] | None, padding: float = 1.55) -> np.ndarray:
    h, w = frame.shape[:2]
    if bbox is None:
        return frame
    x0, y0, x1, y1 = bbox
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    bw, bh = max(1.0, x1 - x0), max(1.0, y1 - y0)
    side = min(max(bw, bh) * padding, float(max(h, w)))
    # Use a portrait-friendly crop when the source is portrait; otherwise square.
    cw = side
    ch = min(side * (1.18 if h > w else 1.0), float(h))
    x0 = int(round(cx - cw / 2)); x1 = int(round(cx + cw / 2))
    y0 = int(round(cy - ch / 2)); y1 = int(round(cy + ch / 2))
    if x0 < 0: x1 -= x0; x0 = 0
    if y0 < 0: y1 -= y0; y0 = 0
    if x1 > w: x0 -= x1 - w; x1 = w
    if y1 > h: y0 -= y1 - h; y1 = h
    x0, y0 = max(0, x0), max(0, y0)
    return frame[y0:y1, x0:x1]


def _fit_output(frame: np.ndarray, width: int = 640, height: int = 640) -> np.ndarray:
    h, w = frame.shape[:2]
    scale = min(width / w, height / h)
    nw, nh = max(1, int(round(w * scale))), max(1, int(round(h * scale)))
    resized = cv2.resize(frame, (nw, nh), interpolation=cv2.INTER_AREA if scale < 1 else cv2.INTER_LINEAR)
    canvas = np.zeros((height, width, 3), dtype=np.uint8)
    x, y = (width - nw) // 2, (height - nh) // 2
    canvas[y:y + nh, x:x + nw] = resized
    return canvas


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _write_session(spec: SourceSpec, video: Path, output_root: Path, analysis_fps: float) -> dict[str, Any]:
    raw_frames, fps = _sample_video(video, analysis_fps)
    cycle = find_best_cycle(raw_frames, fps)
    pad = max(2, int(round(.45 * fps)))
    start = max(0, cycle.start_index - pad)
    end = min(len(raw_frames) - 1, cycle.end_index + pad)
    selected = raw_frames[start:end + 1]
    bbox = _motion_bbox(selected)
    session_id = f"g2-real-rgb-{spec.exercise_id}"
    root = output_root / session_id
    if root.exists():
        shutil.rmtree(root)
    (root / "frames").mkdir(parents=True)
    (root / "ground_truth").mkdir(parents=True)
    frame_records = []
    for i, frame in enumerate(selected):
        transformed = _fit_output(_square_crop(frame, bbox))
        image_rel = f"frames/{i:06d}.jpg"
        gt_rel = f"ground_truth/{i:06d}.json"
        cv2.imwrite(str(root / image_rel), transformed, [cv2.IMWRITE_JPEG_QUALITY, 91])
        timestamp_us = int(round(i * 1_000_000.0 / fps))
        ground = {
            "schema_version": 1,
            "evidence_tier": "G2_REAL_RGB",
            "session_id": session_id,
            "exercise_id": spec.exercise_id,
            "frame_id": i,
            "timestamp_us": timestamp_us,
            "expected_reps": 1,
            "rep_window": {
                "start_us": int(round((cycle.start_index - start) * 1_000_000.0 / fps)),
                "turn_us": int(round((cycle.turn_index - start) * 1_000_000.0 / fps)),
                "end_us": int(round((cycle.end_index - start) * 1_000_000.0 / fps)),
                "label_source": "independent_dense_optical_flow",
            },
        }
        (root / gt_rel).write_text(json.dumps(ground, indent=2) + "\n", encoding="utf-8")
        frame_records.append({
            "frame_id": i, "timestamp_us": timestamp_us, "width": 640, "height": 640,
            "mime_type": "image/jpeg", "image_path": image_rel, "ground_truth_path": gt_rel,
        })
    manifest = {
        "schema_version": 1, "session_id": session_id, "exercise_id": spec.exercise_id,
        "fps": fps, "width": 640, "height": 640, "source": "real_prerecorded_rgb",
        "frames": frame_records,
    }
    (root / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    evidence = {
        "schema_version": 1,
        "evidence_tier": "G2_REAL_RGB",
        "exercise_id": spec.exercise_id,
        "session_id": session_id,
        "source_title": spec.source_title,
        "source_url": spec.source_url,
        "source_page": spec.source_page,
        "equipment": spec.equipment,
        "expected_view": spec.expected_view,
        "video_sha256": _sha256(video),
        "source_frame_count_sampled": len(raw_frames),
        "session_frame_count": len(selected),
        "analysis_fps": fps,
        "independent_label": {
            "expected_reps": 1,
            "rep_window_us": [
                int(round((cycle.start_index - start) * 1_000_000.0 / fps)),
                int(round((cycle.end_index - start) * 1_000_000.0 / fps)),
            ],
            "turn_us": int(round((cycle.turn_index - start) * 1_000_000.0 / fps)),
            "method": "dense_optical_flow_direction_cycle",
            "score": cycle.score,
        },
    }
    (root / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    return evidence


def _stress_variant(base_root: Path, variant: str, device_seed: int = 5600) -> dict[str, Any]:
    rng = np.random.default_rng(device_seed)
    manifest = json.loads((base_root / "manifest.json").read_text())
    evidence = json.loads((base_root / "evidence.json").read_text())
    frames = []
    source_frames = manifest["frames"]
    if variant == "degraded":
        keep = list(range(len(source_frames)))
    elif variant == "gap":
        rep_start, rep_end = evidence["independent_label"]["rep_window_us"]
        midpoint = (rep_start + rep_end) // 2
        gap_half = 260_000
        keep = [i for i, f in enumerate(source_frames) if not (midpoint-gap_half <= f["timestamp_us"] <= midpoint+gap_half)]
    else:
        raise ValueError(variant)
    out_root = base_root.parent / (base_root.name.replace("g2-real-rgb", f"g3-{variant}"))
    if out_root.exists(): shutil.rmtree(out_root)
    (out_root / "frames").mkdir(parents=True)
    (out_root / "ground_truth").mkdir(parents=True)
    for new_i, old_i in enumerate(keep):
        src = source_frames[old_i]
        img = cv2.imread(str(base_root / src["image_path"]))
        if img is None: raise FileNotFoundError(base_root / src["image_path"])
        if variant == "degraded":
            # Target-device-like worst-case image degradation: dim, mild sensor noise and blur.
            img = np.clip(img.astype(np.float32) * 0.68 + rng.normal(0, 3.5, img.shape), 0, 255).astype(np.uint8)
            img = cv2.GaussianBlur(img, (3, 3), 0.8)
            # Short camera bump before the rep midpoint; transform pixels, not oracle labels.
            if len(keep) // 5 <= new_i < len(keep) // 5 + 3:
                mat = np.float32([[1, 0, 18], [0, 1, -12]])
                img = cv2.warpAffine(img, mat, (img.shape[1], img.shape[0]), borderMode=cv2.BORDER_REFLECT)
        rel = f"frames/{new_i:06d}.jpg"
        gt_rel = f"ground_truth/{new_i:06d}.json"
        cv2.imwrite(str(out_root / rel), img, [cv2.IMWRITE_JPEG_QUALITY, 88])
        gt = json.loads((base_root / src["ground_truth_path"]).read_text())
        gt["session_id"] = out_root.name
        gt["frame_id"] = new_i
        gt["timestamp_us"] = src["timestamp_us"]
        gt["evidence_tier"] = "G3_INTEGRATED_REALITY_STRESS"
        gt["stress_variant"] = variant
        # A gap deliberately bisecting the independently labelled rep must not be stitched.
        gt["expected_reps"] = 0 if variant == "gap" else 1
        (out_root / gt_rel).write_text(json.dumps(gt, indent=2) + "\n")
        frames.append({
            "frame_id": new_i, "timestamp_us": src["timestamp_us"], "width": src["width"], "height": src["height"],
            "mime_type": "image/jpeg", "image_path": rel, "ground_truth_path": gt_rel,
        })
    out_manifest = dict(manifest)
    out_manifest["session_id"] = out_root.name
    out_manifest["source"] = f"g3_{variant}_from_real_rgb"
    out_manifest["frames"] = frames
    (out_root / "manifest.json").write_text(json.dumps(out_manifest, indent=2) + "\n")
    out_evidence = dict(evidence)
    out_evidence.update({
        "evidence_tier": "G3_INTEGRATED_REALITY_STRESS", "session_id": out_root.name,
        "session_frame_count": len(frames),
        "stress_variant": variant, "expected_reps": 0 if variant == "gap" else 1,
        "device_profile": {
            "profile_id": "android-conservative-v1",
            "purpose": "deterministic worst-case camera/performance stress, not a claim about a specific physical unit",
            "low_light_scale": 0.68 if variant == "degraded" else 1.0,
            "blur_sigma": 0.8 if variant == "degraded" else 0.0,
            "mid_rep_gap_us": 520_000 if variant == "gap" else 0,
        },
    })
    (out_root / "evidence.json").write_text(json.dumps(out_evidence, indent=2) + "\n")
    return out_evidence


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--sources", type=Path, required=True)
    p.add_argument("--output-root", type=Path, required=True)
    p.add_argument("--cache", type=Path, default=Path("build/real-rgb-cache"))
    p.add_argument("--fps", type=float, default=DEFAULT_ANALYSIS_FPS)
    p.add_argument("--include-g3", action="store_true")
    args = p.parse_args()
    args.output_root.mkdir(parents=True, exist_ok=True)
    summaries = []
    for spec in _load_specs(args.sources):
        video = _download(spec, args.cache)
        g2 = _write_session(spec, video, args.output_root, args.fps)
        summaries.append(g2)
        if args.include_g3:
            root = args.output_root / g2["session_id"]
            summaries.append(_stress_variant(root, "degraded"))
            summaries.append(_stress_variant(root, "gap"))
    summary = {"schema_version":1,"sessions":summaries}
    (args.output_root / "release-rgb-sessions.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(f"REAL_RGB_RELEASE_SESSIONS_READY sessions={len(summaries)}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
