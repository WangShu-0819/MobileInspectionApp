#!/usr/bin/env python3
"""Batch-extract visible part silhouettes from the DCIM reference photographs."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np


def _odd(value: int) -> int:
    return max(3, value | 1)


def _background_distance(lab: np.ndarray) -> np.ndarray:
    h, w = lab.shape[:2]
    band = max(4, min(h, w) // 40)
    border = np.concatenate(
        (lab[:band].reshape(-1, 3), lab[-band:].reshape(-1, 3),
         lab[:, :band].reshape(-1, 3), lab[:, -band:].reshape(-1, 3)), axis=0
    ).astype(np.float32)
    # Median is deliberately robust to a part crossing one or two image edges.
    background = np.median(border, axis=0)
    delta = lab.astype(np.float32) - background
    return np.sqrt(np.sum(delta * delta, axis=2))


def segment_part(image: np.ndarray, max_edge: int = 1280) -> tuple[np.ndarray, dict]:
    """Return a full-resolution binary visible-part mask and quality metadata."""
    if image is None or image.size == 0:
        raise ValueError("image is empty")
    source_h, source_w = image.shape[:2]
    scale = min(1.0, max_edge / max(source_w, source_h))
    work = cv2.resize(image, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    h, w = work.shape[:2]

    lab = cv2.cvtColor(work, cv2.COLOR_BGR2LAB)
    gray = cv2.cvtColor(work, cv2.COLOR_BGR2GRAY)
    distance = _background_distance(lab)
    distance = cv2.GaussianBlur(distance, (5, 5), 0)

    border_distance = np.concatenate((distance[0], distance[-1], distance[:, 0], distance[:, -1]))
    bg_limit = max(8.0, float(np.percentile(border_distance, 45)))
    fg_limit = max(bg_limit + 12.0, float(np.percentile(distance, 67)))
    dark_limit = float(np.percentile(gray, 48))

    gc_mask = np.full((h, w), cv2.GC_PR_BGD, np.uint8)
    likely_fg = (distance >= fg_limit) | ((gray <= dark_limit) & (distance >= bg_limit))
    gc_mask[likely_fg] = cv2.GC_PR_FGD

    seed_size = _odd(min(h, w) // 120)
    seed_kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (seed_size, seed_size))
    sure_fg = cv2.erode(likely_fg.astype(np.uint8), seed_kernel, iterations=1).astype(bool)
    gc_mask[sure_fg] = cv2.GC_FGD

    sure_bg = (distance < bg_limit * 0.72) & (gray > dark_limit)
    band = max(2, min(h, w) // 100)
    edge = np.zeros((h, w), bool)
    edge[:band] = edge[-band:] = True
    edge[:, :band] = edge[:, -band:] = True
    gc_mask[sure_bg & edge] = cv2.GC_BGD

    # GrabCut requires both classes. Fall back to the deterministic seeds if a
    # highly cropped frame gives it no safe background or foreground samples.
    has_bg = np.any(gc_mask == cv2.GC_BGD)
    has_fg = np.any(gc_mask == cv2.GC_FGD)
    if has_bg and has_fg:
        bg_model = np.zeros((1, 65), np.float64)
        fg_model = np.zeros((1, 65), np.float64)
        cv2.grabCut(work, gc_mask, None, bg_model, fg_model, 4, cv2.GC_INIT_WITH_MASK)
        binary = np.isin(gc_mask, (cv2.GC_FGD, cv2.GC_PR_FGD)).astype(np.uint8)
    else:
        binary = likely_fg.astype(np.uint8)

    close_size = _odd(min(h, w) // 80)
    open_size = _odd(min(h, w) // 220)
    binary = cv2.morphologyEx(binary, cv2.MORPH_CLOSE,
                              cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (close_size, close_size)))
    binary = cv2.morphologyEx(binary, cv2.MORPH_OPEN,
                              cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (open_size, open_size)))

    count, labels, stats, centroids = cv2.connectedComponentsWithStats(binary, 8)
    selected = np.zeros_like(binary)
    image_area = h * w
    cx, cy = w / 2.0, h / 2.0
    candidates: list[tuple[float, int]] = []
    for label in range(1, count):
        area = int(stats[label, cv2.CC_STAT_AREA])
        if area < image_area * 0.004:
            continue
        px, py = centroids[label]
        center_distance = np.hypot((px - cx) / w, (py - cy) / h)
        score = area / image_area - 0.18 * center_distance
        candidates.append((score, label))
    if candidates:
        # The capture contract is one centered inspection target. Keeping other
        # large components admits furniture, shadows and neighbouring brackets.
        _, best_label = max(candidates)
        selected[labels == best_label] = 255

    full_mask = cv2.resize(selected, (source_w, source_h), interpolation=cv2.INTER_NEAREST)
    coverage = float(np.count_nonzero(selected) / image_area)
    edge_pixels = np.concatenate((selected[0], selected[-1], selected[:, 0], selected[:, -1]))
    border_contact = float(np.count_nonzero(edge_pixels) / edge_pixels.size)
    return full_mask, {
        "status": "NEEDS_CONFIRMATION",
        "coverage": round(coverage, 4),
        "borderContact": round(border_contact, 4),
        "analysisScale": round(scale, 6),
    }


def contours_from_mask(mask: np.ndarray, epsilon_ratio: float = 0.0015) -> list[np.ndarray]:
    found, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    minimum_area = mask.shape[0] * mask.shape[1] * 0.002
    result = []
    for contour in sorted(found, key=cv2.contourArea, reverse=True):
        if cv2.contourArea(contour) < minimum_area:
            continue
        epsilon = max(1.0, cv2.arcLength(contour, True) * epsilon_ratio)
        result.append(cv2.approxPolyDP(contour, epsilon, True))
    return result


def contour_payload(contours: list[np.ndarray], width: int, height: int) -> list[list[dict]]:
    return [
        [{"x": round(float(x) / width, 6), "y": round(float(y) / height, 6)}
         for [[x, y]] in contour]
        for contour in contours
    ]


def process_file(source: Path, output: Path) -> dict:
    image = cv2.imread(str(source), cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError(f"cannot decode {source}")
    mask, quality = segment_part(image)
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
        "width": image.shape[1],
        "height": image.shape[0],
        **quality,
        "contourCount": len(contours),
        "contours": contour_payload(contours, image.shape[1], image.shape[0]),
    }
    (output / f"{source.stem}_contour.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return {k: v for k, v in payload.items() if k != "contours"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    sources = sorted(p for p in args.input.iterdir() if p.suffix.lower() in {".jpg", ".jpeg", ".png"})
    if not sources:
        raise SystemExit(f"No images found in {args.input}")
    manifest = []
    for index, source in enumerate(sources, 1):
        row = process_file(source, args.output)
        manifest.append(row)
        print(f"[{index:02d}/{len(sources)}] {source.name}: {row['status']} coverage={row['coverage']}")
    (args.output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )


if __name__ == "__main__":
    main()
