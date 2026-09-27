#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np


def resize(frame: np.ndarray, max_width: int = 260) -> np.ndarray:
    h, w = frame.shape[:2]
    if w <= max_width:
        return frame
    scale = max_width / w
    return cv2.resize(frame, (max_width, max(1, int(round(h * scale)))), interpolation=cv2.INTER_AREA)


def make_contact_sheet(session: Path, output: Path, samples: int = 30) -> None:
    manifest = json.loads((session / "manifest.json").read_text(encoding="utf-8"))
    frames = manifest["frames"]
    if not frames:
        raise ValueError(f"empty session: {session}")
    take = min(samples, len(frames))
    indices = np.linspace(0, len(frames) - 1, num=take, dtype=int).tolist()
    thumbs = []
    for idx in indices:
        meta = frames[idx]
        image = cv2.imread(str(session / meta["image_path"]))
        if image is None:
            raise FileNotFoundError(session / meta["image_path"])
        image = resize(image)
        cv2.putText(image, f"f={meta['frame_id']} t={meta['timestamp_us']/1e6:.1f}s", (6, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 255), 2, cv2.LINE_AA)
        cv2.putText(image, f"f={meta['frame_id']} t={meta['timestamp_us']/1e6:.1f}s", (6, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 0, 0), 1, cv2.LINE_AA)
        thumbs.append(image)
    cols = 5
    rows = (len(thumbs) + cols - 1) // cols
    cell_h = max(x.shape[0] for x in thumbs)
    cell_w = max(x.shape[1] for x in thumbs)
    sheet = np.zeros((rows * cell_h, cols * cell_w, 3), dtype=np.uint8)
    for i, image in enumerate(thumbs):
        y, x = divmod(i, cols)
        sheet[y * cell_h:y * cell_h + image.shape[0], x * cell_w:x * cell_w + image.shape[1]] = image
    output.parent.mkdir(parents=True, exist_ok=True)
    if not cv2.imwrite(str(output), sheet, [int(cv2.IMWRITE_JPEG_QUALITY), 88]):
        raise RuntimeError(f"cannot write {output}")


def make_preview(session: Path, output: Path, max_width: int = 360) -> None:
    manifest = json.loads((session / "manifest.json").read_text(encoding="utf-8"))
    frames = manifest["frames"]
    first = cv2.imread(str(session / frames[0]["image_path"]))
    if first is None:
        raise FileNotFoundError(session / frames[0]["image_path"])
    first = resize(first, max_width)
    h, w = first.shape[:2]
    writer = cv2.VideoWriter(str(output), cv2.VideoWriter_fourcc(*"mp4v"), float(manifest["fps"]), (w, h))
    if not writer.isOpened():
        raise RuntimeError(f"cannot create {output}")
    try:
        for meta in frames:
            image = cv2.imread(str(session / meta["image_path"]))
            if image is None:
                raise FileNotFoundError(session / meta["image_path"])
            image = resize(image, max_width)
            if image.shape[:2] != (h, w):
                image = cv2.resize(image, (w, h), interpolation=cv2.INTER_AREA)
            writer.write(image)
    finally:
        writer.release()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sessions-root", type=Path, required=True)
    ap.add_argument("--output-root", type=Path, required=True)
    args = ap.parse_args()
    for session in sorted(args.sessions_root.glob("*-baseline")):
        make_contact_sheet(session, args.output_root / f"{session.name}-contact.jpg")
        make_preview(session, args.output_root / f"{session.name}-preview.mp4")
        oracle = json.loads((session / "oracle.json").read_text(encoding="utf-8"))
        (args.output_root / f"{session.name}-oracle.json").write_text(json.dumps(oracle, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
