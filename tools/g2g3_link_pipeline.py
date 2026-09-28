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
