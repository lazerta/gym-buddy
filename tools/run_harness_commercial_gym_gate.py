#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import base64
import hashlib
import urllib.request
import cv2
import numpy as np
from pathlib import Path

from harness.gym_episode_runtime import run_commercial_gym_episode_benchmark
from harness.profiles import EXERCISES, external_subjects
from harness.runner import Harness
from harness.video_motion_pipeline import download_public_video


HUMAN_G2_SOURCES = (
    ("incline_db_press_human", "incline_db_press", "direct", "https://wellulu.com/wp-content/uploads/2024/03/15-1.Incline-Dumbbell-Press-1.mp4"),
    ("smith_squat_human", "smith_squat", "direct", "https://wellulu.com/wp-content/uploads/2024/03/3-12.smith-machine_squat.mp4"),
    ("lateral_raise_human", "lateral_raise", "direct", "https://wellulu.com/wp-content/uploads/2025/12/lateral_raise.mp4"),
)

def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def _contact_sheet(path: Path, samples: int = 48) -> tuple[str, list[dict]]:
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        raise AssertionError(f"cannot open human G2 source {path}")
    fps = float(cap.get(cv2.CAP_PROP_FPS) or 0.0)
    count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    if fps <= 0 or count <= 0:
        raise AssertionError(f"invalid video metadata {path}: fps={fps} count={count}")
    indices = np.linspace(0, count - 1, min(samples, count), dtype=int)
    thumbs = []
    index = []
    for raw in indices:
        frame_index = int(raw)
        cap.set(cv2.CAP_PROP_POS_FRAMES, frame_index)
        ok, frame = cap.read()
        if not ok:
            continue
        h, w = frame.shape[:2]
        scale = min(320 / w, 240 / h)
        frame = cv2.resize(frame, (max(1, int(w * scale)), max(1, int(h * scale))))
        timestamp_s = frame_index / fps
        label = f"{frame_index} {timestamp_s:.2f}s"
        cv2.putText(frame, label, (8, 22), cv2.FONT_HERSHEY_SIMPLEX, .55, (255,255,255), 2, cv2.LINE_AA)
        cv2.putText(frame, label, (8, 22), cv2.FONT_HERSHEY_SIMPLEX, .55, (0,0,0), 1, cv2.LINE_AA)
        thumbs.append(frame)
        index.append({"frame": frame_index, "timestamp_s": timestamp_s})
    cap.release()
    cols = 4
    rows = (len(thumbs) + cols - 1) // cols
    cell_w = max(x.shape[1] for x in thumbs)
    cell_h = max(x.shape[0] for x in thumbs)
    canvas = np.zeros((rows * cell_h, cols * cell_w, 3), dtype=np.uint8)
    for i, frame in enumerate(thumbs):
        r, c = divmod(i, cols)
        y, x = r * cell_h, c * cell_w
        canvas[y:y+frame.shape[0], x:x+frame.shape[1]] = frame
    ok, encoded = cv2.imencode(".jpg", canvas, [int(cv2.IMWRITE_JPEG_QUALITY), 88])
    if not ok:
        raise AssertionError(f"cannot encode contact sheet {path}")
    return base64.b64encode(encoded.tobytes()).decode("ascii"), index

def _download_direct(url: str, case_id: str) -> Path:
    root = Path("build/g2-human-probe/direct")
    root.mkdir(parents=True, exist_ok=True)
    path = root / f"{case_id}.mp4"
    req = urllib.request.Request(url, headers={"User-Agent": "GymBuddy-G2-Probe/1"})
    with urllib.request.urlopen(req, timeout=120) as response, path.open("wb") as out:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            out.write(chunk)
    if path.stat().st_size <= 0:
        raise AssertionError(f"empty real-human source {url}")
    return path

