#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import io
import subprocess
import sys
from pathlib import Path

import numpy as np

SELECTED_SCENARIOS = (
    "background_bystander",
    "spotter",
    "foreground_cross",
    "full_occlusion",
    "track_id_change",
    "lookalike",
    "subject_swap",
    "exit_reenter",
)

VARIANT_SEEDS = (0, 1, 2)


def harness_commit(root: Path, explicit: str | None) -> str:
    if explicit:
        return explicit
    return subprocess.check_output(
        ["git", "-C", str(root), "rev-parse", "HEAD"],
        text=True,
    ).strip()


def range_bounds(value):
    if value is None:
        return "", ""
    values = list(value)
    if not values:
        return "", ""
    return values[0], values[-1]


def render(root: Path, commit: str) -> str:
    sys.path.insert(0, str(root))

    from harness.subject_lock import _temporal_sequence

    output = io.StringIO()
    output.write("# schema_version=1\n")
    output.write(
        "# harness_repo=lazerta/virtual-gym-buddy-simulator-harness\n"
    )
    output.write(f"# harness_commit={commit}\n")

    writer = csv.writer(
        output,
        delimiter="\t",
        lineterminator="\n",
    )
    writer.writerow(
        [
            "case_key",
            "scenario",
            "variant_seed",
            "frame_count",
            "may_pause",
            "occlusion_start",
            "occlusion_end",
            "ambiguous_start",
            "ambiguous_end",
            "lost_start",
            "lost_end",
            "max_reacquire",
            "reid_track",
        ]
    )

    for variant_seed in VARIANT_SEEDS:
        for scenario in SELECTED_SCENARIOS:
            rng = np.random.default_rng(220000 + variant_seed)
            frames, _truth, rules = _temporal_sequence(
                scenario,
                rng,
            )

            occlusion_start, occlusion_end = range_bounds(
                rules.get("occlusion_range")
            )
            ambiguous_start, ambiguous_end = range_bounds(
                rules.get("ambiguous_range")
            )
            lost_start, lost_end = range_bounds(
                rules.get("lost_range")
            )

            writer.writerow(
                [
                    f"{scenario}:{variant_seed}",
                    scenario,
                    variant_seed,
                    len(frames),
                    str(rules.get("may_pause", True)).lower(),
                    occlusion_start,
                    occlusion_end,
                    ambiguous_start,
                    ambiguous_end,
                    lost_start,
                    lost_end,
                    rules.get("max_reacquire", ""),
                    rules.get("reid_track", ""),
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
