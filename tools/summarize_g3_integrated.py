#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path

RELEASE = {"incline_db_press", "smith_squat", "lateral_raise"}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--commercial-gym", type=Path, required=True)
    ap.add_argument("--g2-dir", type=Path, required=True)
    ap.add_argument("--g3-dir", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    args = ap.parse_args()
    commercial = json.loads(args.commercial_gym.read_text(encoding="utf-8"))
    if commercial.get("static_failures") != 0 or commercial.get("temporal_failures") != 0:
        raise AssertionError("G1 commercial-gym gate not green")
    g2 = [json.loads(p.read_text(encoding="utf-8")) for p in sorted(args.g2_dir.glob("*.json"))]
    g3 = [json.loads(p.read_text(encoding="utf-8")) for p in sorted(args.g3_dir.glob("*.json"))]
    for name, rows in (("G2", g2), ("G3", g3)):
        exercises = {x["exercise_id"] for x in rows if x.get("passed")}
        if exercises != RELEASE:
            raise AssertionError(f"{name} release coverage {sorted(exercises)} != {sorted(RELEASE)}")
    summary = {
        "schema_version": 1,
        "gate": "G3_INTEGRATED_REALITY_STRESS",
        "passed": True,
        "g1": commercial,
        "g2_real_rgb_sessions": g2,
        "g3_stressed_real_rgb_sessions": g3,
        "physical_device_required": False,
        "physical_smoke_optional": True,
        "oracle_exposed_to_production": False,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print("G3_INTEGRATED_REALITY_STRESS_PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
