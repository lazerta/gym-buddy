#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import gzip
import io
import json
from pathlib import Path

UPSTREAM_REPO = "david-dabert/workout-vision"
UPSTREAM_COMMIT = "285977080e2b7eec01d1fde0a03e976db25a5926"
UPSTREAM_BLOB = "5b569d7f16395668fcd6c2e514d1c520e9007818"
CLIP = "lateral_raise_10_front_mufhhbun.json.gz"
EXPECTED_REPS = 10
RELATIVE = Path("test/real-phone/landmarks") / CLIP
LANDMARKS = (
    (11, "left_shoulder"),
    (12, "right_shoulder"),
    (13, "left_elbow"),
    (14, "right_elbow"),
    (15, "left_wrist"),
    (16, "right_wrist"),
    (23, "left_hip"),
    (24, "right_hip"),
)


def value(point: dict | None, key: str, default: str = "NA") -> str:
    if point is None:
        return default
    raw = point.get(key)
    return default if raw is None else f"{float(raw):.12g}"


def render(source: Path) -> str:
    with gzip.open(source, "rt", encoding="utf-8") as handle:
        data = json.load(handle)

    image = data["imageLandmarks"]
    world = data["worldLandmarks"]
    timestamps = data["timestamps"]
    if not (len(image) == len(world) == len(timestamps)):
        raise ValueError("fixture arrays differ in length")

    out = io.StringIO()
    out.write("# schema_version=1\n")
    out.write(f"# upstream_repo={UPSTREAM_REPO}\n")
    out.write(f"# upstream_commit={UPSTREAM_COMMIT}\n")
    out.write(f"# upstream_blob={UPSTREAM_BLOB}\n")
    out.write(f"# clip={CLIP}\n")
    out.write(f"# expected_reps={EXPECTED_REPS}\n")
    out.write(f"# sample_count={len(timestamps)}\n")
    out.write("# evidence_tier=REAL_LANDMARK_REPLAY\n")

    writer = csv.writer(out, delimiter="\t", lineterminator="\n")
    header = ["timestamp_us", "valid"]
    for _, name in LANDMARKS:
        header += [
            f"{name}_ix",
            f"{name}_iy",
            f"{name}_iz",
            f"{name}_iv",
            f"{name}_wx",
            f"{name}_wy",
            f"{name}_wz",
            f"{name}_wv",
        ]
    writer.writerow(header)

    previous = -1
    for timestamp, image_frame, world_frame in zip(timestamps, image, world):
        timestamp_us = round(float(timestamp) * 1_000_000)
        if timestamp_us <= previous:
            raise ValueError(
                f"non-monotonic timestamp {timestamp_us} after {previous}"
            )
        previous = timestamp_us

        valid = (
            image_frame is not None
            and world_frame is not None
            and len(image_frame) > 24
            and len(world_frame) > 24
        )
        row: list[object] = [timestamp_us, str(valid).lower()]
        for index, _ in LANDMARKS:
            image_point = image_frame[index] if valid else None
            world_point = world_frame[index] if valid else None
            image_visibility = value(image_point, "visibility")
            world_visibility = value(
                world_point,
                "visibility",
                image_visibility,
            )
            row += [
                value(image_point, "x"),
                value(image_point, "y"),
                value(image_point, "z"),
                image_visibility,
                value(world_point, "x"),
                value(world_point, "y"),
                value(world_point, "z"),
                world_visibility,
            ]
        writer.writerow(row)
    return out.getvalue()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workout-vision-root", type=Path)
    parser.add_argument("--source-file", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    source = args.source_file
    if source is None and args.workout_vision_root is not None:
        source = args.workout_vision_root / RELATIVE
    if source is None:
        raise ValueError("provide --workout-vision-root or --source-file")
    if not source.exists():
        raise FileNotFoundError(source)

    generated = render(source)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(generated, encoding="utf-8")
    print(f"wrote {args.output} source={source}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
