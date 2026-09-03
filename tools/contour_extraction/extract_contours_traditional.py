#!/usr/bin/env python3
"""Traditional segmentation for a centered black assembly on an unknown background."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

from extract_contours import contour_payload, contours_from_mask


def _outer_envelope(mask: np.ndarray) -> np.ndarray:
    """Smooth one foreground component without filling its background gaps."""
    close_size = max(5, (min(mask.shape) // 100) | 1)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (close_size, close_size))
    closed = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel)
    smooth_size = max(5, (min(mask.shape) // 80) | 1)
    smoothed = cv2.GaussianBlur(closed, (smooth_size, smooth_size), 0)
    _, smoothed = cv2.threshold(smoothed, 127, 255, cv2.THRESH_BINARY)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(smoothed, 8)
    if count <= 1:
        return mask
    best_label = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    return np.where(labels == best_label, 255, 0).astype(np.uint8)


def _expand_compact_mount(image: np.ndarray, mask: np.ndarray) -> np.ndarray | None:
    """Expand a bright compact insert to its enclosing round rubber mount.

    The deliberately narrow trigger and inward radius bias make this a safe
    recovery path: if a strong enclosing circle is absent, the original mask
    is kept instead of growing into the background.
    """
    points = cv2.findNonZero(mask)
    if points is None:
        return None
    marker = _marker_mask(image)
    marker_count = cv2.countNonZero(marker)
    if marker_count == 0:
        return None
    marker_overlap = np.count_nonzero((marker > 0) & (mask > 0)) / marker_count
    if marker_overlap < 0.05:
        return None
    h, w = mask.shape
    x, y, width, height = cv2.boundingRect(points)
    coverage = cv2.countNonZero(mask) / mask.size
    aspect = width / max(1, height)
    fill_ratio = cv2.countNonZero(mask) / max(1, width * height)
    if coverage > 0.06 or not 0.65 <= aspect <= 1.65 or fill_ratio < 0.45:
        return None

    size = max(width, height)
    cx, cy = x + width // 2, y + height // 2
    half = int(size * 1.15)
    left, top = max(0, cx - half), max(0, cy - half)
    right, bottom = min(w, cx + half), min(h, cy + half)
    roi = cv2.cvtColor(image[top:bottom, left:right], cv2.COLOR_BGR2GRAY)
    roi = cv2.GaussianBlur(roi, (7, 7), 1.5)
    circles = cv2.HoughCircles(
        roi,
        cv2.HOUGH_GRADIENT,
        dp=1.2,
        minDist=max(20, size // 2),
        param1=120,
        param2=42,
        minRadius=max(12, int(size * 0.53)),
        maxRadius=max(13, int(size * 0.92)),
    )
    if circles is None:
        return None

    mask_y, mask_x = np.nonzero(mask)
    candidates = []
    for local_x, local_y, radius in circles[0]:
        circle_x, circle_y = local_x + left, local_y + top
        if (circle_x - radius <= 1 or circle_y - radius <= 1 or
                circle_x + radius >= w - 1 or circle_y + radius >= h - 1):
            continue
        distances = np.hypot(mask_x - circle_x, mask_y - circle_y)
        contained = np.count_nonzero(distances <= radius * 0.98) / len(distances)
        center_offset = np.hypot(circle_x - cx, circle_y - cy) / radius
        if contained < 0.97 or center_offset > 0.38:
            continue
        candidates.append((radius, circle_x, circle_y))
    if not candidates:
        return None

    radius, circle_x, circle_y = max(candidates)
    # Stay visibly inside the detected outer edge. Missing a thin rim is safer
    # than including even a narrow strip of an unknown field background.
    safe_radius = max(1, int(radius * 0.90))
    expanded = np.zeros_like(mask)
    cv2.circle(expanded, (round(circle_x), round(circle_y)), safe_radius, 255, cv2.FILLED)
    return expanded


def _marker_mask(image: np.ndarray) -> np.ndarray:
    """Locate optional cyan marks; an empty result is a normal input."""
    hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    raw = cv2.inRange(hsv, (80, 105, 65), (92, 255, 255))
    raw = cv2.morphologyEx(raw, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    h, w = raw.shape
    count, labels, stats, _ = cv2.connectedComponentsWithStats(raw, 8)
    selected = np.zeros_like(raw)
    min_area = max(12, int(h * w * 0.00002))
    max_area = int(h * w * 0.03)
    border_margin = max(2, int(min(h, w) * 0.08))
    valid = []
    for label in range(1, count):
        x, y, width, height, area = stats[label]
        clear_of_border = (x > border_margin and y > border_margin and
                           x + width < w - border_margin and y + height < h - border_margin)
        if (min_area <= area <= max_area and width < w * 0.25 and
                height < h * 0.25 and clear_of_border):
            valid.append((label, area))
    if valid:
        dominant_area = max(area for _, area in valid)
        for label, area in valid:
            if area >= dominant_area * 0.25:
                selected[labels == label] = 255
    return selected


def _grabcut_key_region(image: np.ndarray, marker_mask: np.ndarray) -> np.ndarray | None:
    """V3/V4-style rectangle GrabCut, reduced to the center-overlapping component."""
    h, w = image.shape[:2]
    margin_x = max(2, int(w * 0.10))
    margin_y = max(2, int(h * 0.10))
    rect = (margin_x, margin_y, w - 2 * margin_x, h - 2 * margin_y)
    gc_mask = np.zeros((h, w), np.uint8)
    cv2.setRNGSeed(20260828)
    bg_model = np.zeros((1, 65), np.float64)
    fg_model = np.zeros((1, 65), np.float64)
    cv2.grabCut(image, gc_mask, rect, bg_model, fg_model, 5, cv2.GC_INIT_WITH_RECT)
    binary = np.isin(gc_mask, (cv2.GC_FGD, cv2.GC_PR_FGD)).astype(np.uint8)
    kernel_size = max(5, (min(h, w) // 100) | 1)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (kernel_size, kernel_size))
    binary = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, kernel)

    center = np.zeros((h, w), np.uint8)
    cv2.ellipse(center, (w // 2, h // 2), (int(w * .18), int(h * .18)), 0, 0, 360, 1, -1)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(binary, 8)
    best_label = 0
    best_score = 0.0
    for label in range(1, count):
        area = stats[label, cv2.CC_STAT_AREA]
        area_ratio = area / (h * w)
        if area_ratio < 0.02 or area_ratio > 0.75:
            continue
        component = labels == label
        center_overlap = np.count_nonzero(component & (center > 0))
        marker_overlap = np.count_nonzero(component & (marker_mask > 0))
        score = marker_overlap + center_overlap * 5 + area
        if center_overlap > 0 and score > best_score:
            best_score, best_label = score, label
    if best_label == 0:
        return None
    selected = np.where(labels == best_label, 255, 0).astype(np.uint8)
    return _outer_envelope(selected)


def _auto_key_region(image: np.ndarray) -> tuple[np.ndarray | None, str]:
    """Use the simple V3/V4 AutoRoi path before any iterative segmentation."""
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    smooth = cv2.GaussianBlur(gray, (5, 5), 0)
    h, w = gray.shape
    image_area = h * w
    center_mask = np.zeros_like(gray)
    cv2.ellipse(center_mask, (w // 2, h // 2), (int(w * .26), int(h * .26)), 0, 0, 360, 255, -1)
    center_area = np.count_nonzero(center_mask)
    marker_mask = _marker_mask(image)
    marker_count = max(1, np.count_nonzero(marker_mask))
    marker_near = cv2.dilate(
        marker_mask,
        cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (max(7, (min(h, w) // 25) | 1),) * 2),
    )
    def candidates(binary: np.ndarray) -> list[tuple[float, np.ndarray]]:
        contours, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        accepted = []
        for contour in contours:
            area = cv2.contourArea(contour)
            x, y, width, height = cv2.boundingRect(contour)
            area_ratio = area / image_area
            rect_ratio = width * height / image_area
            if area_ratio < 0.015 or area_ratio > 0.80 or rect_ratio >= 0.95:
                continue
            if min(width, height) < min(h, w) * 0.05:
                continue
            clearance = max(2, int(min(h, w) * 0.01))
            if (x <= clearance or y <= clearance or
                    x + width >= w - clearance or y + height >= h - clearance):
                continue
            moments = cv2.moments(contour)
            if moments["m00"] == 0:
                continue
            cx = moments["m10"] / moments["m00"]
            cy = moments["m01"] / moments["m00"]
            center_distance = np.hypot((cx - w / 2) / w, (cy - h / 2) / h)
            if center_distance > 0.38:
                continue
            candidate_mask = np.zeros_like(gray)
            cv2.drawContours(candidate_mask, [contour], -1, 255, cv2.FILLED)
            center_overlap = np.count_nonzero((candidate_mask > 0) & (center_mask > 0)) / center_area
            marker_overlap = np.count_nonzero((candidate_mask > 0) & (marker_mask > 0)) / marker_count
            marker_proximity = np.count_nonzero((candidate_mask > 0) & (marker_near > 0)) / max(
                1, np.count_nonzero(marker_near))
            score = (area_ratio + 0.55 * center_overlap - 0.25 * center_distance
                     + 0.25 * marker_overlap + 0.10 * marker_proximity)
            accepted.append((score, contour))
        return accepted

    otsu_candidates = []
    for mode in (cv2.THRESH_BINARY_INV, cv2.THRESH_BINARY):
        _, binary = cv2.threshold(smooth, 0, 255, mode | cv2.THRESH_OTSU)
        kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
        binary = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, kernel)
        otsu_candidates.extend(candidates(binary))
    if otsu_candidates:
        contour = max(otsu_candidates, key=lambda item: item[0])[1]
        mask = np.zeros_like(gray)
        cv2.drawContours(mask, [contour], -1, 255, cv2.FILLED)
        return _outer_envelope(mask), "OTSU_KEY_REGION"

    edges = cv2.Canny(smooth, 50, 150)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
    closed = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, kernel)
    canny_candidates = candidates(closed)
    if canny_candidates:
        contour = max(canny_candidates, key=lambda item: item[0])[1]
        mask = np.zeros_like(gray)
        cv2.drawContours(mask, [contour], -1, 255, cv2.FILLED)
        return _outer_envelope(mask), "CANNY_KEY_REGION"
    grabcut = _grabcut_key_region(image, marker_mask)
    if grabcut is not None:
        return grabcut, "V3_RECT_GRABCUT_FALLBACK"
    return None, "SEGMENTATION_FALLBACK"


def _quality_metrics(mask: np.ndarray, method: str) -> dict:
    h, w = mask.shape
    coverage = float(np.count_nonzero(mask) / mask.size)
    foreground_points = cv2.findNonZero(mask)
    if foreground_points is None:
        clearance = 0.0
    else:
        x, y, width, height = cv2.boundingRect(foreground_points)
        clearance = min(x, y, w - x - width, h - y - height) / min(h, w)
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    solidity = 0.0
    gap_ratio = 0.0
    if contours:
        contour = max(contours, key=cv2.contourArea)
        contour_area = cv2.contourArea(contour)
        hull_area = cv2.contourArea(cv2.convexHull(contour))
        solidity = contour_area / hull_area if hull_area > 0 else 0.0
        exterior = np.zeros_like(mask)
        cv2.drawContours(exterior, contours, -1, 255, cv2.FILLED)
        exterior_area = cv2.countNonZero(exterior)
        if exterior_area > 0:
            gap_ratio = (exterior_area - cv2.countNonZero(mask)) / exterior_area
    edge_pixels = np.concatenate((mask[0], mask[-1], mask[:, 0], mask[:, -1]))
    unreliable = coverage < 0.04 or clearance < 0.02 or solidity < 0.50 or gap_ratio > 0.12
    return {
        "status": "NEEDS_RECAPTURE" if unreliable else "NEEDS_CONFIRMATION",
        "outlineMode": "KEY_REGION_CONTOUR",
        "selectionMethod": method,
        "coverage": round(coverage, 4),
        "borderContact": round(float(np.count_nonzero(edge_pixels) / edge_pixels.size), 4),
        "minimumClearance": round(float(clearance), 4),
        "solidity": round(float(solidity), 4),
        "backgroundGapRatio": round(float(gap_ratio), 4),
    }


def segment_assembly(image: np.ndarray, max_edge: int = 1100) -> tuple[np.ndarray, dict]:
    if image is None or image.size == 0:
        raise ValueError("image is empty")
    source_h, source_w = image.shape[:2]
    scale = min(1.0, max_edge / max(source_h, source_w))
    work = cv2.resize(image, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    h, w = work.shape[:2]
    key_region, selection_method = _auto_key_region(work)
    if key_region is not None:
        expanded = _expand_compact_mount(work, key_region)
        if expanded is not None:
            key_region = expanded
            selection_method += "+LOCAL_MOUNT_CIRCLE"
        full_mask = cv2.resize(key_region, (source_w, source_h), interpolation=cv2.INTER_NEAREST)
        return full_mask, _quality_metrics(key_region, selection_method)
    empty = np.zeros((h, w), np.uint8)
    full_mask = np.zeros((source_h, source_w), np.uint8)
    return full_mask, _quality_metrics(empty, selection_method)


def process(source: Path, output: Path) -> dict:
    image = cv2.imread(str(source), cv2.IMREAD_COLOR)
    mask, metrics = segment_assembly(image)
    contours = contours_from_mask(mask, epsilon_ratio=0.004)
    overlay = image.copy()
    cv2.drawContours(overlay, contours, -1, (0, 255, 255), max(3, image.shape[1] // 700))
    dimmed = cv2.addWeighted(image, .35, np.zeros_like(image), .65, 0)
    preview = np.where((mask > 0)[..., None], overlay, dimmed)
    output.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(output / f"{source.stem}_mask.png"), mask)
    cv2.imwrite(str(output / f"{source.stem}_overlay.jpg"), preview, [cv2.IMWRITE_JPEG_QUALITY, 90])
    payload = {"source": source.name, "width": image.shape[1], "height": image.shape[0], **metrics,
               "contours": contour_payload(contours, image.shape[1], image.shape[0])}
    (output / f"{source.stem}_contour.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return {key: value for key, value in payload.items() if key != "contours"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("names", nargs="*")
    args = parser.parse_args()
    sources = [args.input / name for name in args.names] if args.names else sorted(args.input.glob("*.jpg"))
    manifest = []
    for source in sources:
        row = process(source, args.output)
        manifest.append(row)
        print(f"{source.name}: coverage={row['coverage']} border={row['borderContact']}")
    (args.output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
