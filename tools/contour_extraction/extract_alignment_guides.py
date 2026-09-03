#!/usr/bin/env python3
"""Build sparse, human-readable alignment guides from reliable template masks."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

from extract_contours_traditional import segment_assembly


def _normalized_polyline(contour: np.ndarray, width: int, height: int) -> list[dict]:
    return [
        {"x": round(float(x) / width, 6), "y": round(float(y) / height, 6)}
        for [[x, y]] in contour
    ]


def _central_target_mask(image: np.ndarray, part_mask: np.ndarray) -> np.ndarray | None:
    """Prefer a closed physical part near frame center over a merged assembly."""
    h, w = part_mask.shape
    gray = cv2.GaussianBlur(cv2.cvtColor(image, cv2.COLOR_BGR2GRAY), (7, 7), 0)
    edges = cv2.Canny(gray, 45, 140)
    edges = cv2.bitwise_and(edges, cv2.dilate(part_mask, np.ones((5, 5), np.uint8)))
    close_size = max(3, (min(h, w) // 400) | 1)
    edges = cv2.morphologyEx(
        edges, cv2.MORPH_CLOSE,
        cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (close_size, close_size)),
    )
    contours, _ = cv2.findContours(edges, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    image_area = h * w
    mask_area = cv2.countNonZero(part_mask)
    candidates = []
    for contour in contours:
        area = cv2.contourArea(contour)
        if not max(image_area * 0.018, mask_area * 0.15) <= area <= mask_area * 0.85:
            continue
        x, y, width, height = cv2.boundingRect(contour)
        if min(width, height) < min(h, w) * 0.08:
            continue
        candidate = np.zeros_like(part_mask)
        cv2.drawContours(candidate, [contour], -1, 255, cv2.FILLED)
        overlap = np.count_nonzero((candidate > 0) & (part_mask > 0)) / max(1, cv2.countNonZero(candidate))
        if overlap < 0.82:
            continue
        moments = cv2.moments(contour)
        if moments["m00"] == 0:
            continue
        center_x = moments["m10"] / moments["m00"]
        center_y = moments["m01"] / moments["m00"]
        center_distance = np.hypot((center_x - w / 2) / w, (center_y - h / 2) / h)
        contains_center = cv2.pointPolygonTest(contour, (w / 2, h / 2), False) >= 0
        score = (1.0 if contains_center else 0.0) - center_distance + area / image_area * 0.2
        hull_area = cv2.contourArea(cv2.convexHull(contour))
        solidity = area / max(1.0, hull_area)
        # Large, deeply concave regions usually bridge adjacent parts through
        # an occlusion or shadow.  A small bracket may legitimately be
        # concave, so apply this gate only to substantial image regions.
        if area >= image_area * 0.10 and solidity < 0.78:
            continue
        candidates.append((score, candidate))
    if not candidates:
        fallback_contours, _ = cv2.findContours(
            part_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        if not fallback_contours:
            return None
        fallback = max(fallback_contours, key=cv2.contourArea)
        area = cv2.contourArea(fallback)
        hull_area = cv2.contourArea(cv2.convexHull(fallback))
        solidity = area / max(1.0, hull_area)
        if area >= image_area * 0.10 and solidity < 0.78:
            return None
        return part_mask
    return max(candidates, key=lambda item: item[0])[1]


def build_guide_lines(image: np.ndarray, part_mask: np.ndarray) -> tuple[np.ndarray, list[np.ndarray]]:
    """Return one clean silhouette plus a few geometric alignment anchors."""
    target_mask = _central_target_mask(image, part_mask)
    if target_mask is None:
        return np.zeros_like(part_mask), []
    h, w = target_mask.shape
    minimum_edge = min(h, w)
    thickness = max(2, round(minimum_edge / 900))
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    gray = cv2.GaussianBlur(gray, (7, 7), 0)
    median = float(np.median(gray[target_mask > 0])) if np.any(target_mask) else 0.0
    lower = max(25, round(median * 0.55))
    upper = min(220, max(lower + 30, round(median * 1.35)))
    edges = cv2.Canny(gray, lower, upper)

    erosion_size = max(3, (minimum_edge // 350) | 1)
    eroded = cv2.erode(
        target_mask,
        cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (erosion_size, erosion_size)),
    )
    internal_edges = cv2.bitwise_and(edges, eroded)
    guide = np.zeros_like(part_mask)
    selected: list[np.ndarray] = []
    outer_line: np.ndarray | None = None

    smooth_size = max(5, (minimum_edge // 180) | 1)
    smooth_mask = cv2.GaussianBlur(target_mask, (smooth_size, smooth_size), 0)
    _, smooth_mask = cv2.threshold(smooth_mask, 127, 255, cv2.THRESH_BINARY)
    outer_contours, _ = cv2.findContours(smooth_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if outer_contours:
        outer = max(outer_contours, key=cv2.contourArea)
        outer_perimeter = cv2.arcLength(outer, True)
        outer = cv2.approxPolyDP(outer, max(1.0, outer_perimeter * 0.008), True)
        outer_line = outer
        selected.append(np.concatenate((outer, outer[:1])))

    # Closed round or elongated features are the most useful human anchors:
    # holes, bolt heads and stamped slots remain recognizable under rotation.
    contours, _ = cv2.findContours(internal_edges, cv2.RETR_LIST, cv2.CHAIN_APPROX_NONE)
    anchor_candidates = []
    image_area = h * w
    support_size = max(3, (minimum_edge // 250) | 1)
    support_mask = cv2.dilate(
        target_mask,
        cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (support_size, support_size)),
    )
    for contour in contours:
        if len(contour) < 5:
            continue
        area = cv2.contourArea(contour)
        perimeter = cv2.arcLength(contour, True)
        if not image_area * 0.00004 <= area <= image_area * 0.018 or perimeter <= 0:
            continue
        circularity = 4 * np.pi * area / (perimeter * perimeter)
        if circularity < 0.42:
            continue
        (center_x, center_y), (axis_a, axis_b), angle = cv2.fitEllipse(contour)
        minor_axis, major_axis = sorted((axis_a, axis_b))
        if (minor_axis < minimum_edge * 0.012 or major_axis > minimum_edge * 0.18 or
                major_axis / max(1.0, minor_axis) > 3.5):
            continue
        cx, cy = round(center_x), round(center_y)
        if not (0 <= cx < w and 0 <= cy < h):
            continue
        ring = cv2.ellipse2Poly(
            (cx, cy),
            (max(1, round(major_axis / 2)), max(1, round(minor_axis / 2))),
            round(angle), 0, 360, 8,
        )
        ring_x = np.clip(ring[:, 0], 0, w - 1)
        ring_y = np.clip(ring[:, 1], 0, h - 1)
        if np.count_nonzero(support_mask[ring_y, ring_x]) / len(ring) < 0.70:
            continue
        center_distance = np.hypot((center_x - w / 2) / w, (center_y - h / 2) / h)
        score = np.sqrt(area) * circularity * (1.0 - min(0.7, center_distance))
        anchor_candidates.append((score, center_x, center_y, major_axis, minor_axis, angle))

    anchors = []
    for _, center_x, center_y, major_axis, minor_axis, angle in sorted(anchor_candidates, reverse=True):
        duplicate = any(
            np.hypot(center_x - old_x, center_y - old_y) < max(major_axis, old_axis) * 0.45
            for old_x, old_y, old_axis in anchors
        )
        if duplicate:
            continue
        points = cv2.ellipse2Poly(
            (round(center_x), round(center_y)),
            (max(1, round(major_axis / 2)), max(1, round(minor_axis / 2))),
            round(angle), 0, 360, 8,
        ).reshape(-1, 1, 2)
        cv2.polylines(guide, [points], True, 255, thickness)
        selected.append(np.concatenate((points, points[:1])))
        anchors.append((center_x, center_y, major_axis))
        if len(anchors) >= 4:
            break

    # A few long internal edges communicate rotation and perspective without
    # turning every reflection or carpet fibre into a guide.
    raw_lines = cv2.HoughLinesP(
        internal_edges, 1, np.pi / 180,
        threshold=max(30, minimum_edge // 45),
        minLineLength=round(minimum_edge * 0.11),
        maxLineGap=round(minimum_edge * 0.015),
    )
    chosen_lines = []
    if raw_lines is not None:
        ranked_lines = []
        for raw_line in raw_lines:
            x1, y1, x2, y2 = raw_line.reshape(-1)[:4]
            length = np.hypot(x2 - x1, y2 - y1)
            mid_x, mid_y = (x1 + x2) / 2, (y1 + y2) / 2
            center_distance = np.hypot((mid_x - w / 2) / w, (mid_y - h / 2) / h)
            angle = np.arctan2(y2 - y1, x2 - x1) % np.pi
            ranked_lines.append((length * (1.0 - min(0.7, center_distance)),
                                 x1, y1, x2, y2, mid_x, mid_y, angle))
        for _, x1, y1, x2, y2, mid_x, mid_y, angle in sorted(ranked_lines, reverse=True):
            duplicate = any(
                min(abs(angle - old_angle), np.pi - abs(angle - old_angle)) < np.deg2rad(8)
                and np.hypot(mid_x - old_x, mid_y - old_y) < minimum_edge * 0.08
                for old_x, old_y, old_angle in chosen_lines
            )
            if duplicate:
                continue
            line = np.array([[[x1, y1]], [[x2, y2]]], np.int32)
            cv2.polylines(guide, [line], False, 255, thickness)
            selected.append(line)
            chosen_lines.append((mid_x, mid_y, angle))
            if len(chosen_lines) >= 3:
                break

    guide = cv2.bitwise_and(guide, target_mask)
    if outer_line is not None:
        cv2.polylines(guide, [outer_line], True, 255, thickness)
    return guide, selected


def process(source: Path, output: Path) -> dict:
    image = cv2.imread(str(source), cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError(f"cannot decode {source}")
    part_mask, quality = segment_assembly(image)
    if quality["status"] == "NEEDS_RECAPTURE":
        guide = np.zeros(part_mask.shape, np.uint8)
        lines: list[np.ndarray] = []
    else:
        guide, lines = build_guide_lines(image, part_mask)

    overlay = image.copy()
    overlay[guide > 0] = (0, 255, 255)
    transparent = np.zeros((*guide.shape, 4), np.uint8)
    transparent[guide > 0] = (255, 255, 255, 210)
    output.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(output / f"{source.stem}_guide.png"), transparent)
    cv2.imwrite(str(output / f"{source.stem}_guide_overlay.jpg"), overlay,
                [cv2.IMWRITE_JPEG_QUALITY, 90])
    payload = {
        "source": source.name,
        "width": image.shape[1],
        "height": image.shape[0],
        **quality,
        "guideStatus": "READY" if lines else "RECAPTURE_REQUIRED",
        "guideLineCount": len(lines),
        "guideLines": [_normalized_polyline(line, image.shape[1], image.shape[0]) for line in lines],
    }
    (output / f"{source.stem}_guide.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return {key: value for key, value in payload.items() if key != "guideLines"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("names", nargs="*")
    args = parser.parse_args()
    sources = [args.input / name for name in args.names] if args.names else sorted(args.input.glob("*.jpg"))
    manifest = [process(source, args.output) for source in sources]
    (args.output / "guide_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    for row in manifest:
        print(f"{row['source']}: {row['guideStatus']} lines={row['guideLineCount']}")


if __name__ == "__main__":
    main()
