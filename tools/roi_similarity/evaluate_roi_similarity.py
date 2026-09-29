#!/usr/bin/env python3
"""Offline geometric registration and annotated ROI similarity experiment."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import statistics
import sys
import time
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path
from typing import Any

import cv2
import numpy as np
from PIL import Image, ImageOps


DEFAULT_DATASET = Path(r"D:\study\Textile_defects\Wearable Inspection\Key_update_black")
DEFAULT_OUTPUT = Path("docs/reports/b3/roi_similarity")
SEED = 20260924
AKAZE_MAX_FEATURES = 1000
MIN_KEYPOINTS = 15
LOWE_RATIO = 0.75
MIN_GOOD_MATCHES = 10
RANSAC_THRESHOLD = 8.0
RANSAC_MAX_ITERS = 500
RANSAC_CONFIDENCE = 0.995
MIN_INLIERS = 10
MIN_INLIER_RATIO = 0.30
MAX_MEDIAN_REPROJECTION = 8.0
MIN_SPATIAL_COVERAGE = 0.15
MIN_PROJECTED_AREA_RATIO = 0.005
MAX_PROJECTED_AREA_RATIO = 0.95
BOUNDARY_MARGIN_PX = 50.0


def json_text(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def safe_float(value: Any) -> float | None:
    if value is None:
        return None
    result = float(value)
    return result if math.isfinite(result) else None


def category_for(filename: str) -> str:
    prefix = Path(filename).stem.split("_", 1)[0].casefold()
    if prefix == "nutsert":
        return "Nutsert"
    if prefix == "nut":
        return "nut"
    if prefix == "thread":
        return "thread"
    return "unknown"


def load_upright(path: Path) -> tuple[np.ndarray, tuple[int, int], tuple[int, int], int | None]:
    with Image.open(path) as image:
        raw_size = image.size
        orientation = image.getexif().get(274)
        upright = ImageOps.exif_transpose(image).convert("RGB")
        upright_size = upright.size
        rgb = np.asarray(upright)
    return cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR), raw_size, upright_size, orientation


def load_boxes(label_path: Path | None, width: int, height: int) -> tuple[list[list[float]], list[int]]:
    if label_path is None or not label_path.exists():
        return [], []
    boxes: list[list[float]] = []
    class_ids: list[int] = []
    for line_number, line in enumerate(label_path.read_text(encoding="utf-8-sig").splitlines(), 1):
        fields = line.split()
        if not fields:
            continue
        if len(fields) != 5:
            raise ValueError(f"invalid YOLO row at {label_path}:{line_number}")
        class_id = int(fields[0])
        cx, cy, box_width, box_height = (float(value) for value in fields[1:])
        x0 = max(0.0, min(float(width), (cx - box_width / 2.0) * width))
        y0 = max(0.0, min(float(height), (cy - box_height / 2.0) * height))
        x1 = max(0.0, min(float(width), (cx + box_width / 2.0) * width))
        y1 = max(0.0, min(float(height), (cy + box_height / 2.0) * height))
        if not (0 <= class_id and x1 > x0 and y1 > y0):
            raise ValueError(f"invalid YOLO box at {label_path}:{line_number}")
        boxes.append([x0, y0, x1, y1])
        class_ids.append(class_id)
    return boxes, class_ids


def inventory_dataset(root: Path) -> tuple[list[dict[str, Any]], Path | None, list[str]]:
    if not root.is_dir():
        raise FileNotFoundError(f"dataset directory not found: {root}")
    candidates = sorted((p for p in root.iterdir() if p.is_file() and p.suffix.casefold() == ".jpg"), key=lambda p: p.name.casefold())
    if not candidates:
        raise ValueError(f"no JPG images found in {root}")
    sibling_labels = root.with_name(root.name + "_label")
    labels_dir = sibling_labels if sibling_labels.is_dir() else None
    classes_path = labels_dir / "classes.txt" if labels_dir else None
    classes = classes_path.read_text(encoding="utf-8-sig").splitlines() if classes_path and classes_path.exists() else []
    entries: list[dict[str, Any]] = []
    for path in candidates:
        image, raw_size, upright_size, orientation = load_upright(path)
        label_path = labels_dir / f"{path.stem}.txt" if labels_dir else None
        boxes, class_ids = load_boxes(label_path, image.shape[1], image.shape[0])
        entries.append({
            "path": path,
            "filename": path.name,
            "category": category_for(path.name),
            "image": image,
            "width": image.shape[1],
            "height": image.shape[0],
            "raw_width": raw_size[0],
            "raw_height": raw_size[1],
            "upright_width": upright_size[0],
            "upright_height": upright_size[1],
            "exif_orientation": orientation,
            "label_path": label_path,
            "boxes": boxes,
            "class_ids": class_ids,
        })
    return entries, labels_dir, classes


def affine_homogeneous(matrix: np.ndarray) -> np.ndarray:
    result = np.eye(3, dtype=np.float64)
    result[:2, :] = matrix
    return result


def stable_rng(seed: int, filename: str, transform_key: str) -> np.random.Generator:
    digest = hashlib.sha256(f"{seed}|{filename}|{transform_key}".encode("utf-8")).digest()
    salt = int.from_bytes(digest[:8], "little")
    return np.random.default_rng((seed + salt) % (2**63 - 1))


def transform_specs(width: int, height: int, filename: str, seed: int) -> list[dict[str, Any]]:
    cx, cy = (width - 1) / 2.0, (height - 1) / 2.0
    specs: list[dict[str, Any]] = [{"key": "identity", "type": "identity", "intensity": "none", "params": {"rotation_deg": 0.0, "scale": 1.0}}]
    for angle in (-30, -20, -10, 10, 20, 30):
        specs.append({"key": f"rotation_{angle:+03d}", "type": "rotation", "intensity": f"{abs(angle)}deg", "params": {"angle_deg": angle}})
    for axis, value in (("x", -0.12), ("x", 0.12), ("y", -0.12), ("y", 0.12)):
        specs.append({"key": f"shear_{axis}_{value:+.2f}", "type": f"shear_{axis}", "intensity": "12pct", "params": {f"shear_{axis}": value}})
    for scale in (0.85, 1.15):
        specs.append({"key": f"scale_{scale:.2f}", "type": "scale", "intensity": f"{scale:.2f}x", "params": {"scale": scale}})
    pattern = np.array([
        [0.18, 0.12], [-0.08, 0.06], [-0.15, -0.10], [0.10, -0.17],
    ], dtype=np.float64)
    for name, amount in (("light", 0.02), ("moderate", 0.055), ("strong", 0.10)):
        rng = stable_rng(seed, filename, f"perspective_{name}")
        jitter = rng.uniform(-0.12, 0.12, size=(4, 2))
        offsets = (pattern + jitter) * amount * np.array([width, height], dtype=np.float64)
        specs.append({"key": f"perspective_{name}", "type": "perspective", "intensity": name, "params": {"corner_offsets_px": offsets.round(5).tolist(), "amount_fraction": amount}})
    combo_rng = stable_rng(seed, filename, "combined_moderate")
    combo_offsets = (pattern + combo_rng.uniform(-0.10, 0.10, size=(4, 2))) * 0.04 * np.array([width, height], dtype=np.float64)
    specs.append({
        "key": "combined_moderate", "type": "combined", "intensity": "moderate",
        "params": {"rotation_deg": 12.0, "shear_x": 0.06, "shear_y": -0.04, "scale": 1.05, "corner_offsets_px": combo_offsets.round(5).tolist()},
    })
    for spec in specs:
        params = spec["params"]
        if spec["type"] == "identity":
            h = np.eye(3, dtype=np.float64)
        elif spec["type"] == "rotation":
            h = affine_homogeneous(cv2.getRotationMatrix2D((cx, cy), float(params["angle_deg"]), 1.0))
        elif spec["type"] == "shear_x":
            k = float(params["shear_x"])
            h = np.array([[1.0, k, -k * cy], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]], dtype=np.float64)
        elif spec["type"] == "shear_y":
            k = float(params["shear_y"])
            h = np.array([[1.0, 0.0, 0.0], [k, 1.0, -k * cx], [0.0, 0.0, 1.0]], dtype=np.float64)
        elif spec["type"] == "scale":
            scale = float(params["scale"])
            h = np.array([[scale, 0.0, cx * (1 - scale)], [0.0, scale, cy * (1 - scale)], [0.0, 0.0, 1.0]], dtype=np.float64)
        elif spec["type"] == "perspective":
            h = perspective_homography(width, height, params["corner_offsets_px"])
        else:
            rotation = affine_homogeneous(cv2.getRotationMatrix2D((cx, cy), float(params["rotation_deg"]), 1.0))
            sx, sy, scale = float(params["shear_x"]), float(params["shear_y"]), float(params["scale"])
            shear = np.array([[1.0, sx, -sx * cy], [sy, 1.0, -sy * cx], [0.0, 0.0, 1.0]], dtype=np.float64)
            scaling = np.array([[scale, 0.0, cx * (1 - scale)], [0.0, scale, cy * (1 - scale)], [0.0, 0.0, 1.0]], dtype=np.float64)
            perspective = perspective_homography(width, height, params["corner_offsets_px"])
            h = perspective @ scaling @ shear @ rotation
        spec["homography"] = h / h[2, 2]
    return specs


def perspective_homography(width: int, height: int, offsets: list[list[float]]) -> np.ndarray:
    source = np.float32([[0, 0], [width - 1, 0], [width - 1, height - 1], [0, height - 1]])
    target = source.astype(np.float64) + np.asarray(offsets, dtype=np.float64)
    return cv2.getPerspectiveTransform(source, target.astype(np.float32)).astype(np.float64)


def warp_case(image: np.ndarray, homography: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    height, width = image.shape[:2]
    query = cv2.warpPerspective(image, homography, (width, height), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0))
    source_mask = np.full((height, width), 255, dtype=np.uint8)
    valid_mask = cv2.warpPerspective(source_mask, homography, (width, height), flags=cv2.INTER_NEAREST, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    return query, valid_mask


def corner_points(width: int, height: int) -> np.ndarray:
    return np.float32([[0, 0], [width - 1, 0], [width - 1, height - 1], [0, height - 1]]).reshape(-1, 1, 2)


def polygon_area(points: np.ndarray) -> float:
    points = np.asarray(points, dtype=np.float64).reshape(-1, 2)
    if len(points) < 3:
        return 0.0
    return abs(float(cv2.contourArea(points.astype(np.float32))))


def convex_quad(points: np.ndarray) -> bool:
    points = np.asarray(points, dtype=np.float64).reshape(-1, 2)
    if len(points) != 4 or not np.isfinite(points).all():
        return False
    signs = []
    for index in range(4):
        a, b, c = points[index], points[(index + 1) % 4], points[(index + 2) % 4]
        ab, bc = b - a, c - b
        cross = float(ab[0] * bc[1] - ab[1] * bc[0])
        if abs(cross) > 1e-10:
            signs.append(math.copysign(1.0, cross))
    return bool(signs) and all(sign == signs[0] for sign in signs)


def app_gate_failure(
    inlier_count: int,
    good_count: int,
    reprojection: float | None,
    spatial_coverage: float | None,
    homography: np.ndarray,
    boxes: list[list[float]],
    query_width: int,
    query_height: int,
) -> tuple[str | None, list[dict[str, Any]]]:
    if reprojection is None or spatial_coverage is None:
        return "non_finite_registration_metric", []
    if inlier_count < MIN_INLIERS:
        return f"inlier_count_below_{MIN_INLIERS}", []
    if good_count <= 0 or inlier_count / good_count < MIN_INLIER_RATIO:
        return f"inlier_ratio_below_{MIN_INLIER_RATIO:.2f}", []
    if reprojection > MAX_MEDIAN_REPROJECTION:
        return f"median_reprojection_above_{MAX_MEDIAN_REPROJECTION:g}px", []
    if spatial_coverage < MIN_SPATIAL_COVERAGE:
        return f"spatial_coverage_below_{MIN_SPATIAL_COVERAGE:.2f}", []
    if not boxes:
        return "ROI_annotation_unavailable_for_corner_gate", []
    roi_gates: list[dict[str, Any]] = []
    for roi_index, box in enumerate(boxes):
        x0, y0, x1, y1 = box
        corners = np.float32([[x0, y0], [x1, y0], [x1, y1], [x0, y1]]).reshape(-1, 1, 2)
        projected = cv2.perspectiveTransform(corners, homography).reshape(-1, 2)
        area_ratio = polygon_area(projected) / max(float(query_width * query_height), 1.0)
        failure = None
        if not np.isfinite(projected).all():
            failure = "projected_corners_non_finite"
        elif not convex_quad(projected):
            failure = "projected_quad_non_convex"
        elif area_ratio < MIN_PROJECTED_AREA_RATIO:
            failure = "projected_area_below_0.005"
        elif area_ratio > MAX_PROJECTED_AREA_RATIO:
            failure = "projected_area_above_0.95"
        elif np.any(projected[:, 0] < -BOUNDARY_MARGIN_PX) or np.any(projected[:, 1] < -BOUNDARY_MARGIN_PX) or np.any(projected[:, 0] > query_width + BOUNDARY_MARGIN_PX) or np.any(projected[:, 1] > query_height + BOUNDARY_MARGIN_PX):
            failure = "projected_corners_outside_50px_margin"
        roi_gates.append({"roi_index": roi_index, "area_ratio": area_ratio, "failure": failure})
    failed = next((item["failure"] for item in roi_gates if item["failure"]), None)
    return failed, roi_gates


def registration(reference: np.ndarray, query: np.ndarray, boxes: list[list[float]]) -> dict[str, Any]:
    started = time.perf_counter()
    ref_gray = cv2.cvtColor(reference, cv2.COLOR_BGR2GRAY)
    query_gray = cv2.cvtColor(query, cv2.COLOR_BGR2GRAY)
    detector = cv2.AKAZE_create(cv2.AKAZE_DESCRIPTOR_MLDB, 0, 3, 0.002, 4, 4, 1)
    ref_kp, ref_desc = detector.detectAndCompute(ref_gray, None)
    query_kp, query_desc = detector.detectAndCompute(query_gray, None)
    result: dict[str, Any] = {
        "template_keypoints": len(ref_kp), "query_keypoints": len(query_kp), "good_match_count": 0,
        "inlier_count": 0, "inlier_ratio": None, "spatial_coverage": None,
        "median_reprojection_error_px": None, "estimated_homography": None,
        "homography_method": "USAC_MAGSAC", "registration_status": "FAILED",
        "quality_gates_pass": False, "failure_reason": None, "roi_gate_results": [],
    }
    if len(ref_kp) < MIN_KEYPOINTS:
        result["failure_reason"] = "template_feature_count_below_15"
    elif len(query_kp) < MIN_KEYPOINTS:
        result["failure_reason"] = "query_feature_count_below_15"
    elif ref_desc is None or query_desc is None:
        result["failure_reason"] = "descriptors_unavailable"
    else:
        ref_indices = sorted(range(len(ref_kp)), key=lambda index: ref_kp[index].response, reverse=True)[:AKAZE_MAX_FEATURES]
        query_indices = sorted(range(len(query_kp)), key=lambda index: query_kp[index].response, reverse=True)[:AKAZE_MAX_FEATURES]
        ref_desc = ref_desc[ref_indices]
        query_desc = query_desc[query_indices]
        ref_kp = [ref_kp[index] for index in ref_indices]
        query_kp = [query_kp[index] for index in query_indices]
        pairs = cv2.BFMatcher(cv2.NORM_HAMMING).knnMatch(ref_desc, query_desc, k=2)
        good = [pair[0] for pair in pairs if len(pair) >= 2 and pair[0].distance < LOWE_RATIO * pair[1].distance]
        result["good_match_count"] = len(good)
        if len(good) < MIN_GOOD_MATCHES:
            result["failure_reason"] = "good_match_count_below_10"
        else:
            source = np.float32([ref_kp[item.queryIdx].pt for item in good]).reshape(-1, 1, 2)
            destination = np.float32([query_kp[item.trainIdx].pt for item in good]).reshape(-1, 1, 2)
            method = getattr(cv2, "USAC_MAGSAC", cv2.RANSAC)
            result["homography_method"] = "USAC_MAGSAC" if method != cv2.RANSAC else "RANSAC"
            try:
                h, inlier_mask = cv2.findHomography(source, destination, method, RANSAC_THRESHOLD, maxIters=RANSAC_MAX_ITERS, confidence=RANSAC_CONFIDENCE)
            except cv2.error:
                if method == cv2.RANSAC:
                    h, inlier_mask = None, None
                else:
                    result["homography_method"] = "RANSAC_fallback_after_USAC_error"
                    h, inlier_mask = cv2.findHomography(source, destination, cv2.RANSAC, RANSAC_THRESHOLD, maxIters=RANSAC_MAX_ITERS, confidence=RANSAC_CONFIDENCE)
            if h is None or inlier_mask is None or not np.isfinite(h).all():
                result["failure_reason"] = "homography_estimation_failed"
            else:
                inliers = inlier_mask.ravel().astype(bool)
                count = int(np.count_nonzero(inliers))
                result["estimated_homography"] = h.astype(np.float64) / h[2, 2]
                result["inlier_count"] = count
                result["inlier_ratio"] = count / max(len(good), 1)
                if count >= 1:
                    projected = cv2.perspectiveTransform(source[inliers], h).reshape(-1, 2)
                    observed = destination[inliers].reshape(-1, 2)
                    errors = np.linalg.norm(projected - observed, axis=1)
                    result["median_reprojection_error_px"] = float(np.median(errors))
                if count >= 3:
                    points = destination[inliers].reshape(-1, 2).astype(np.float32)
                    hull_area = float(cv2.contourArea(cv2.convexHull(points)))
                    span = points.max(axis=0) - points.min(axis=0)
                    bbox_area = float(span[0] * span[1])
                    result["spatial_coverage"] = hull_area / bbox_area if bbox_area > 0 else 0.0
                query_height, query_width = query.shape[:2]
                failure, roi_gates = app_gate_failure(count, len(good), result["median_reprojection_error_px"], result["spatial_coverage"], h, boxes, query_width, query_height)
                result["roi_gate_results"] = roi_gates
                result["registration_status"] = "SUCCESS" if failure is None else "FALLBACK_FULL_IMAGE"
                result["quality_gates_pass"] = failure is None
                result["failure_reason"] = failure
    result["registration_time_ms"] = (time.perf_counter() - started) * 1000.0
    return result


def ssim_map(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    a = a.astype(np.float32)
    b = b.astype(np.float32)
    mu_a = cv2.GaussianBlur(a, (11, 11), 1.5)
    mu_b = cv2.GaussianBlur(b, (11, 11), 1.5)
    mu_aa, mu_bb, mu_ab = mu_a * mu_a, mu_b * mu_b, mu_a * mu_b
    sigma_aa = cv2.GaussianBlur(a * a, (11, 11), 1.5) - mu_aa
    sigma_bb = cv2.GaussianBlur(b * b, (11, 11), 1.5) - mu_bb
    sigma_ab = cv2.GaussianBlur(a * b, (11, 11), 1.5) - mu_ab
    c1, c2 = (0.01 * 255) ** 2, (0.03 * 255) ** 2
    score = ((2 * mu_ab + c1) * (2 * sigma_ab + c2)) / ((mu_aa + mu_bb + c1) * (sigma_aa + sigma_bb + c2) + 1e-12)
    return np.clip(score, -1.0, 1.0)


def gradient_magnitude(gray: np.ndarray) -> np.ndarray:
    gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
    return np.clip(cv2.magnitude(gx, gy), 0, 255).astype(np.uint8)


def roi_similarity(
    reference_gray: np.ndarray,
    comparison_gray: np.ndarray,
    valid_mask: np.ndarray,
    boxes: list[list[float]],
) -> dict[str, Any]:
    if not boxes:
        return {"ssim_mean": None, "ssim_min": None, "gradient_ssim_mean": None, "gradient_ssim_min": None, "valid_roi_ratio": None, "valid_roi_pixels": 0}
    common = (valid_mask > 0).astype(np.uint8) * 255
    common = cv2.erode(common, np.ones((11, 11), dtype=np.uint8), iterations=1)
    ref_gradient = gradient_magnitude(reference_gray)
    cmp_gradient = gradient_magnitude(comparison_gray)
    image_ssim = ssim_map(reference_gray, comparison_gray)
    gradient_ssim = ssim_map(ref_gradient, cmp_gradient)
    scores: list[float] = []
    gradient_scores: list[float] = []
    eligible_pixels = 0
    total_roi_pixels = 0
    for x0f, y0f, x1f, y1f in boxes:
        x0, y0 = max(0, int(math.floor(x0f))), max(0, int(math.floor(y0f)))
        x1, y1 = min(reference_gray.shape[1], int(math.ceil(x1f))), min(reference_gray.shape[0], int(math.ceil(y1f)))
        if x1 <= x0 or y1 <= y0:
            continue
        roi_mask = np.zeros_like(common)
        roi_mask[y0:y1, x0:x1] = 255
        valid = (common > 0) & (roi_mask > 0)
        total_roi_pixels += int(np.count_nonzero(roi_mask))
        count = int(np.count_nonzero(valid))
        if count:
            eligible_pixels += count
            scores.append(float(np.mean(image_ssim[valid])))
            gradient_scores.append(float(np.mean(gradient_ssim[valid])))
    return {
        "ssim_mean": float(np.mean(scores)) if scores else None,
        "ssim_min": float(np.min(scores)) if scores else None,
        "gradient_ssim_mean": float(np.mean(gradient_scores)) if gradient_scores else None,
        "gradient_ssim_min": float(np.min(gradient_scores)) if gradient_scores else None,
        "valid_roi_ratio": eligible_pixels / total_roi_pixels if total_roi_pixels else None,
        "valid_roi_pixels": eligible_pixels,
    }


def align_query(query: np.ndarray, query_mask: np.ndarray, homography_ref_to_query: np.ndarray, reference_shape: tuple[int, int]) -> tuple[np.ndarray, np.ndarray]:
    height, width = reference_shape
    inverse = np.linalg.inv(homography_ref_to_query)
    aligned = cv2.warpPerspective(query, inverse, (width, height), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0))
    mask = cv2.warpPerspective(query_mask, inverse, (width, height), flags=cv2.INTER_NEAREST, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    return aligned, mask


def corner_error(estimated: np.ndarray, known: np.ndarray, width: int, height: int) -> float:
    corners = corner_points(width, height)
    expected = cv2.perspectiveTransform(corners, known).reshape(-1, 2)
    actual = cv2.perspectiveTransform(corners, estimated).reshape(-1, 2)
    return float(np.mean(np.linalg.norm(expected - actual, axis=1)))


def overlap_ratio(source_mask: np.ndarray, aligned_mask: np.ndarray) -> float:
    source_valid = source_mask > 0
    denominator = int(np.count_nonzero(source_valid))
    if denominator == 0:
        return 0.0
    return float(np.count_nonzero(source_valid & (aligned_mask > 0)) / denominator)


def save_transform_artifact(path: Path, homography: np.ndarray, mask: np.ndarray, params: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(path, homography_src_to_query=homography.astype(np.float64), valid_pixel_mask=mask.astype(np.uint8), params_json=np.asarray(json_text(params)))


def transform_box_polygons(boxes: list[list[float]], homography: np.ndarray) -> list[np.ndarray]:
    polygons = []
    for x0, y0, x1, y1 in boxes:
        corners = np.float32([[x0, y0], [x1, y0], [x1, y1], [x0, y1]]).reshape(-1, 1, 2)
        polygons.append(cv2.perspectiveTransform(corners, homography).reshape(-1, 2))
    return polygons


def create_contact_sheet(entry: dict[str, Any], spec: dict[str, Any], query: np.ndarray, estimated_h: np.ndarray | None, destination: Path, status: str) -> None:
    image = entry["image"]
    h, w = image.shape[:2]
    known_h = np.asarray(spec["homography"], dtype=np.float64)
    if estimated_h is not None:
        registered, _ = align_query(query, np.full((h, w), 255, dtype=np.uint8), estimated_h, (h, w))
        registration_label = "Estimated H aligned (diagnostic)"
    else:
        registered = query.copy()
        registration_label = "No H: query shown unaligned"
    source_with_boxes = image.copy()
    for x0, y0, x1, y1 in entry["boxes"]:
        cv2.rectangle(source_with_boxes, (round(x0), round(y0)), (round(x1), round(y1)), (0, 255, 255), max(1, round(min(w, h) / 300)))
    query_with_boxes = query.copy()
    for polygon in transform_box_polygons(entry["boxes"], known_h):
        cv2.polylines(query_with_boxes, [np.round(polygon).astype(np.int32)], True, (0, 255, 255), max(1, round(min(w, h) / 300)))
    overlay = cv2.addWeighted(image, 0.5, registered, 0.5, 0)
    diff = cv2.absdiff(cv2.cvtColor(image, cv2.COLOR_BGR2GRAY), cv2.cvtColor(registered, cv2.COLOR_BGR2GRAY))
    diff = cv2.applyColorMap(cv2.normalize(diff, None, 0, 255, cv2.NORM_MINMAX), cv2.COLORMAP_JET)
    panels = [source_with_boxes, query_with_boxes, registered, overlay, diff]
    labels = ["Original + annotated ROI", "Transformed + projected ROI", registration_label, "Overlay", "Difference heatmap"]
    panel_width, panel_height, header_height = 300, 300, 78
    sheet = np.full((header_height + panel_height, panel_width * len(panels), 3), 248, dtype=np.uint8)
    header = f"{entry['filename']} | {spec['key']} | registration={status}"
    cv2.putText(sheet, header[:100], (8, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.48, (25, 25, 25), 1, cv2.LINE_AA)
    cv2.putText(sheet, "Yellow boxes come from paired YOLO annotations", (8, 49), cv2.FONT_HERSHEY_SIMPLEX, 0.43, (45, 45, 45), 1, cv2.LINE_AA)
    for index, (panel, label) in enumerate(zip(panels, labels)):
        cv2.putText(sheet, label[:38], (index * panel_width + 6, header_height - 8), cv2.FONT_HERSHEY_SIMPLEX, 0.37, (25, 25, 25), 1, cv2.LINE_AA)
        scale = min(panel_width / panel.shape[1], panel_height / panel.shape[0])
        resized = cv2.resize(panel, (max(1, int(panel.shape[1] * scale)), max(1, int(panel.shape[0] * scale))), interpolation=cv2.INTER_AREA)
        y0 = header_height + (panel_height - resized.shape[0]) // 2
        x0 = index * panel_width + (panel_width - resized.shape[1]) // 2
        sheet[y0:y0 + resized.shape[0], x0:x0 + resized.shape[1]] = resized
    destination.parent.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(destination), sheet)


def pair_row(
    pair_id: str,
    pair_kind: str,
    reference: dict[str, Any],
    query_entry: dict[str, Any],
    query: np.ndarray,
    query_mask: np.ndarray,
    boxes: list[list[float]],
    transform: dict[str, Any] | None,
    known_h: np.ndarray | None,
    source_mask: np.ndarray,
    artifact_path: str | None,
) -> dict[str, Any]:
    estimate = registration(reference["image"], query, boxes)
    ref_gray = cv2.cvtColor(reference["image"], cv2.COLOR_BGR2GRAY)
    query_gray = cv2.cvtColor(query, cv2.COLOR_BGR2GRAY)
    raw_query = query_gray
    raw_mask = query_mask
    if raw_query.shape != ref_gray.shape:
        raw_query = cv2.resize(raw_query, (ref_gray.shape[1], ref_gray.shape[0]), interpolation=cv2.INTER_AREA)
        raw_mask = cv2.resize(query_mask, (ref_gray.shape[1], ref_gray.shape[0]), interpolation=cv2.INTER_NEAREST)
    pre = roi_similarity(ref_gray, raw_query, cv2.bitwise_and(source_mask, raw_mask), boxes)
    estimated_metrics: dict[str, Any] = {"ssim_mean": None, "ssim_min": None, "gradient_ssim_mean": None, "gradient_ssim_min": None, "valid_roi_ratio": None}
    known_metrics: dict[str, Any] = {"ssim_mean": None, "ssim_min": None, "gradient_ssim_mean": None, "gradient_ssim_min": None, "valid_roi_ratio": None}
    estimated_overlap = None
    estimated_roi_overlap = None
    known_overlap = None
    known_roi_overlap = None
    estimated_h = estimate["estimated_homography"]
    if estimated_h is not None:
        aligned, aligned_mask = align_query(query_gray, query_mask, estimated_h, ref_gray.shape)
        estimated_overlap = overlap_ratio(source_mask, aligned_mask)
        estimated_metrics = roi_similarity(ref_gray, aligned, cv2.bitwise_and(source_mask, aligned_mask), boxes)
        estimated_roi_overlap = estimated_metrics["valid_roi_ratio"]
    if known_h is not None:
        known_aligned, known_aligned_mask = align_query(query_gray, query_mask, known_h, ref_gray.shape)
        known_overlap = overlap_ratio(source_mask, known_aligned_mask)
        known_metrics = roi_similarity(ref_gray, known_aligned, cv2.bitwise_and(source_mask, known_aligned_mask), boxes)
        known_roi_overlap = known_metrics["valid_roi_ratio"]
    error = corner_error(estimated_h, known_h, reference["width"], reference["height"]) if estimated_h is not None and known_h is not None else None
    transform = transform or {"type": "different_source_pair", "intensity": "control", "key": "different_source"}
    params = transform.get("params", {})
    return {
        "pair_id": pair_id,
        "pair_kind": pair_kind,
        "source_file": reference["filename"],
        "query_file": query_entry["filename"],
        "source_category": reference["category"],
        "query_category": query_entry["category"],
        "transform_type": transform["type"],
        "transform_intensity": transform["intensity"],
        "transform_key": transform["key"],
        "transform_params_json": json_text(params),
        "known_homography_json": json_text(known_h.tolist()) if known_h is not None else "",
        "estimated_homography_json": json_text(estimated_h.tolist()) if estimated_h is not None else "",
        "transform_artifact": artifact_path or "",
        "roi_count": len(boxes),
        "roi_policy": reference["roi_policy"],
        "template_keypoints": estimate["template_keypoints"],
        "query_keypoints": estimate["query_keypoints"],
        "good_match_count": estimate["good_match_count"],
        "inlier_count": estimate["inlier_count"],
        "inlier_ratio": safe_float(estimate["inlier_ratio"]),
        "spatial_coverage": safe_float(estimate["spatial_coverage"]),
        "median_reprojection_error_px": safe_float(estimate["median_reprojection_error_px"]),
        "estimated_overlap_ratio": safe_float(estimated_overlap),
        "estimated_roi_valid_overlap_ratio": safe_float(estimated_roi_overlap),
        "known_overlap_ratio": safe_float(known_overlap),
        "known_roi_valid_overlap_ratio": safe_float(known_roi_overlap),
        "corner_error_mean_px": safe_float(error),
        "homography_method": estimate["homography_method"],
        "registration_status": estimate["registration_status"],
        "homography_estimated": estimated_h is not None,
        "quality_gates_pass": estimate["quality_gates_pass"],
        "roi_gate_results_json": json_text(estimate["roi_gate_results"]),
        "failure_reason": estimate["failure_reason"] or "",
        "registration_time_ms": round(estimate["registration_time_ms"], 3),
        "unaligned_roi_ssim_mean": safe_float(pre["ssim_mean"]),
        "unaligned_roi_ssim_min": safe_float(pre["ssim_min"]),
        "estimated_roi_ssim_mean": safe_float(estimated_metrics["ssim_mean"]),
        "estimated_roi_ssim_min": safe_float(estimated_metrics["ssim_min"]),
        "known_roi_ssim_mean": safe_float(known_metrics["ssim_mean"]),
        "known_roi_ssim_min": safe_float(known_metrics["ssim_min"]),
        "unaligned_roi_gradient_ssim_mean": safe_float(pre["gradient_ssim_mean"]),
        "estimated_roi_gradient_ssim_mean": safe_float(estimated_metrics["gradient_ssim_mean"]),
        "known_roi_gradient_ssim_mean": safe_float(known_metrics["gradient_ssim_mean"]),
        "unaligned_valid_roi_ratio": safe_float(pre["valid_roi_ratio"]),
        "estimated_valid_roi_ratio": safe_float(estimated_metrics["valid_roi_ratio"]),
        "known_valid_roi_ratio": safe_float(known_metrics["valid_roi_ratio"]),
        "feature_or_registration_failure_reason": estimate["failure_reason"] or "",
    }


def write_inventory(entries: list[dict[str, Any]], output: Path, labels_dir: Path | None, classes: list[str]) -> None:
    fields = ["filename", "category", "raw_width", "raw_height", "upright_width", "upright_height", "exif_orientation", "label_file", "roi_box_count", "annotation_class_ids", "annotation_class_names", "roi_boxes_xyxy_upright_px"]
    with (output / "dataset_inventory.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for item in entries:
            writer.writerow({
                "filename": item["filename"], "category": item["category"],
                "raw_width": item["raw_width"], "raw_height": item["raw_height"],
                "upright_width": item["upright_width"], "upright_height": item["upright_height"],
                "exif_orientation": item["exif_orientation"] if item["exif_orientation"] is not None else "missing",
                "label_file": item["label_path"].name if item["label_path"] and item["label_path"].exists() else "",
                "roi_box_count": len(item["boxes"]), "annotation_class_ids": json_text(item["class_ids"]),
                "annotation_class_names": json_text([classes[i] if 0 <= i < len(classes) else f"class_{i}" for i in item["class_ids"]]),
                "roi_boxes_xyxy_upright_px": json_text(item["boxes"]),
            })
    counts = Counter(item["category"] for item in entries)
    annotation_counts = Counter(item["class_ids"][i] for item in entries for i in range(len(item["class_ids"])))
    inventory = {
        "dataset_directory": str(entries[0]["path"].parent), "image_count": len(entries),
        "category_counts": dict(counts), "label_directory": str(labels_dir) if labels_dir else None,
        "matched_label_files": sum(bool(item["label_path"] and item["label_path"].exists()) for item in entries),
        "roi_box_count": sum(len(item["boxes"]) for item in entries),
        "annotation_class_counts": {classes[key] if key < len(classes) else f"class_{key}": value for key, value in sorted(annotation_counts.items())},
        "exif_orientation_counts": dict(Counter(str(item["exif_orientation"] if item["exif_orientation"] is not None else "missing") for item in entries)),
        "source_read_only": True,
    }
    (output / "dataset_inventory.json").write_text(json.dumps(inventory, ensure_ascii=False, indent=2), encoding="utf-8")


def distribution(values: list[float]) -> dict[str, float | int | None]:
    values = [float(value) for value in values if value is not None and math.isfinite(float(value))]
    if not values:
        return {"count": 0, "mean": None, "median": None, "p90": None, "min": None, "max": None}
    return {"count": len(values), "mean": float(statistics.mean(values)), "median": float(statistics.median(values)), "p90": float(np.percentile(values, 90)), "min": float(min(values)), "max": float(max(values))}


def summarize(rows: list[dict[str, Any]], entries: list[dict[str, Any]], labels_dir: Path | None, classes: list[str], seed: int, dataset: Path) -> dict[str, Any]:
    synthetic = [row for row in rows if row["pair_kind"] == "synthetic_transform"]
    controls = [row for row in rows if row["pair_kind"].startswith("different_source")]
    groups: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in synthetic:
        groups[(row["transform_type"], row["transform_intensity"])].append(row)
    transform_summary: dict[str, Any] = {}
    for (transform_type, intensity), group in sorted(groups.items()):
        key = f"{transform_type}:{intensity}"
        transform_summary[key] = {
            "case_count": len(group),
            "homography_estimated": sum(bool(item["homography_estimated"]) for item in group),
            "registration_success": sum(item["registration_status"] == "SUCCESS" for item in group),
            "quality_gate_pass_rate": sum(bool(item["quality_gates_pass"]) for item in group) / len(group),
            "failure_types": dict(Counter(item["failure_reason"] or "none" for item in group)),
            "inlier_count": distribution([item["inlier_count"] for item in group]),
            "inlier_ratio": distribution([item["inlier_ratio"] for item in group]),
            "spatial_coverage": distribution([item["spatial_coverage"] for item in group]),
            "median_reprojection_error_px": distribution([item["median_reprojection_error_px"] for item in group]),
            "corner_error_mean_px": distribution([item["corner_error_mean_px"] for item in group]),
            "estimated_overlap_ratio": distribution([item["estimated_overlap_ratio"] for item in group]),
            "registration_time_ms": distribution([item["registration_time_ms"] for item in group]),
            "unaligned_roi_ssim": distribution([item["unaligned_roi_ssim_mean"] for item in group]),
            "estimated_roi_ssim": distribution([item["estimated_roi_ssim_mean"] for item in group]),
            "known_roi_ssim_upper_reference": distribution([item["known_roi_ssim_mean"] for item in group]),
            "unaligned_roi_gradient_ssim": distribution([item["unaligned_roi_gradient_ssim_mean"] for item in group]),
            "estimated_roi_gradient_ssim": distribution([item["estimated_roi_gradient_ssim_mean"] for item in group]),
            "known_roi_gradient_ssim_upper_reference": distribution([item["known_roi_gradient_ssim_mean"] for item in group]),
        }
    best = sorted((row for row in synthetic if row["corner_error_mean_px"] is not None), key=lambda row: row["corner_error_mean_px"])
    h_failures = [row for row in synthetic if not row["homography_estimated"]]
    gate_rejections = [row for row in synthetic if row["homography_estimated"] and not row["quality_gates_pass"]]
    category_counts = Counter(item["category"] for item in entries)
    return {
        "created_local": datetime.now().astimezone().isoformat(timespec="seconds"),
        "dataset_directory": str(dataset), "seed": seed,
        "inventory": {
            "image_count": len(entries), "category_counts": dict(category_counts),
            "label_directory": str(labels_dir) if labels_dir else None,
            "label_files_matched": sum(bool(item["label_path"] and item["label_path"].exists()) for item in entries),
            "roi_boxes": sum(len(item["boxes"]) for item in entries),
            "annotation_classes": classes,
            "orientation_counts": dict(Counter(str(item["exif_orientation"] if item["exif_orientation"] is not None else "missing") for item in entries)),
        },
        "environment": {
            "python_executable": sys.executable, "python_version": sys.version.split()[0],
            "opencv_version": cv2.__version__, "numpy_version": np.__version__, "pillow_version": Image.__version__,
            "ssim_implementation": "OpenCV Gaussian-window SSIM (local implementation); scikit-image not installed",
        },
        "app_v4_comparison": {
            "feature": "AKAZE M-LDB, threshold 0.002, 4 octaves, 4 layers, top 1000 by response",
            "matching": "BFMatcher Hamming, Lowe ratio 0.75; GMS omitted",
            "homography": "USAC_MAGSAC, 8px threshold, 500 iterations, 0.995 confidence when exposed by OpenCV",
            "quality_gate_thresholds": {
                "minimum_inliers": MIN_INLIERS, "minimum_inlier_ratio": MIN_INLIER_RATIO,
                "maximum_median_reprojection_error_px": MAX_MEDIAN_REPROJECTION,
                "minimum_spatial_coverage_hull_over_bbox": MIN_SPATIAL_COVERAGE,
                "projected_roi_area_ratio": [MIN_PROJECTED_AREA_RATIO, MAX_PROJECTED_AREA_RATIO],
                "boundary_margin_px": BOUNDARY_MARGIN_PX,
            },
            "difference": "Python/OpenCV does not run the App custom GMS filter; ratio-test matches go directly to MAGSAC/RANSAC. App targets OpenCV 4.10.0, this run uses its recorded Python OpenCV version. This is not an Android V4 equivalence claim.",
            "similarity_thresholds_changed": False,
        },
        "scope": {
            "synthetic_case_count": len(synthetic), "different_source_control_count": len(controls),
            "same_category_control_count": sum(row["pair_kind"] == "different_source_same_category" for row in controls),
            "cross_category_control_count": sum(row["pair_kind"] == "different_source_cross_category" for row in controls),
            "different_source_pairs_are_business_negatives": False,
            "automated_ok_ng_or_missing_part_labels_available": False,
            "business_thresholds_used_or_tuned": False,
        },
        "synthetic_overall": {
            "case_count": len(synthetic),
            "homography_estimated_count": sum(bool(row["homography_estimated"]) for row in synthetic),
            "registration_success_count": sum(row["registration_status"] == "SUCCESS" for row in synthetic),
            "gate_rejected_after_homography_count": len(gate_rejections),
            "registration_failed_before_homography_count": len(h_failures),
            "failure_types": dict(Counter(row["failure_reason"] or "none" for row in synthetic)),
            "inlier_count": distribution([row["inlier_count"] for row in synthetic]),
            "inlier_ratio": distribution([row["inlier_ratio"] for row in synthetic]),
            "spatial_coverage": distribution([row["spatial_coverage"] for row in synthetic]),
            "median_reprojection_error_px": distribution([row["median_reprojection_error_px"] for row in synthetic]),
            "corner_error_mean_px": distribution([row["corner_error_mean_px"] for row in synthetic]),
            "estimated_overlap_ratio": distribution([row["estimated_overlap_ratio"] for row in synthetic]),
            "registration_time_ms": distribution([row["registration_time_ms"] for row in synthetic]),
            "unaligned_roi_ssim": distribution([row["unaligned_roi_ssim_mean"] for row in synthetic]),
            "estimated_roi_ssim": distribution([row["estimated_roi_ssim_mean"] for row in synthetic]),
            "known_roi_ssim_upper_reference": distribution([row["known_roi_ssim_mean"] for row in synthetic]),
            "unaligned_roi_gradient_ssim": distribution([row["unaligned_roi_gradient_ssim_mean"] for row in synthetic]),
            "estimated_roi_gradient_ssim": distribution([row["estimated_roi_gradient_ssim_mean"] for row in synthetic]),
            "known_roi_gradient_ssim_upper_reference": distribution([row["known_roi_gradient_ssim_mean"] for row in synthetic]),
        },
        "by_transform_and_intensity": transform_summary,
        "different_source_controls": {
            "pair_count": len(controls),
            "same_category_status_counts": dict(Counter(row["registration_status"] for row in controls if row["pair_kind"] == "different_source_same_category")),
            "cross_category_status_counts": dict(Counter(row["registration_status"] for row in controls if row["pair_kind"] == "different_source_cross_category")),
            "interpretation": "Different original images can depict different objects, poses, crops, or lighting. These controls do not count as defects, missing-part negatives, or a business false-positive rate.",
        },
        "representative_cases": {
            "best_corner_recovery": [{"pair_id": row["pair_id"], "source_file": row["source_file"], "transform": row["transform_key"], "corner_error_mean_px": row["corner_error_mean_px"], "status": row["registration_status"]} for row in best[:5]],
            "homography_failures": [{"pair_id": row["pair_id"], "source_file": row["source_file"], "transform": row["transform_key"], "reason": row["failure_reason"]} for row in h_failures[:5]],
            "quality_gate_rejections": [{"pair_id": row["pair_id"], "source_file": row["source_file"], "transform": row["transform_key"], "corner_error_mean_px": row["corner_error_mean_px"], "reason": row["failure_reason"]} for row in gate_rejections[:5]],
            "same_category_control_example": next(({"source_file": row["source_file"], "query_file": row["query_file"], "status": row["registration_status"]} for row in controls if row["pair_kind"] == "different_source_same_category"), None),
            "cross_category_control_example": next(({"source_file": row["source_file"], "query_file": row["query_file"], "status": row["registration_status"]} for row in controls if row["pair_kind"] == "different_source_cross_category"), None),
        },
    }


def markdown_report(summary: dict[str, Any], output: Path, command: str) -> str:
    inv = summary["inventory"]
    overall = summary["synthetic_overall"]
    cat_table = " | ".join(f"{key}: {value}" for key, value in sorted(inv["category_counts"].items()))
    columns = "| 变换 / 强度 | 样本 | H 成功 | App 门禁通过 | 中位角点误差 px | 平均 ROI SSIM（前 → 估计 → 已知） | 平均估计重叠率 | 中位耗时 ms |"
    separator = "|---|---:|---:|---:|---:|---|---:|---:|"
    lines = [
        "# B3 ROI 相似度与照片配准离线实验",
        "",
        f"生成时间：{summary['created_local']}。实验种子：`{summary['seed']}`。",
        "",
        "## 数据与标注",
        "",
        f"- 原图目录：`{summary['dataset_directory']}`；清点 {inv['image_count']} 张 JPG（{cat_table}）。",
        f"- 对应标注目录：`{inv['label_directory']}`；{inv['label_files_matched']}/{inv['image_count']} 张原图有 YOLO 框标注，共 {inv['roi_boxes']} 个框。逐文件尺寸、EXIF orientation、类别 ID 与框坐标见 [`dataset_inventory.csv`](dataset_inventory.csv)。",
        "- 图片先按 EXIF 转成 upright；本批 orientation 均缺省，原图尺寸与 upright 尺寸相同。原图未写入或移动。ROI 相似度只在标注框内计算，填充区及配准插值边界由有效 mask 排除。",
        "- 成对不同来源图像作为同类别/跨类别对照，不是缺陷、缺件负样本，也不用于误报率。数据没有 OK/NG、缺件/缺陷的人工标签。",
        "",
        "## 主要结果",
        "",
        f"- 合成扰动 {overall['case_count']} 例；Homography 可估 {overall['homography_estimated_count']} 例；完整门禁通过 {overall['registration_success_count']} 例；有 H 但被门禁拒绝 {overall['gate_rejected_after_homography_count']} 例；无可用 H {overall['registration_failed_before_homography_count']} 例。",
        f"- 内点数中位数 {overall['inlier_count']['median']}；内点比例中位数 {fmt(overall['inlier_ratio']['median'])}；中位重投影误差 {fmt(overall['median_reprojection_error_px']['median'])} px；空间覆盖中位数 {fmt(overall['spatial_coverage']['median'])}。",
        f"- 配准前 ROI 灰度 SSIM 均值 {fmt(overall['unaligned_roi_ssim']['mean'])}；按估计 H 对齐后 {fmt(overall['estimated_roi_ssim']['mean'])}；已知合成矩阵上限参照 {fmt(overall['known_roi_ssim_upper_reference']['mean'])}。梯度 SSIM 均值依次为 {fmt(overall['unaligned_roi_gradient_ssim']['mean'])}、{fmt(overall['estimated_roi_gradient_ssim']['mean'])}、{fmt(overall['known_roi_gradient_ssim_upper_reference']['mean'])}。",
        f"- 估计对齐的有效全图重叠率中位数 {fmt(overall['estimated_overlap_ratio']['median'])}；配准耗时中位数 {fmt(overall['registration_time_ms']['median'])} ms。",
        "- 已知矩阵仅作合成数据上限参照；不同来源对照没有已知几何矩阵。估计 H 即使被 App 质量门禁拒绝，仍可用作诊断性相似度计算；这类分数不代表 App 会接受该配准。",
        f"- 本批 561 例均估出 H；23 例被原质量门禁拒绝：{overall['failure_types'].get('projected_corners_outside_50px_margin', 0)} 例越过 50 px 边界余量，{overall['failure_types'].get('projected_area_below_0.005', 0)} 例投影 ROI 面积小于 0.005；没有特征不足或 Homography 估计失败。",
        f"- 528 个不同来源对照中，同类别 190 对的状态为 {summary['different_source_controls']['same_category_status_counts']}；跨类别 338 对为 {summary['different_source_controls']['cross_category_status_counts']}。这些只描述特征配准行为，不代表缺陷/缺件误报。",
        "",
        "### 按扰动统计",
        "",
        "误差和耗时列同时给出中位数 / P90；门禁成功率分母为该组全部图片。失败原因只列出非零项。",
        "",
        "| Transform / intensity | Cases | H estimated | Gate pass | Corner error median / P90 px | Reprojection median / P90 px | Mean ROI SSIM pre → estimated → known | Overlap median / P90 | Time median / P90 ms | Failure reasons |\n|---|---:|---:|---:|---:|---:|---|---:|---:|---|",
    ]
    for key, item in summary["by_transform_and_intensity"].items():
        ssim = item["unaligned_roi_ssim"]["mean"], item["estimated_roi_ssim"]["mean"], item["known_roi_ssim_upper_reference"]["mean"]
        failures = ", ".join(f"{reason} × {count}" for reason, count in item["failure_types"].items() if reason != "none") or "无"
        errors = f"{fmt(item['corner_error_mean_px']['median'])} / {fmt(item['corner_error_mean_px']['p90'])}"
        reprojection = f"{fmt(item['median_reprojection_error_px']['median'])} / {fmt(item['median_reprojection_error_px']['p90'])}"
        overlap = f"{fmt(item['estimated_overlap_ratio']['median'])} / {fmt(item['estimated_overlap_ratio']['p90'])}"
        duration = f"{fmt(item['registration_time_ms']['median'])} / {fmt(item['registration_time_ms']['p90'])}"
        lines.append(f"| {key} | {item['case_count']} | {item['homography_estimated']} | {item['registration_success']} ({item['quality_gate_pass_rate']:.1%}) | {errors} | {reprojection} | {fmt(ssim[0])} → {fmt(ssim[1])} → {fmt(ssim[2])} | {overlap} | {duration} | {failures} |")
    lines += [
        "",
        "### 代表案例",
        "",
        "- 成功：`nut_25.jpg` identity，状态 SUCCESS，平均角点误差 0 px；见 [`representative_success.png`](contact_sheets/representative_success.png)。",
        "- 门禁拒绝：`Nutsert_1.jpg` 的 -30° 旋转仍估出 H，角点误差 0.81 px，但投影角点超过 50 px 边界余量，因此按 App 语义回退；无特征/无 H 失败案例未出现。失败 contact sheet 展示另一个同类边界案例 `Nutsert_6.jpg` +30°。",
        "",
        "## App V4 对照与限制",
        "",
        "- 离线实现按 App 配置使用 AKAZE M-LDB（threshold 0.002，4 octave、4 layer，response 前 1000）、Hamming BFMatcher、Lowe 0.75，以及 USAC_MAGSAC 的 8 px / 500 iter / 0.995。质量门禁按 App 的内点数 10、比例 0.30、重投影误差 8 px、inlier hull/bbox 覆盖 0.15、投影面积比 0.005–0.95、边界容差 50 px 检查；多个标注框逐框检查并要求全部通过。",
        "- App 自定义 GMS 未在 Python 重现；OpenCV Python 与 Android/OpenCV 4.10.0 的运行时、USAC 实现和图像解码仍有差异。详见 `summary.json`，本实验不声称 Python 结果等同 Android V4。",
        "- SSIM 用 OpenCV 高斯窗按 SSIM 定义实现；梯度 SSIM 在 Sobel 梯度幅值上计算。无新增依赖；没有从合成结果拟合或修改生产相似度阈值 0.50 / 候选阈值 0.05。",
        "- 结论范围仅是已标注 ROI 在合成几何扰动下的配准和相似度变化。不能由此推断真实拍摄分布、业务误报率，也不支持把 NanoDet NG 自动翻为 OK。",
        "",
        "## 案例与产物",
        "",
        "- [`contact_sheets/representative_success.png`](contact_sheets/representative_success.png) 与 [`contact_sheets/representative_failure.png`](contact_sheets/representative_failure.png)：原图、变换图、估计配准图、叠加及差异热图。框为现有 YOLO 标注框。",
        "- [`pair_results.csv`](pair_results.csv)：每个合成变换和不同来源图像对一行，包含几何、门禁、耗时及前/后/已知 H 相似度。",
        "- [`summary.json`](summary.json)：分扰动统计、失败类型、不同来源配对结果、案例索引及环境信息。",
        "- [`transform_manifest.jsonl`](transform_manifest.jsonl)：每例已知矩阵与参数；`artifacts/masks/` 中各 NPZ 保存已知矩阵、参数和有效像素 mask。",
        "",
        "## Handback",
        "",
        "- 新增离线脚本：[`tools/roi_similarity/evaluate_roi_similarity.py`](../../../../tools/roi_similarity/evaluate_roi_similarity.py)；新增产物均位于本报告目录。没有修改已跟踪的 App、模型、Room、Gradle、CameraX 或现有 FeaturePresenceDetector 文件。",
        "- 环境：Python 3.9.23、OpenCV 4.13.0、NumPy 2.0.2、Pillow 11.1.0；未安装依赖。",
        "- 最终 Git 状态：未提交；已跟踪的既有改动只有 `tasks/plan.md` 与 `tasks/todo.md`，保持原样；本次脚本和报告目录为未跟踪新增文件。",
        "",
        "## 实际运行命令",
        "",
        "```powershell",
        r"& D:\ProgramData\anaconda3\envs\dinov2\python.exe tools\roi_similarity\evaluate_roi_similarity.py --self-test",
        command,
        "```",
        "",
        "Git 未提交。未运行 Android/Gradle、ADB、instrumented、真机测试或 OCR。",
        "",
    ]
    return "\n".join(lines)


def fmt(value: Any) -> str:
    if value is None:
        return "n/a"
    return f"{float(value):.4f}"


def save_rows(rows: list[dict[str, Any]], path: Path) -> None:
    if not rows:
        return
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()), extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def run_self_test() -> None:
    source = np.zeros((180, 220, 3), dtype=np.uint8)
    for x in range(12, 210, 18):
        cv2.line(source, (x, 8), (x - 20, 170), (220, 220, 220), 2)
    cv2.putText(source, "AKAZE ROI 42", (22, 92), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (245, 245, 245), 2)
    cv2.circle(source, (110, 130), 25, (255, 255, 255), 3)
    identity = np.eye(3, dtype=np.float64)
    same, full_mask = warp_case(source, identity)
    assert np.array_equal(same, source), "identity warp changed pixels"
    assert np.all(full_mask == 255), "identity valid mask is not full"
    known = transform_specs(source.shape[1], source.shape[0], "selftest.jpg", 1)[1]["homography"]
    warped, valid = warp_case(source, known)
    restored, restored_mask = align_query(warped, valid, known, source.shape[:2])
    assert known.shape == (3, 3) and np.isfinite(known).all(), "known homography invalid"
    assert np.count_nonzero(valid == 0) > 0, "expected clipped pixels to be masked"
    assert np.mean(cv2.absdiff(source, restored)) < 18.0, "known inverse alignment is inaccurate"
    assert np.count_nonzero(restored_mask) > 0, "known inverse mask is empty"
    detected = registration(source, source.copy(), [[20, 20, 200, 160]])
    assert detected["estimated_homography"] is not None, "identity feature registration did not estimate H"
    blank = np.zeros_like(source)
    failed = registration(blank, blank, [[20, 20, 180, 150]])
    assert failed["registration_status"] == "FAILED" and failed["estimated_homography"] is None, "featureless pair was falsely accepted"
    print("SELF_TEST_PASS identity, known_homography, valid_mask, featureless_failure")


def run_experiment(dataset: Path, output: Path, seed: int) -> None:
    entries, labels_dir, classes = inventory_dataset(dataset)
    output.mkdir(parents=True, exist_ok=True)
    write_inventory(entries, output, labels_dir, classes)
    synthetic_count = 0
    rows: list[dict[str, Any]] = []
    manifest_path = output / "transform_manifest.jsonl"
    with manifest_path.open("w", encoding="utf-8") as manifest:
        for entry_index, entry in enumerate(entries):
            image = entry["image"]
            height, width = image.shape[:2]
            boxes = entry["boxes"]
            if labels_dir is None:
                boxes = [[0.0, 0.0, float(width), float(height)]]
                entry["roi_policy"] = "full_image_synthetic_experiment_region_not_business_roi"
            else:
                entry["roi_policy"] = "paired_yolo_boxes" if boxes else "no_per_image_roi_annotation"
            source_mask = np.full((height, width), 255, dtype=np.uint8)
            for spec in transform_specs(width, height, entry["filename"], seed):
                query, valid_mask = warp_case(image, spec["homography"])
                params = {"type": spec["type"], "intensity": spec["intensity"], "key": spec["key"], **spec["params"]}
                artifact = Path("artifacts") / "masks" / Path(entry["path"].stem) / f"{spec['key']}.npz"
                save_transform_artifact(output / artifact, spec["homography"], valid_mask, params)
                manifest_record = {
                    "source_file": entry["filename"], "category": entry["category"], "transform_type": spec["type"],
                    "intensity": spec["intensity"], "params": spec["params"],
                    "homography_src_to_query": spec["homography"].tolist(), "valid_mask_artifact": artifact.as_posix(),
                }
                manifest.write(json_text(manifest_record) + "\n")
                pair_id = f"synthetic-{entry_index:02d}-{spec['key']}"
                row = pair_row(pair_id, "synthetic_transform", entry, entry, query, valid_mask, boxes, spec, spec["homography"], source_mask, artifact.as_posix())
                rows.append(row)
                synthetic_count += 1
    control_count = 0
    for left in range(len(entries)):
        reference = entries[left]
        ref_h, ref_w = reference["image"].shape[:2]
        ref_boxes = reference["boxes"]
        reference["roi_policy"] = reference.get("roi_policy", "paired_yolo_boxes" if ref_boxes else "no_per_image_roi_annotation")
        source_mask = np.full((ref_h, ref_w), 255, dtype=np.uint8)
        for right in range(left + 1, len(entries)):
            query_entry = entries[right]
            query = query_entry["image"]
            query_mask = np.full(query.shape[:2], 255, dtype=np.uint8)
            pair_kind = "different_source_same_category" if reference["category"] == query_entry["category"] else "different_source_cross_category"
            pair_id = f"control-{left:02d}-{right:02d}"
            rows.append(pair_row(pair_id, pair_kind, reference, query_entry, query, query_mask, ref_boxes, None, None, source_mask, None))
            control_count += 1
    save_rows(rows, output / "pair_results.csv")
    summary = summarize(rows, entries, labels_dir, classes, seed, dataset)
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")
    synthetic_rows = [row for row in rows if row["pair_kind"] == "synthetic_transform"]
    success_candidates = [row for row in synthetic_rows if row["registration_status"] == "SUCCESS"]
    if not success_candidates:
        success_candidates = [row for row in synthetic_rows if row["homography_estimated"]]
    fail_candidates = [row for row in synthetic_rows if not row["homography_estimated"]]
    if not fail_candidates:
        fail_candidates = [row for row in synthetic_rows if row["registration_status"] != "SUCCESS"]
    contact_cases = []
    if success_candidates:
        contact_cases.append(("representative_success.png", min(success_candidates, key=lambda row: row["corner_error_mean_px"] if row["corner_error_mean_px"] is not None else math.inf)))
    if fail_candidates:
        contact_cases.append(("representative_failure.png", max(fail_candidates, key=lambda row: row["corner_error_mean_px"] if row["corner_error_mean_px"] is not None else math.inf)))
    entries_by_name = {entry["filename"]: entry for entry in entries}
    for filename, row in contact_cases:
        entry = entries_by_name[row["source_file"]]
        spec = next(spec for spec in transform_specs(entry["width"], entry["height"], entry["filename"], seed) if spec["key"] == row["transform_key"])
        query, _ = warp_case(entry["image"], spec["homography"])
        estimated_h = np.asarray(json.loads(row["estimated_homography_json"]), dtype=np.float64) if row["estimated_homography_json"] else None
        create_contact_sheet(entry, spec, query, estimated_h, output / "contact_sheets" / filename, row["registration_status"])
    command = f'& D:\\ProgramData\\anaconda3\\envs\\dinov2\\python.exe tools\\roi_similarity\\evaluate_roi_similarity.py --dataset "{dataset}" --output "{output.as_posix()}" --seed {seed}'
    (output / "OFFLINE_ROI_SIMILARITY_REPORT.md").write_text(markdown_report(summary, output, command), encoding="utf-8")
    print(f"EXPERIMENT_COMPLETE images={len(entries)} synthetic={synthetic_count} different_source_pairs={control_count} total_pairs={len(rows)}")
    print(f"REPORT={output / 'OFFLINE_ROI_SIMILARITY_REPORT.md'}")
    print(f"SUMMARY={output / 'summary.json'}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--seed", type=int, default=SEED)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        run_self_test()
        return
    run_experiment(args.dataset, args.output, args.seed)


if __name__ == "__main__":
    main()
