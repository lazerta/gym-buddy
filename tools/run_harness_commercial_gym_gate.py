#!/usr/bin/env python3
from __future__ import annotations

import argparse
import concurrent.futures
import ctypes.util
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import urllib.request

from harness.gym_episode_runtime import run_commercial_gym_episode_benchmark
from harness.profiles import EXERCISES, external_subjects
from harness.runner import Harness

RELEASE_EXERCISES = {
    "incline_db_press",
    "smith_squat",
    "lateral_raise",
}
SUBJECT_ID = "ansur_median_central"
POSE_MODEL_URL = (
    "https://storage.googleapis.com/mediapipe-models/"
    "pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task"
)


def run_sim_gym() -> dict:
    subject = next(x for x in external_subjects() if x.id == SUBJECT_ID)
    static = Harness().commercial_gym_deterministic(subjects=[subject], calibrated=False)
    failed_static = static[~static["passed"]]
    if not failed_static.empty:
        print(failed_static.to_string(index=False))
        raise AssertionError(f"{len(failed_static)} commercial-gym static cases failed")

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
        raise AssertionError(f"{len(failed_episodes)} commercial-gym temporal episodes failed")

    return {
        "schema_version": 2,
        "evidence_tier": "G1_SYNTHETIC_COMMERCIAL_GYM",
        "subject_id": SUBJECT_ID,
        "static_cases": int(len(static)),
        "temporal_episodes": int(len(episodes)),
        "release_exercises": sorted(RELEASE_EXERCISES),
        "static_failures": 0,
        "temporal_failures": 0,
        "physical_device_claim": False,
    }


def ensure_pose_model(path: Path) -> None:
    if path.exists() and path.stat().st_size > 1024:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    print(f"Downloading independent harness pose model -> {path}")
    with urllib.request.urlopen(POSE_MODEL_URL, timeout=120) as response, tmp.open("wb") as out:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            out.write(chunk)
    if tmp.stat().st_size < 1024:
        raise RuntimeError("downloaded pose model is unexpectedly small")
    tmp.replace(path)


def ensure_vision_runtime() -> None:
    # MediaPipe's Linux wheel loads EGL even for CPU/video inference. Hosted
    # Ubuntu runners do not always include libEGL.so.1, so provision the minimal
    # runtime only when missing. This is test infrastructure only.
    if ctypes.util.find_library("EGL") is None:
        print("Installing minimal EGL runtime for MediaPipe")
        subprocess.run(["sudo", "apt-get", "update"], check=True)
        subprocess.run(
            ["sudo", "apt-get", "install", "-y", "libegl1", "libgl1"],
            check=True,
        )
    if importlib.util.find_spec("mediapipe") is None:
        print("Installing external harness vision dependency (mediapipe)")
        subprocess.run(
            [sys.executable, "-m", "pip", "install", "mediapipe>=0.10.14"],
            check=True,
        )


def run_g2_prepare(sources: Path, output: Path, model: Path) -> None:
    ensure_vision_runtime()
    ensure_pose_model(model)
    cmd = [
        sys.executable,
        "tools/g2g3_release_pipeline.py",
        "prepare",
        "--sources",
        str(sources),
        "--output",
        str(output),
        "--model",
        str(model),
    ]
    subprocess.run(cmd, check=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    parser.add_argument("--g2-sources", type=Path, default=Path("tools/g2_real_video_sources.json"))
    parser.add_argument("--g2-output", type=Path, default=Path("build/g2g3"))
    parser.add_argument("--pose-model", type=Path, default=Path("build/g2g3/pose_landmarker_lite.task"))
    parser.add_argument("--skip-g2", action="store_true")
    args = parser.parse_args()

    # G1 Sim Gym and all G2 link preparation are independent and intentionally run
    # at the same time. g2g3_release_pipeline parallelizes the three exercise links.
    if args.skip_g2:
        summary = run_sim_gym()
    else:
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            sim_future = pool.submit(run_sim_gym)
            g2_future = pool.submit(run_g2_prepare, args.g2_sources, args.g2_output, args.pose_model)
            summary = sim_future.result()
            g2_future.result()

    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")

    print(f"HARNESS_COMMERCIAL_GYM_STATIC_PASS cases={summary['static_cases']} subject={SUBJECT_ID}")
    print(f"HARNESS_COMMERCIAL_GYM_TEMPORAL_PASS episodes={summary['temporal_episodes']} exercises={len(RELEASE_EXERCISES)} subject={SUBJECT_ID}")
    print("HARNESS_COMMERCIAL_GYM_SIMULATION_PASS")
    if not args.skip_g2:
        print("G2_LINK_PREPARATION_PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
