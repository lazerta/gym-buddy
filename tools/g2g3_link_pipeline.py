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
