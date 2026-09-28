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
