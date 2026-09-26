#!/usr/bin/env python3
"""Fail closed unless every required test actually executes and passes.

A successful Gradle/adb exit or an empty instrumentation report is not evidence
of test execution. Read AndroidJUnitRunner's per-test status protocol, then emit
an auditable JSON/JUnit report; never infer PASS from test discovery alone.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

COMPOSE = "com.gymbuddy.app.Step3ComposeRepairTest"
ACTIVITY = "com.gymbuddy.app.Step3ActivityRecreationTest"
EXPECTED = frozenset({
    (COMPOSE, "favoriteEquipmentOutsideDayAndQueryOpensOneVisibleEditor"),
    (COMPOSE, "recentEquipmentOutsideDayAndQueryOpensOneVisibleEditor"),
    (COMPOSE, "failedAndPendingRestSavesDisableAdvancingButPermitRetry"),
    (COMPOSE, "returnedAnalysisDraftSurvivesFailureAndCanBeRetriedFromSummary"),
    (ACTIVITY, "mainActivityRecreationRetainsRestPlanEquipmentAndMonotonicAnchor"),
})


def verify(text: str) -> list[tuple[str, str]]:
    status: dict[str, str] = {}
    started: set[tuple[str, str]] = set()
    finished: set[tuple[str, str]] = set()
    reported_counts: list[int] = []
    final_codes: list[int] = []
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if separator:
                status[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            code = int(line.partition(":")[2].strip())
            test = (status.get("class", ""), status.get("test", ""))
            if test not in EXPECTED:
                raise ValueError(f"Unexpected or unidentified test status: {test}, code={code}, {status}")
            if "numtests" in status:
                reported_counts.append(int(status["numtests"]))
            if code == 1:
                if test in started:
                    raise ValueError(f"Duplicate start: {test}")
                started.add(test)
            elif code == 0:
                if test not in started or test in finished:
                    raise ValueError(f"Completion without unique start: {test}")
                finished.add(test)
            else:
                # Failure, error, skipped and assumption-failure are not PASS.
                raise ValueError(f"Test did not pass: {test}, code={code}, {status}")
            status = {}
        elif line.startswith("INSTRUMENTATION_CODE:"):
            final_codes.append(int(line.partition(":")[2].strip()))
    if started != EXPECTED or finished != EXPECTED:
        raise ValueError(f"Required tests not executed: missing starts={sorted(EXPECTED-started)}, "
                         f"missing passes={sorted(EXPECTED-finished)}")
    if not reported_counts or any(n != len(EXPECTED) for n in reported_counts):
        raise ValueError(f"Incorrect runner test counts: {reported_counts}")
    if final_codes != [-1]:
        raise ValueError(f"Instrumentation did not complete normally: {final_codes}")
    if not re.search(r"(?m)^OK \(5 tests\)\s*$", text.replace("\r", "")):
        raise ValueError("Missing five-test JUnit completion summary")
    return sorted(finished)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    raw = args.log.read_bytes()
    report = {"schema_version": 1, "runner_trace_sha256": hashlib.sha256(raw).hexdigest(),
              "expected_tests": len(EXPECTED), "passed": False, "tests": []}
    try:
        tests = verify(raw.decode("utf-8", errors="replace"))
        report.update(passed=True, tests=[{"class": cls, "name": name, "passed": True}
                                         for cls, name in tests])
        suite = ET.Element("testsuite", name="Step3ActualInstrumentation", tests=str(len(tests)),
                           failures="0", errors="0", skipped="0")
        for cls, name in tests:
            ET.SubElement(suite, "testcase", classname=cls, name=name)
        ET.ElementTree(suite).write(args.output / "TEST-step3-instrumentation.xml", encoding="utf-8",
                                    xml_declaration=True)
    except (ValueError, OSError) as error:
        report["error"] = str(error)
    (args.output / "instrumentation-verification.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    if report["passed"]:
        print("STEP3_ACTUAL_INSTRUMENTATION_5_TESTS_PASS")
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
