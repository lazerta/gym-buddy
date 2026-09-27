#!/usr/bin/env python3
from __future__ import annotations
import argparse
import csv
import gzip
import json
import math
from pathlib import Path

SOURCE_REPO = "david-dabert/workout-vision"
SOURCE_COMMIT = "285977080e2b7eec01d1fde0a03e976db25a5926"
SOURCE_BLOB = "5b569d7f16395668fcd6c2e514d1c520e9007818"
CLIP_ID = "lateral_raise_10_front_mufhhbun"
EXPECTED_REPS = 10
LANDMARKS = {
    "left_shoulder": 11,
    "right_shoulder": 12,
    "left_elbow": 13,
    "right_elbow": 14,
    "left_hip": 23,
    "right_hip": 24,
}

def pick(root, *names):
    for name in names:
        value = root.get(name)
        if isinstance(value, list):
            return value
    return None

def finite_or_blank(value):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return ""
    return repr(number) if math.isfinite(number) else ""

def timestamp_us(value, last):
    number = float(value)
    seconds = number / 1000.0 if number > 10_000 else number
    return max(int(round(seconds * 1_000_000.0)), last + 1)

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    with gzip.open(args.input, "rt", encoding="utf-8") as handle:
        data = json.load(handle)

    world = pick(data, "worldLandmarks", "world_landmarks")
    image = pick(data, "imageLandmarks", "image_landmarks")
    timestamps = pick(data, "timestamps", "timestamp_seconds")
    if world is None or timestamps is None or len(world) != len(timestamps):
        raise ValueError("fixture must contain aligned worldLandmarks and timestamps arrays")
    if image is not None and len(image) != len(world):
        raise ValueError("imageLandmarks length must match worldLandmarks when present")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", newline="", encoding="utf-8") as handle:
        handle.write("# schema_version=1\n")
        handle.write(f"# source_repo={SOURCE_REPO}\n")
        handle.write(f"# source_commit={SOURCE_COMMIT}\n")
        handle.write(f"# source_blob={SOURCE_BLOB}\n")
        handle.write(f"# clip_id={CLIP_ID}\n")
        handle.write(f"# expected_reps={EXPECTED_REPS}\n")
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        header = ["frame", "timestamp_us"]
        for name in LANDMARKS:
            header += [
                f"{name}_x",
                f"{name}_y",
                f"{name}_z",
                f"{name}_visibility",
                f"{name}_presence",
            ]
        writer.writerow(header)

        last = -1
        for frame_index, (raw_timestamp, world_frame) in enumerate(zip(timestamps, world)):
            timestamp = timestamp_us(raw_timestamp, last)
            last = timestamp
            image_frame = image[frame_index] if image is not None else None
            row = [frame_index, timestamp]
            for _, index in LANDMARKS.items():
                world_point = (
                    world_frame[index]
                    if isinstance(world_frame, list)
                    and len(world_frame) > index
                    and isinstance(world_frame[index], dict)
                    else None
                )
                image_point = (
                    image_frame[index]
                    if isinstance(image_frame, list)
                    and len(image_frame) > index
                    and isinstance(image_frame[index], dict)
                    else None
                )
                coordinates = [
                    finite_or_blank(world_point.get(axis) if world_point else None)
                    for axis in ("x", "y", "z")
                ]
                visibility = (
                    finite_or_blank((world_point or {}).get("visibility"))
                    or finite_or_blank((image_point or {}).get("visibility"))
                )
                presence = (
                    finite_or_blank((world_point or {}).get("presence"))
                    or finite_or_blank((image_point or {}).get("presence"))
                )
                row += coordinates + [visibility, presence]
            writer.writerow(row)

    print(
        f"WORKOUTVISION_LATERAL_RAISE_CONTRACT_PASS "
        f"frames={len(world)} expected_reps={EXPECTED_REPS}"
    )
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
