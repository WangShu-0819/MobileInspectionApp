#!/usr/bin/env python3
"""Model-based whole visible assembly segmentation for reference photographs."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

import cv2
import numpy as np
from rembg import new_session, remove

from extract_contours import contour_payload, contours_from_mask


def clean_mask(alpha: np.ndarray) -> np.ndarray:
    mask = np.where(alpha >= 96, 255, 0).astype(np.uint8)
    h, w = mask.shape
    close_size = max(5, (min(h, w) // 180) | 1)
    mask = cv2.morphologyEx(
        mask,
        cv2.MORPH_CLOSE,
        cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (close_size, close_size)),
    )
    count, labels, stats, centroids = cv2.connectedComponentsWithStats(mask, 8)
    if count <= 1:
        return mask
    cx, cy = w / 2.0, h / 2.0
    scored = []
    for label in range(1, count):
        area = stats[label, cv2.CC_STAT_AREA]
        if area < h * w * 0.002:
            continue
        px, py = centroids[label]
        center_distance = np.hypot((px - cx) / w, (py - cy) / h)
        scored.append((area / (h * w) - center_distance * 0.12, label))
    if not scored:
        return np.zeros_like(mask)
    result = np.zeros_like(mask)
    result[labels == max(scored)[1]] = 255
    return result


def process(source: Path, output: Path, session) -> dict:
    data = source.read_bytes()
    result = remove(data, session=session, only_mask=True)
    alpha = cv2.imdecode(np.frombuffer(result, np.uint8), cv2.IMREAD_GRAYSCALE)
    image = cv2.imread(str(source), cv2.IMREAD_COLOR)
    if alpha is None or image is None:
        raise ValueError(f"Cannot decode {source}")
    mask = clean_mask(alpha)
    contours = contours_from_mask(mask)
    overlay = image.copy()
    cv2.drawContours(overlay, contours, -1, (0, 255, 255), max(3, image.shape[1] // 700))
    dimmed = cv2.addWeighted(image, 0.35, np.zeros_like(image), 0.65, 0)
    preview = np.where((mask > 0)[..., None], overlay, dimmed)
    output.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(output / f"{source.stem}_mask.png"), mask)
    cv2.imwrite(str(output / f"{source.stem}_overlay.jpg"), preview, [cv2.IMWRITE_JPEG_QUALITY, 90])
    payload = {
        "source": source.name,
        "status": "NEEDS_CONFIRMATION",
        "width": image.shape[1],
        "height": image.shape[0],
        "coverage": round(float(np.count_nonzero(mask) / mask.size), 4),
        "contours": contour_payload(contours, image.shape[1], image.shape[0]),
    }
    (output / f"{source.stem}_contour.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return {key: value for key, value in payload.items() if key != "contours"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("names", nargs="*")
    args = parser.parse_args()
    model_home = Path(__file__).with_name("models")
    model_home.mkdir(exist_ok=True)
    os.environ["U2NET_HOME"] = str(model_home.resolve())
    session = new_session("u2net")
    sources = [args.input / name for name in args.names] if args.names else sorted(args.input.glob("*.jpg"))
    manifest = [process(source, args.output, session) for source in sources]
    (args.output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )


if __name__ == "__main__":
    main()
