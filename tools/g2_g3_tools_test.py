#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parent.parent


def load(name: str):
    path = ROOT / "tools" / f"{name}.py"
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


prep = load("prepare_g2_g3_sessions")
verify = load("verify_g2_g3_results")
review = load("build_g2_g3_review_artifacts")


def make_video(path: Path, frames: int = 60, fps: int = 10) -> None:
    writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), fps, (160, 120))
    if not writer.isOpened():
        raise RuntimeError("cannot create local test video")
    for i in range(frames):
        image = np.full((120, 160, 3), 80, np.uint8)
        cv2.circle(image, (30 + (i % 80), 60), 12, (220, 220, 220), -1)
        writer.write(image)
    writer.release()


def fake_case():
    return prep.Case(
        "case", "lateral_raise", "Dumbbell Lateral Raise",
        "https://example.invalid/v", 3, "independent", "front", "two_dumbbells", False,
    )


class G2G3ToolsTest(unittest.TestCase):
    def test_registry_covers_exact_release_set(self):
        fps, cases = prep.load_registry(ROOT / "tools" / "g2_g3_video_registry.json")
        self.assertEqual(fps, 5.0)
        self.assertEqual(
            {c.exercise_id for c in cases},
            {"incline_db_press", "smith_squat", "lateral_raise"},
        )

    def test_session_generation_stress_and_private_oracle(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "x.mp4"
            make_video(source)
            baseline = prep.make_session(fake_case(), source, root / "sessions", "baseline", 5)
            stress = prep.make_session(fake_case(), source, root / "sessions", "stress", 5)
            b = json.loads((baseline / "manifest.json").read_text())
            s = json.loads((stress / "manifest.json").read_text())
            self.assertGreaterEqual(len(b["frames"]), 20)
            self.assertGreater(len(s["frames"]), len(b["frames"]) * 0.85)
            self.assertLess(len(s["frames"]), len(b["frames"]))
            self.assertFalse(json.loads((baseline / "oracle.json").read_text())["oracle_exposed_to_app"])
            timestamps = [f["timestamp_us"] for f in s["frames"]]
            self.assertEqual(timestamps, sorted(set(timestamps)))

    def test_verifier_rejects_incomplete_and_wrong_count(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "x.mp4"
            make_video(source)
            session = prep.make_session(fake_case(), source, root / "sessions", "baseline", 5)
            manifest = json.loads((session / "manifest.json").read_text())

            def row(frame, count):
                return {
                    "session_id": manifest["session_id"],
                    "frame_id": frame["frame_id"],
                    "timestamp_us": frame["timestamp_us"],
                    "analysis": {
                        "pose_count": 1,
                        "tracking_state": "OBSERVABLE",
                        "tracking_reason": "NONE",
                        "camera_guidance": "CAMERA_READY",
                        "rep_count": count,
                        "rep_events": [],
                        "cues": [],
                    },
                }

            incomplete = root / "incomplete.json"
            incomplete.write_text(json.dumps([row(manifest["frames"][0], 0)]))
            with self.assertRaisesRegex(AssertionError, "coverage"):
                verify.verify(session, incomplete)
            wrong = root / "wrong.json"
            wrong.write_text(json.dumps([row(f, 2) for f in manifest["frames"]]))
            with self.assertRaisesRegex(AssertionError, "rep count"):
                verify.verify(session, wrong)
            okay = root / "okay.json"
            okay.write_text(json.dumps([row(f, 3) for f in manifest["frames"]]))
            self.assertTrue(verify.verify(session, okay)["passed"])

    def test_review_artifacts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "x.mp4"
            make_video(source)
            session = prep.make_session(fake_case(), source, root / "sessions", "baseline", 5)
            sheet = root / "contact.jpg"
            preview = root / "preview.mp4"
            review.make_contact_sheet(session, sheet, samples=12)
            review.make_preview(session, preview)
            self.assertGreater(sheet.stat().st_size, 1000)
            self.assertGreater(preview.stat().st_size, 1000)


if __name__ == "__main__":
    unittest.main(verbosity=2)
