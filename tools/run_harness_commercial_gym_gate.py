#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import urllib.request
from dataclasses import dataclass, asdict
from pathlib import Path

import cv2
import numpy as np

from harness.gym_episode_runtime import run_commercial_gym_episode_benchmark
from harness.profiles import EXERCISES, external_subjects
from harness.runner import Harness

RELEASE_EXERCISES = {
    "incline_db_press",
    "smith_squat",
    "lateral_raise",
}
SUBJECT_ID = "ansur_median_central"


@dataclass(frozen=True)
class G2Source:
    case_id: str
    exercise_id: str
    sex: str
    url: str


G2_SOURCES = (
    G2Source(
        "incline_db_press_male",
        "incline_db_press",
        "male",
        "https://pub-585d42eb1aa64a67aedf483ec328d3fe.r2.dev/exercise-videos/male/dumbbell-incline-bench-press.mp4",
    ),
    G2Source(
        "incline_db_press_female",
        "incline_db_press",
        "female",
        "https://pub-585d42eb1aa64a67aedf483ec328d3fe.r2.dev/exercise-videos/female/dumbbell-incline-bench-press.mp4",
    ),
    G2Source(
        "smith_squat_male",
        "smith_squat",
        "male",
        "https://pub-585d42eb1aa64a67aedf483ec328d3fe.r2.dev/exercise-videos/male/smith-chair-squat.mp4",
    ),
    G2Source(
        "smith_squat_female",
        "smith_squat",
        "female",
        "https://pub-585d42eb1aa64a67aedf483ec328d3fe.r2.dev/exercise-videos/female/smith-chair-squat.mp4",
    ),
    G2Source(
        "lateral_raise_male",
        "lateral_raise",
        "male",
        "https://pub-585d42eb1aa64a67aedf483ec328d3fe.r2.dev/exercise-videos/male/dumbbell-lateral-raise.mp4",
    ),
    G2Source(
        "lateral_raise_female",
        "lateral_raise",
        "female",
        "https://pub-585d42eb1aa64a67aedf483ec328d3fe.r2.dev/exercise-videos/female/dumbbell-lateral-raise.mp4",
    ),
)


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _download(url: str, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": "GymBuddy-G2-Probe/1"})
    with urllib.request.urlopen(req, timeout=120) as response, path.open("wb") as out:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            out.write(chunk)
    if path.stat().st_size <= 0:
        raise AssertionError(f"empty G2 source: {url}")


def _video_probe(path: Path) -> dict:
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        raise AssertionError(f"cannot decode G2 source: {path}")
    fps = float(cap.get(cv2.CAP_PROP_FPS) or 0.0)
    frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH) or 0)
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0)
    cap.release()
    if fps <= 0 or frame_count <= 0 or width <= 0 or height <= 0:
        raise AssertionError(
            f"invalid G2 video metadata: fps={fps} frames={frame_count} size={width}x{height}"
        )
    return {
        "fps": fps,
        "frame_count": frame_count,
        "duration_s": frame_count / fps,
        "width": width,
        "height": height,
    }


def _contact_sheet_b64(path: Path, samples: int = 32) -> tuple[str, list[dict]]:
    cap = cv2.VideoCapture(str(path))
    fps = float(cap.get(cv2.CAP_PROP_FPS) or 0.0)
    frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    if not cap.isOpened() or fps <= 0 or frame_count <= 0:
        raise AssertionError(f"cannot build contact sheet for {path}")
    indices = np.linspace(0, frame_count - 1, min(samples, frame_count), dtype=int)
    thumbs: list[np.ndarray] = []
    index: list[dict] = []
    for raw in indices:
        frame_index = int(raw)
        cap.set(cv2.CAP_PROP_POS_FRAMES, frame_index)
        ok, frame = cap.read()
        if not ok:
            continue
        h, w = frame.shape[:2]
        scale = min(320 / w, 240 / h)
        frame = cv2.resize(
            frame,
            (max(1, int(w * scale)), max(1, int(h * scale))),
        )
        timestamp_s = frame_index / fps
        label = f"{frame_index} {timestamp_s:.2f}s"
        cv2.putText(
            frame,
            label,
            (8, 22),
            cv2.FONT_HERSHEY_SIMPLEX,
            .55,
            (255, 255, 255),
            2,
            cv2.LINE_AA,
        )
        cv2.putText(
            frame,
            label,
            (8, 22),
            cv2.FONT_HERSHEY_SIMPLEX,
            .55,
            (0, 0, 0),
            1,
            cv2.LINE_AA,
        )
        thumbs.append(frame)
        index.append({"frame": frame_index, "timestamp_s": timestamp_s})
    cap.release()
    if not thumbs:
        raise AssertionError(f"no contact sheet frames for {path}")
    columns = 4
    rows = (len(thumbs) + columns - 1) // columns
    cell_w = max(frame.shape[1] for frame in thumbs)
    cell_h = max(frame.shape[0] for frame in thumbs)
    canvas = np.zeros((rows * cell_h, columns * cell_w, 3), dtype=np.uint8)
    for i, frame in enumerate(thumbs):
        row, col = divmod(i, columns)
        y, x = row * cell_h, col * cell_w
        canvas[y:y + frame.shape[0], x:x + frame.shape[1]] = frame
    ok, encoded = cv2.imencode(".jpg", canvas, [int(cv2.IMWRITE_JPEG_QUALITY), 88])
    if not ok:
        raise AssertionError(f"contact sheet encoding failed: {path}")
    return base64.b64encode(encoded.tobytes()).decode("ascii"), index


def _probe_g2_sources(work_root: Path) -> list[dict]:
    rows: list[dict] = []
    for source in G2_SOURCES:
        video = work_root / f"{source.case_id}.mp4"
        _download(source.url, video)
        metadata = _video_probe(video)
        contact_sheet, contact_index = _contact_sheet_b64(video)
        row = {
            **asdict(source),
            "sha256": _sha256(video),
            "bytes": video.stat().st_size,
            **metadata,
            "contact_sheet_jpeg_base64": contact_sheet,
            "contact_sheet_index": contact_index,
        }
        rows.append(row)
        print(
            f"G2_SOURCE_PROBED {source.case_id} "
            f"sha256={row['sha256']} frames={row['frame_count']} "
            f"duration={row['duration_s']:.3f}s"
        )
    return rows


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

    g2_probe = _probe_g2_sources(Path("build/g2-source-probe-videos"))

    summary = {
        "schema_version": 2,
        "evidence_tier": "SYNTHETIC_COMMERCIAL_GYM_PLUS_G2_SOURCE_PROBE",
        "subject_id": SUBJECT_ID,
        "static_cases": int(len(static)),
        "temporal_episodes": int(len(episodes)),
        "release_exercises": sorted(RELEASE_EXERCISES),
        "static_failures": 0,
        "temporal_failures": 0,
        "g3_real_device_claim": False,
        "g2_source_probe": g2_probe,
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
    print(f"G2_SOURCE_PROBE_PASS cases={len(g2_probe)}")
    print("HARNESS_COMMERCIAL_GYM_SIMULATION_PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
