#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import subprocess
import sys
from dataclasses import dataclass
from io import StringIO
from pathlib import Path

APP_TO_HARNESS = {
    "incline_dumbbell_press": "incline_db_press",
    "smith_machine_squat": "smith_squat",
    "dumbbell_lateral_raise": "lateral_raise",
}

SCENARIO_FAMILIES = (
    "clean",
    "single_issue",
    "repeated_issue",
    "short_rom",
    "asymmetry",
)

SCENARIO_SEEDS = (41, 97, 211)
NATURAL_CASES = 120
BOUNDARY_CASES = 60


def harness_commit(root: Path, explicit: str | None) -> str:
    if explicit:
        return explicit
    return subprocess.check_output(
        ["git", "-C", str(root), "rev-parse", "HEAD"],
        text=True,
    ).strip()


def threshold_for(calibration) -> float:
    baseline = .10
    if calibration is not None:
        w = max(0.0, min(.65, calibration.confidence * .65))
        baseline = (1.0 - w) * .10 + w * calibration.issue_baseline
    return .56 + baseline * .18


@dataclass
class Captured:
    scenario_id: str
    exercise_id: str
    threshold: float
    evidence: tuple[float, ...]
    any_cue: bool


class CaptureAdapter:
    def __init__(self, delegate):
        self.delegate = delegate
        self.records: list[Captured] = []

    def analyze(self, obs, ex, calibration=None):
        out = self.delegate.analyze(obs, ex, calibration)
        self.records.append(
            Captured(
                scenario_id=obs.scenario_id,
                exercise_id=ex.id,
                threshold=threshold_for(calibration),
                evidence=tuple(obs.issue_evidence_by_rep),
                any_cue=out.cue_count > 0,
            )
        )
        return out


def append_benchmark_rows(
    rows,
    *,
    mode: str,
    n: int,
    seed: int,
    subjects,
):
    from harness.adapter import ReferenceProductionAdapter
    from harness.false_cue import run_false_cue_benchmark

    capture = CaptureAdapter(ReferenceProductionAdapter())
    frame = run_false_cue_benchmark(
        n=n,
        seed=seed,
        calibrated=False,
        adapter=capture,
        mode=mode,
        subjects=subjects,
    )
    if len(frame) != n or len(capture.records) != n:
        raise RuntimeError(
            f"{mode}: expected {n} captured cases, got "
            f"frame={len(frame)} records={len(capture.records)}"
        )

    for index, record in enumerate(capture.records):
        row_false_cue = bool(frame.iloc[index]["false_cue"])
        if row_false_cue != record.any_cue:
            raise RuntimeError(
                f"{mode}:{index}: benchmark row and delegate disagree"
            )
        rows.append(
            (
                f"{mode}:{index}:{record.exercise_id}",
                mode,
                record.exercise_id,
                mode,
                record.threshold,
                record.any_cue,
                False,
                record.evidence,
            )
        )


def render(root: Path, commit: str) -> str:
    sys.path.insert(0, str(root))

    from harness.adapter import ReferenceProductionAdapter
    from harness.oracle import expected_for
    from harness.profiles import EXERCISES, external_subjects
    from harness.scenarios import deterministic_scenario
    from harness.simulator import simulate

    by_id = {x.id: x for x in EXERCISES}
    subject = next(
        x for x in external_subjects()
        if x.id == "ansur_median_central"
    )
    delegate = ReferenceProductionAdapter()

    rows = []

    for _app_id, harness_id in APP_TO_HARNESS.items():
        exercise = by_id[harness_id]
        for family in SCENARIO_FAMILIES:
            for seed in SCENARIO_SEEDS:
                scenario = deterministic_scenario(family, seed)
                observation = simulate(subject, exercise, scenario)
                oracle = expected_for(scenario)
                result = delegate.analyze(observation, exercise, None)
                actual_any_cue = result.cue_count > 0

                if oracle.cue_expected and not actual_any_cue:
                    raise RuntimeError(
                        f"{harness_id}:{family}:{seed}: "
                        "oracle expected a cue but reference adapter did not"
                    )
                if oracle.cue_forbidden and actual_any_cue:
                    raise RuntimeError(
                        f"{harness_id}:{family}:{seed}: "
                        "oracle forbids a cue but reference adapter emitted one"
                    )

                rows.append(
                    (
                        f"scenario:{harness_id}:{family}:{seed}",
                        "scenario",
                        harness_id,
                        family,
                        threshold_for(None),
                        oracle.cue_expected,
                        family in {"repeated_issue", "asymmetry"},
                        tuple(observation.issue_evidence_by_rep),
                    )
                )

    subjects = external_subjects()
    append_benchmark_rows(
        rows,
        mode="natural",
        n=NATURAL_CASES,
        seed=20260919,
        subjects=subjects,
    )
    append_benchmark_rows(
        rows,
        mode="boundary",
        n=BOUNDARY_CASES,
        seed=20260920,
        subjects=subjects,
    )

    if len(rows) != 225:
        raise RuntimeError(f"expected 225 form/cue contracts, got {len(rows)}")

    output = StringIO()
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
            "kind",
            "exercise_id",
            "family",
            "threshold",
            "expected_any_cue",
            "expect_improved_response",
            "evidence",
        ]
    )

    for (
        case_key,
        kind,
        exercise_id,
        family,
        threshold,
        expected_any_cue,
        expect_improved_response,
        evidence,
    ) in rows:
        writer.writerow(
            [
                case_key,
                kind,
                exercise_id,
                family,
                f"{threshold:.9f}",
                str(expected_any_cue).lower(),
                str(expect_improved_response).lower(),
                ",".join(f"{x:.9f}" for x in evidence),
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
