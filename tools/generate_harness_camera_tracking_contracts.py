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

SELECTED_FAMILIES = (
    "clean",
    "wrong_view",
    "too_close",
    "camera_bump",
    "bystander_bg",
    "spotter",
    "foreground_occlusion",
    "motion_blur",
    "low_light",
    "tracking_gap",
    "target_exit_reenter",
)

BASE_SEED = 870000
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

    from harness.oracle import expected_for
    from harness.profiles import EXERCISES, external_subjects
    from harness.scenarios import deterministic_scenario
    from harness.simulator import simulate

    ex_by_id = {x.id: x for x in EXERCISES}
    subject = next(x for x in external_subjects() if x.id == SUBJECT_ID)

    output = io.StringIO()
    output.write("# schema_version=1\n")
    output.write(
        "# harness_repo=lazerta/virtual-gym-buddy-simulator-harness\n"
    )
    output.write(f"# harness_commit={commit}\n")
    output.write(f"# subject_id={SUBJECT_ID}\n")

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
            "family",
            "seed",
            "frame_fill",
            "visible_required_fraction",
            "tracking_quality",
            "camera_motion_score",
            "tracking_gap_ms",
            "detected_people",
            "oracle_ready",
            "oracle_pause",
            "oracle_reset_once",
            "oracle_reason",
        ]
    )

    for exercise_index, (app_id, harness_id) in enumerate(
        APP_TO_HARNESS.items()
    ):
        exercise = ex_by_id[harness_id]

        for family_index, family in enumerate(SELECTED_FAMILIES):
            seed = (
                BASE_SEED
                + exercise_index * 1000
                + family_index * 17
            )
            scenario = deterministic_scenario(family, seed)
            observation = simulate(subject, exercise, scenario)
            expected = expected_for(scenario)

            writer.writerow(
                [
                    f"{app_id}:{family}",
                    app_id,
                    harness_id,
                    family,
                    seed,
                    f"{observation.frame_fill:.9f}",
                    f"{observation.visible_required_fraction:.9f}",
                    f"{observation.tracking_quality:.9f}",
                    f"{observation.camera_motion_score:.9f}",
                    observation.tracking_gap_ms,
                    observation.detected_people,
                    str(expected.ready).lower(),
                    str(expected.pause).lower(),
                    str(expected.reset_once).lower(),
                    expected.reason,
                ]
            )

    return output.getvalue()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--harness-commit")
    args = parser.parse_args()

    commit = harness_commit(
        args.harness_root,
        args.harness_commit,
    )
    generated = render(
        args.harness_root,
        commit,
    )

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
