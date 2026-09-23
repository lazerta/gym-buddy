#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import io
import subprocess
import sys
from pathlib import Path

APP_TO_HARNESS = {
    "incline_dumbbell_press": "incline_db_press",
    "smith_machine_squat": "smith_squat",
    "dumbbell_lateral_raise": "lateral_raise",
}

SELECTED_CASES = (
    "clean_5",
    "partial_only",
    "half_reverse",
    "failed_concentric",
    "bottom_pause",
    "top_pause",
    "slow_grinder",
    "bounce",
    "pulse_cluster",
    "rest_pause",
    "setup_then_clean",
    "tracking_gap_mid_rep_then_clean",
)

BASE_SEED = 760000
FPS = 20
SUBJECT_ID = "ansur_median_central"


def harness_commit(root: Path, explicit: str | None) -> str:
    if explicit:
        return explicit
    return subprocess.check_output(
        ["git", "-C", str(root), "rev-parse", "HEAD"],
        text=True,
    ).strip()


def render(root: Path, commit: str) -> str:
    sys.path.insert(0, str(root))

    from harness.rep_detection import REP_CASES, generate_rep_case
    from harness.profiles import EXERCISES, external_subjects

    truth_by_id = {x.case_id: x for x in REP_CASES}
    ex_by_id = {x.id: x for x in EXERCISES}
    subjects = external_subjects()
    subject = next(x for x in subjects if x.id == SUBJECT_ID)
    subject_index = subjects.index(subject)

    output = io.StringIO()
    output.write("# schema_version=1\n")
    output.write(
        "# harness_repo=lazerta/virtual-gym-buddy-simulator-harness\n"
    )
    output.write(f"# harness_commit={commit}\n")
    output.write(f"# subject_id={SUBJECT_ID}\n")
    output.write(f"# fps={FPS}\n")

    writer = csv.writer(
        output,
        delimiter="\t",
        lineterminator="\n",
    )
    writer.writerow(
        [
            "case_key",
            "exercise_id",
            "harness_exercise_id",
            "case_id",
            "seed",
            "expected_completed_reps",
            "timestamp_us",
            "progress",
            "valid",
        ]
    )

    for app_id, harness_id in APP_TO_HARNESS.items():
        exercise = ex_by_id[harness_id]
        exercise_index = EXERCISES.index(exercise)

        for case_id in SELECTED_CASES:
            truth = truth_by_id[case_id]
            if truth.total_range.low != truth.total_range.high:
                raise RuntimeError(
                    f"{case_id} does not have an exact rep-count contract"
                )

            case_index = REP_CASES.index(truth)
            seed = (
                BASE_SEED
                + subject_index * 100000
                + exercise_index * 1000
                + case_index * 11
            )
            frames = generate_rep_case(
                case_id,
                seed=seed,
                subject=subject,
                exercise=exercise,
                fps=FPS,
            )

            for frame in frames:
                writer.writerow(
                    [
                        f"{app_id}:{case_id}",
                        app_id,
                        harness_id,
                        case_id,
                        seed,
                        truth.total_range.low,
                        round(frame.t_s * 1_000_000),
                        f"{frame.depth:.9f}",
                        str(frame.valid).lower(),
                    ]
                )

    return output.getvalue()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--harness-commit")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    commit = harness_commit(
        args.harness_root,
        args.harness_commit,
    )
    generated = render(
        args.harness_root,
        commit,
    )

    if args.check:
        existing = (
            args.output.read_text(encoding="utf-8")
            if args.output.exists()
            else ""
        )
        if existing != generated:
            print(
                f"harness temporal fixture drift: {args.output}",
                file=sys.stderr,
            )
            return 1
        print(
            f"HARNESS_TEMPORAL_FIXTURES_OK commit={commit}"
        )
        return 0

    args.output.parent.mkdir(
        parents=True,
        exist_ok=True,
    )
    args.output.write_text(
        generated,
        encoding="utf-8",
    )
    print(
        f"wrote {args.output} commit={commit}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
