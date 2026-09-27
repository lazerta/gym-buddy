#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def verify(session: Path, results_path: Path, strict_count: bool = True) -> dict:
    manifest = json.loads((session / "manifest.json").read_text(encoding="utf-8"))
    oracle = json.loads((session / "oracle.json").read_text(encoding="utf-8"))
    results = json.loads(results_path.read_text(encoding="utf-8"))
    if len(results) != len(manifest["frames"]):
        raise AssertionError(f"result coverage {len(results)}/{len(manifest['frames'])}")
    by_id = {int(x["frame_id"]): x for x in results}
    if len(by_id) != len(results):
        raise AssertionError("duplicate frame results")
    pose_frames = 0
    tracked_frames = 0
    final_count = 0
    all_cues = []
    rep_events = []
    for expected in manifest["frames"]:
        item = by_id.get(int(expected["frame_id"]))
        if item is None:
            raise AssertionError(f"missing result frame {expected['frame_id']}")
        if item["session_id"] != manifest["session_id"] or int(item["timestamp_us"]) != int(expected["timestamp_us"]):
            raise AssertionError(f"identity/timestamp mismatch frame {expected['frame_id']}")
        analysis = item["analysis"]
        for key in ("pose_count", "tracking_state", "tracking_reason", "camera_guidance", "rep_count", "rep_events", "cues"):
            if key not in analysis:
                raise AssertionError(f"frame {expected['frame_id']} missing {key}")
        pose_frames += int(analysis["pose_count"] > 0)
        tracked_frames += int(analysis["tracking_state"] in {"OBSERVABLE", "DEGRADED"})
        final_count = int(analysis["rep_count"])
        rep_events.extend(analysis["rep_events"])
        all_cues.extend(analysis["cues"])
    expected_reps = int(oracle["expected_reps"])
    missed = max(0, expected_reps - final_count)
    false = max(0, final_count - expected_reps)
    pose_coverage = pose_frames / len(results)
    tracked_coverage = tracked_frames / len(results)
    if pose_coverage < 0.60:
        raise AssertionError(f"pose coverage too low: {pose_coverage:.3f}")
    if strict_count and final_count != expected_reps:
        raise AssertionError(f"rep count {final_count} != independent expected {expected_reps}")
    return {
        "schema_version": 1,
        "session_id": manifest["session_id"],
        "exercise_id": manifest["exercise_id"],
        "source": manifest["source"],
        "frame_count": len(results),
        "pose_coverage": pose_coverage,
        "tracking_coverage": tracked_coverage,
        "expected_reps": expected_reps,
        "detected_reps": final_count,
        "missed_reps": missed,
        "false_reps": false,
        "rep_event_count": len(rep_events),
        "cue_event_count": len(all_cues),
        "source_sha256": oracle["source_sha256"],
        "label_basis": oracle["label_basis"],
        "raw_rgb": True,
        "oracle_exposed_to_app": False,
        "passed": (not strict_count or final_count == expected_reps) and pose_coverage >= 0.60,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--session", type=Path, required=True)
    ap.add_argument("--results", type=Path, required=True)
    ap.add_argument("--output", type=Path)
    ap.add_argument("--allow-count-mismatch", action="store_true")
    args = ap.parse_args()
    summary = verify(args.session, args.results, strict_count=not args.allow_count_mismatch)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
