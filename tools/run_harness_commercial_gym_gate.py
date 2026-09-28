#!/usr/bin/env python3
from __future__ import annotations

import argparse
import concurrent.futures
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

from harness.gym_episode_runtime import run_commercial_gym_episode_benchmark
from harness.profiles import EXERCISES, external_subjects
from harness.runner import Harness

RELEASE_EXERCISES = {
    "incline_db_press",
    "smith_squat",
    "lateral_raise",
}
SUBJECT_ID = "ansur_median_central"
DEFAULT_G2G3_OUTPUT = Path("build/harness-contracts/g2g3-prepared")


def run_sim_gym() -> dict:
    subject = next(x for x in external_subjects() if x.id == SUBJECT_ID)
    static = Harness().commercial_gym_deterministic(
        subjects=[subject],
        calibrated=False,
    )
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

    print(f"HARNESS_COMMERCIAL_GYM_STATIC_PASS cases={len(static)} subject={SUBJECT_ID}")
    print(f"HARNESS_COMMERCIAL_GYM_TEMPORAL_PASS episodes={len(episodes)} exercises={len(release)} subject={SUBJECT_ID}")
    return {
        "subject_id": SUBJECT_ID,
        "static_cases": int(len(static)),
        "temporal_episodes": int(len(episodes)),
        "release_exercises": sorted(RELEASE_EXERCISES),
        "static_failures": 0,
        "temporal_failures": 0,
    }


def ensure_mediapipe() -> None:
    if importlib.util.find_spec("mediapipe") is not None:
        return
    print("Installing MediaPipe for independent real-RGB oracle...")
    subprocess.run(
        [sys.executable, "-m", "pip", "install", "mediapipe>=0.10.14"],
        check=True,
    )


def run_g2g3_prepare(output: Path) -> dict:
    ensure_mediapipe()
    cmd = [
        sys.executable,
        "tools/g2g3_link_pipeline.py",
        "prepare",
        "--registry",
        "config/g2g3_release_sources.json",
        "--harness-root",
        "harness-src",
        "--model",
        "app/src/main/assets/pose_landmarker_lite.task",
        "--output",
        str(output),
    ]
    subprocess.run(cmd, check=True)
    prepared = json.loads((output / "prepared.json").read_text(encoding="utf-8"))
    return {
        "all_hashes_pinned": bool(prepared.get("all_hashes_pinned")),
        "session_count": len(prepared.get("sessions", [])),
        "sources": [
            {
                "exercise_id": x["exercise_id"],
                "source_name": x.get("source_name"),
                "url": x["url"],
                "sha256": x["sha256"],
                "hash_pinned": x["hash_pinned"],
                "expected_reps": x["oracle"]["expected_reps"],
                "oracle_pose_coverage": x["oracle"]["pose_coverage"],
            }
            for x in prepared["sources"]
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    parser.add_argument("--g2g3-output", type=Path, default=DEFAULT_G2G3_OUTPUT)
    parser.add_argument("--skip-g2g3", action="store_true")
    args = parser.parse_args()

    if args.skip_g2g3:
        sim = run_sim_gym()
        g2g3 = {"status": "SKIPPED"}
    else:
        # Independent workloads: real-video acquisition/oracle/session preparation and
        # synthetic commercial-gym stress generation run concurrently.
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            sim_future = pool.submit(run_sim_gym)
            g2g3_future = pool.submit(run_g2g3_prepare, args.g2g3_output)
            sim = sim_future.result()
            g2g3 = g2g3_future.result()

    summary = {
        "schema_version": 2,
        "evidence_tier": "INTEGRATED_G1_G2_G3_PREPARATION",
        **sim,
        "g2g3_prepare": g2g3,
        "physical_device_required": False,
        "g3_real_device_claim": False,
    }
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")

    print("HARNESS_COMMERCIAL_GYM_SIMULATION_PASS")
    if not args.skip_g2g3:
        print(f"G2G3_PARALLEL_PREPARATION_PASS sessions={g2g3['session_count']} hashes_pinned={g2g3['all_hashes_pinned']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
