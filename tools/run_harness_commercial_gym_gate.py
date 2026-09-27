#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path

from harness.gym_episode_runtime import run_commercial_gym_episode_benchmark
from harness.profiles import EXERCISES, external_subjects
from harness.runner import Harness

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
