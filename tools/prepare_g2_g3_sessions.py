#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import math
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np


@dataclass(frozen=True)
class Case:
    id: str
    exercise_id: str
    canonical_exercise: str
    source_url: str
    expected_reps: int
    label_basis: str
    view_requirement: str
    equipment_requirement: str
    clean_form_claim: bool


def load_registry(path: Path) -> tuple[float, list[Case]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("schema_version") != 1:
        raise ValueError("unsupported registry schema")
    target_fps = float(payload.get("target_fps", 5.0))
    if not 1.0 <= target_fps <= 30.0:
        raise ValueError("target_fps outside safe range")
    cases = []
    seen = set()
    for raw in payload["cases"]:
        case = Case(
            id=str(raw["id"]),
            exercise_id=str(raw["exercise_id"]),
            canonical_exercise=str(raw["canonical_exercise"]),
            source_url=str(raw["source_url"]),
            expected_reps=int(raw["expected_reps"]),
            label_basis=str(raw["label_basis"]),
            view_requirement=str(raw["view_requirement"]),
            equipment_requirement=str(raw["equipment_requirement"]),
            clean_form_claim=bool(raw.get("clean_form_claim", False)),
        )
        if case.id in seen:
            raise ValueError(f"duplicate case id: {case.id}")
        seen.add(case.id)
        if case.exercise_id not in {"incline_db_press", "smith_squat", "lateral_raise"}:
            raise ValueError(f"unexpected release exercise: {case.exercise_id}")
        if case.expected_reps <= 0:
            raise ValueError(f"invalid expected reps: {case.id}")
        cases.append(case)
    if {x.exercise_id for x in cases} != {"incline_db_press", "smith_squat", "lateral_raise"}:
        raise ValueError("registry must cover exactly the three release exercise families")
    return target_fps, cases


def url_target(url: str, root: Path) -> Path:
    digest = hashlib.sha256(url.encode("utf-8")).hexdigest()[:16]
    return root / f"url_{digest}.mp4"


def download(case: Case, download_root: Path) -> Path:
    download_root.mkdir(parents=True, exist_ok=True)
    target = url_target(case.source_url, download_root)
    if target.exists() and target.stat().st_size > 0:
        return target
    template = str(target.with_suffix(".%(ext)s"))
    cmd = [
        sys.executable, "-m", "yt_dlp", "--no-playlist", "--no-part",
        "--retries", "3", "--fragment-retries", "3", "--socket-timeout", "30",
        "-f", "best[height<=720][ext=mp4]/best[height<=720]/best", "-o", template,
        "--print", "after_move:filepath", case.source_url,
    ]
    proc = subprocess.run(cmd, check=True, text=True, capture_output=True)
    candidates = [Path(x.strip()) for x in proc.stdout.splitlines() if x.strip()]
    if candidates and candidates[-1].exists():
        return candidates[-1].resolve()
    if target.exists():
        return target
    fallback = [p for p in target.parent.glob(f"{target.stem}.*") if p.is_file() and p.suffix not in {".part", ".ytdl"}]
    if fallback:
        return sorted(fallback)[0]
    raise FileNotFoundError(f"download produced no video for {case.id}")


def stress_frame(frame: np.ndarray, index: int) -> np.ndarray:
    """Deterministic camera/environment stress; never uses detector output."""
    h, w = frame.shape[:2]
    gain = 0.72 + 0.20 * (0.5 + 0.5 * math.sin(index * 0.37))
    stressed = np.clip(frame.astype(np.float32) * gain, 0, 255).astype(np.uint8)
    if index % 11 in {5, 6}:
        stressed = cv2.GaussianBlur(stressed, (7, 3), 0)
    dx = int(round(w * (0.025 if index % 37 in {0, 1, 2} else 0.0)))
    dy = int(round(h * (-0.02 if index % 53 in {0, 1} else 0.0)))
    if dx or dy:
        matrix = np.float32([[1, 0, dx], [0, 1, dy]])
        stressed = cv2.warpAffine(stressed, matrix, (w, h), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT_101)
    return stressed


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def make_session(case: Case, video: Path, out_root: Path, variant: str, target_fps: float) -> Path:
    session_id = f"{case.id}-{variant}"
    root = out_root / session_id
    if root.exists():
        shutil.rmtree(root)
    frames_dir = root / "frames"
    gt_dir = root / "ground_truth"
    frames_dir.mkdir(parents=True)
    gt_dir.mkdir(parents=True)

    cap = cv2.VideoCapture(str(video))
    if not cap.isOpened():
        raise FileNotFoundError(video)
    source_fps = float(cap.get(cv2.CAP_PROP_FPS) or 30.0)
    source_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH) or 0)
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0)
    if source_fps <= 0 or width <= 0 or height <= 0:
        cap.release()
        raise ValueError(f"invalid video metadata: {video}")
    sample_interval = source_fps / target_fps
    next_sample = 0.0
    input_idx = 0
    kept = 0
    sampled = 0
    manifest_frames = []
    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            if input_idx + 1e-9 < next_sample:
                input_idx += 1
                continue
            drop = variant == "stress" and sampled > 0 and sampled % 47 in {23, 24}
            timestamp_us = int(round(input_idx / source_fps * 1_000_000))
            next_sample += sample_interval
            input_idx += 1
            stress_index = sampled
            sampled += 1
            if drop:
                continue
            output = stress_frame(frame, stress_index) if variant == "stress" else frame
            image_rel = f"frames/{kept:06d}.jpg"
            image_path = root / image_rel
            if not cv2.imwrite(str(image_path), output, [int(cv2.IMWRITE_JPEG_QUALITY), 92]):
                raise RuntimeError(f"failed writing {image_path}")
            gt_rel = f"ground_truth/{kept:06d}.json"
            gt = {
                "schema_version": 1,
                "session_id": session_id,
                "exercise_id": case.exercise_id,
                "frame_id": kept,
                "timestamp_us": timestamp_us,
                "independent_set_expected_reps": case.expected_reps,
                "label_basis": case.label_basis,
                "variant": variant,
            }
            (root / gt_rel).write_text(json.dumps(gt, sort_keys=True) + "\n", encoding="utf-8")
            manifest_frames.append({
                "frame_id": kept,
                "timestamp_us": timestamp_us,
                "width": width,
                "height": height,
                "mime_type": "image/jpeg",
                "image_path": image_rel,
                "ground_truth_path": gt_rel,
            })
            kept += 1
    finally:
        cap.release()
    if kept < max(10, int(target_fps * 2)):
        raise ValueError(f"too few sampled frames for {case.id}: {kept}")

    source_meta = {
        "source_url": case.source_url,
        "source_sha256": sha256_file(video),
        "source_file_size": video.stat().st_size,
        "source_fps": source_fps,
        "source_frame_count": source_frames,
        "canonical_exercise": case.canonical_exercise,
        "view_requirement": case.view_requirement,
        "equipment_requirement": case.equipment_requirement,
        "expected_reps": case.expected_reps,
        "label_basis": case.label_basis,
        "clean_form_claim": case.clean_form_claim,
        "raw_rgb_required": True,
        "oracle_exposed_to_app": False,
    }
    (root / "oracle.json").write_text(json.dumps(source_meta, indent=2) + "\n", encoding="utf-8")
    manifest = {
        "schema_version": 1,
        "session_id": session_id,
        "exercise_id": case.exercise_id,
        "fps": target_fps,
        "width": width,
        "height": height,
        "source": "real-prerecorded-public-rgb" if variant == "baseline" else "real-prerecorded-public-rgb-stressed",
        "frames": manifest_frames,
    }
    (root / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return root


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--registry", type=Path, required=True)
    ap.add_argument("--output-root", type=Path, required=True)
    ap.add_argument("--download-root", type=Path)
    ap.add_argument("--video", action="append", default=[], help="case_id=/path/video override")
    ap.add_argument("--case", action="append", default=[])
    ap.add_argument("--variant", choices=["baseline", "stress", "both"], default="both")
    args = ap.parse_args()
    target_fps, cases = load_registry(args.registry)
    overrides = {}
    for item in args.video:
        key, sep, val = item.partition("=")
        if not sep:
            raise ValueError("--video must be case_id=/path")
        overrides[key] = Path(val).resolve()
    selected = [c for c in cases if not args.case or c.id in set(args.case)]
    missing = set(args.case) - {c.id for c in cases}
    if missing:
        raise KeyError(f"unknown cases: {sorted(missing)}")
    variants = [args.variant] if args.variant != "both" else ["baseline", "stress"]
    download_root = args.download_root or (args.output_root / "_downloads")
    made = []
    for case in selected:
        video = overrides.get(case.id) or download(case, download_root)
        for variant in variants:
            root = make_session(case, video, args.output_root, variant, target_fps)
            made.append(str(root))
            print(f"PREPARED {case.id} {variant} frames={len(json.loads((root/'manifest.json').read_text())['frames'])}")
    print(json.dumps({"sessions": made}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