def _probe_human_g2_sources() -> tuple[list[dict], list[dict]]:
    rows = []
    failures = []
    partial = Path("build/g2-human-probe/partial-summary.json")
    partial.parent.mkdir(parents=True, exist_ok=True)
    for case_id, exercise_id, method, url in HUMAN_G2_SOURCES:
        try:
            if method == "direct":
                video = _download_direct(url, case_id)
            else:
                video = download_public_video(
                    url,
                    exercise_id,
                    work_root="build/g2-human-probe/yt-dlp",
                )
            cap = cv2.VideoCapture(str(video))
            if not cap.isOpened():
                raise AssertionError(f"cannot decode {video}")
            fps = float(cap.get(cv2.CAP_PROP_FPS) or 0.0)
            frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
            width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH) or 0)
            height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0)
            cap.release()
            sheet, sheet_index = _contact_sheet(video)
            row = {
                "case_id": case_id,
                "exercise_id": exercise_id,
                "method": method,
                "url": url,
                "sha256": _sha256(video),
                "bytes": video.stat().st_size,
                "fps": fps,
                "frame_count": frame_count,
                "duration_s": frame_count / fps,
                "width": width,
                "height": height,
                "contact_sheet_jpeg_base64": sheet,
                "contact_sheet_index": sheet_index,
            }
            rows.append(row)
            print(
                f"HUMAN_G2_SOURCE_PROBED {case_id} sha256={row['sha256']} "
                f"frames={frame_count} duration={row['duration_s']:.3f}s"
            )
        except Exception as exc:
            failure = {
                "case_id": case_id,
                "exercise_id": exercise_id,
                "method": method,
                "url": url,
                "error": f"{type(exc).__name__}: {exc}",
            }
            failures.append(failure)
            print(f"HUMAN_G2_SOURCE_FAILED {case_id} {failure['error']}")
        partial.write_text(
            json.dumps({"sources": rows, "failures": failures}, indent=2) + "\n",
            encoding="utf-8",
        )
    print(f"HUMAN_G2_SOURCE_PROBE_DONE pass={len(rows)} fail={len(failures)}")
    return rows, failures


RELEASE_EXERCISES = {
    "incline_db_press",
    "smith_squat",
    "lateral_raise",
}
SUBJECT_ID = "ansur_median_central"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    subject = next(x for x in external_subjects() if x.id == SUBJECT_ID)

    static = Harness().commercial_gym_deterministic(
        subjects=[subject],
        calibrated=False,
    )
    failed_static = static[~static["passed"]]
    if not failed_static.empty:
        print(failed_static.to_string(index=False))
        raise AssertionError(
            f"{len(failed_static)} commercial-gym static cases failed"
        )

    release = [x for x in EXERCISES if x.id in RELEASE_EXERCISES]
    episodes = run_commercial_gym_episode_benchmark(
        [subject],
        seeds_per_episode=1,
        base_seed=910000,
        exercises=release,
        fps=5,
    )
    failed_episodes = episodes[~episodes["passed"]]
    if not failed_episodes.empty:
        print(failed_episodes.to_string(index=False))
        raise AssertionError(
            f"{len(failed_episodes)} commercial-gym temporal episodes failed"
        )

    human_g2_probe, human_g2_failures = _probe_human_g2_sources()

    summary = {
        "schema_version": 1,
        "evidence_tier": "SYNTHETIC_COMMERCIAL_GYM",
        "subject_id": SUBJECT_ID,
        "static_cases": int(len(static)),
        "temporal_episodes": int(len(episodes)),
        "release_exercises": sorted(RELEASE_EXERCISES),
        "static_failures": 0,
        "temporal_failures": 0,
        "g3_real_device_claim": False,
        "human_g2_source_probe": human_g2_probe,
        "human_g2_source_failures": human_g2_failures,
    }
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(summary, indent=2) + "\n",
            encoding="utf-8",
        )

    print(
        f"HARNESS_COMMERCIAL_GYM_STATIC_PASS "
        f"cases={len(static)} subject={SUBJECT_ID}"
    )
    print(
        f"HARNESS_COMMERCIAL_GYM_TEMPORAL_PASS "
        f"episodes={len(episodes)} exercises={len(release)} "
        f"subject={SUBJECT_ID}"
    )
    print("HARNESS_COMMERCIAL_GYM_SIMULATION_PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
