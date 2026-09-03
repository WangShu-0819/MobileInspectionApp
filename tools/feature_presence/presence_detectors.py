"""Small, explainable offline presence detectors.

Inputs are already-localized ROI images. This module never searches a whole
DCIM image and never turns a missing ROI into an absent target.
"""

from __future__ import annotations

import json
import math
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

import cv2
import numpy as np


ALGORITHM_VERSION = "presence-offline.2"
TEAL_MASK = {
    "hueRangeOpenCv": [75, 105],
    "saturationMin": 80,
    "valueMin": 50,
    "dilateKernel": [3, 3],
    "dilateIterations": 1,
}


@dataclass
class DetectionResult:
    status: str
    score: float | None
    message: str
    metrics: dict[str, float] = field(default_factory=dict)
    boxes: list[list[int]] = field(default_factory=list)
    algorithm: str = ""
    algorithm_version: str = ALGORITHM_VERSION
    duration_ms: int = 0
    debug_path: str | None = None

    def as_dict(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "score": self.score,
            "message": self.message,
            "metrics": self.metrics,
            "boxes": self.boxes,
            "algorithm": self.algorithm,
            "algorithmVersion": self.algorithm_version,
            "durationMs": self.duration_ms,
            "debugPath": self.debug_path,
        }


def _result(
    algorithm: str,
    started: float,
    status: str,
    score: float | None,
    message: str,
    metrics: dict[str, float] | None = None,
    boxes: list[list[int]] | None = None,
) -> DetectionResult:
    return DetectionResult(
        status=status,
        score=None if score is None else float(np.clip(score, 0.0, 1.0)),
        message=message,
        metrics=metrics or {},
        boxes=boxes or [],
        algorithm=algorithm,
        duration_ms=max(0, round((time.perf_counter() - started) * 1000)),
    )


def validate_image(image: np.ndarray | None) -> str | None:
    if image is None or not isinstance(image, np.ndarray) or image.size == 0:
        return "image is missing or empty"
    if image.dtype != np.uint8:
        return "image must use uint8 pixels"
    if image.ndim not in (2, 3) or image.shape[0] < 8 or image.shape[1] < 8:
        return "image is too small or has an invalid shape"
    if image.ndim == 3 and image.shape[2] not in (1, 3, 4):
        return "image has an unsupported channel count"
    return None


def crop_normalized(image: np.ndarray, roi: list[float] | None) -> tuple[np.ndarray | None, str | None]:
    """Crop a strictly valid normalized ROI, preserving the image coordinate contract."""

    error = validate_image(image)
    if error:
        return None, error
    if roi is None or not isinstance(roi, (list, tuple, np.ndarray)) or len(roi) != 4:
        return None, "target ROI is not annotated"
    try:
        left, top, right, bottom = (float(value) for value in roi)
    except (TypeError, ValueError):
        return None, "ROI values are not numeric"
    if not (0.0 <= left < right <= 1.0 and 0.0 <= top < bottom <= 1.0):
        return None, "ROI is outside normalized image bounds"
    height, width = image.shape[:2]
    x0, y0 = int(round(left * width)), int(round(top * height))
    x1, y1 = int(round(right * width)), int(round(bottom * height))
    if x1 <= x0 or y1 <= y0:
        return None, "ROI has zero area"
    return image[y0:y1, x0:x1].copy(), None


def _bgr(image: np.ndarray) -> np.ndarray:
    if image.ndim == 2:
        return cv2.cvtColor(image, cv2.COLOR_GRAY2BGR)
    if image.shape[2] == 1:
        return cv2.cvtColor(image, cv2.COLOR_GRAY2BGR)
    if image.shape[2] == 4:
        return cv2.cvtColor(image, cv2.COLOR_BGRA2BGR)
    return image


def teal_mask(image: np.ndarray) -> tuple[np.ndarray, np.ndarray, dict[str, float]]:
    hsv = cv2.cvtColor(_bgr(image), cv2.COLOR_BGR2HSV)
    lower = np.array(
        [TEAL_MASK["hueRangeOpenCv"][0], TEAL_MASK["saturationMin"], TEAL_MASK["valueMin"]],
        dtype=np.uint8,
    )
    upper = np.array([TEAL_MASK["hueRangeOpenCv"][1], 255, 255], dtype=np.uint8)
    raw = cv2.inRange(hsv, lower, upper)
    kernel = np.ones(tuple(TEAL_MASK["dilateKernel"]), dtype=np.uint8)
    dilated = cv2.dilate(raw, kernel, iterations=TEAL_MASK["dilateIterations"])
    total = float(dilated.size)
    return raw, dilated, {
        "tealRawCoverage": float(np.count_nonzero(raw) / total),
        "tealDilatedCoverage": float(np.count_nonzero(dilated) / total),
    }


def masked_gray(image: np.ndarray) -> tuple[np.ndarray, np.ndarray, dict[str, float]]:
    bgr = _bgr(image)
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    _, mask, mask_metrics = teal_mask(bgr)
    valid = mask == 0
    fill_value = int(np.median(gray[valid])) if np.any(valid) else int(np.median(gray))
    cleaned = gray.copy()
    cleaned[~valid] = fill_value
    return cleaned, mask, mask_metrics


def _quality(gray: np.ndarray) -> float:
    return float(np.clip(np.var(gray) / 1800.0, 0.0, 1.0))


def _circle_masks(shape: tuple[int, int], cx: float, cy: float, radius: float) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    yy, xx = np.ogrid[: shape[0], : shape[1]]
    distance = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
    return distance <= radius * 0.72, (distance >= radius * 0.78) & (distance <= radius * 0.98), distance <= radius


def _circle_geometry(gray: np.ndarray, cx: float, cy: float, radius: float) -> tuple[float, float, float]:
    """Return angular edge coverage, radial consistency, and combined roundness."""

    edges = cv2.Canny(gray, 40, 120)
    angles = np.linspace(0.0, 2.0 * math.pi, 72, endpoint=False)
    supports: list[float] = []
    edge_radii: list[float] = []
    for angle in angles:
        sample_radii = np.arange(max(1.0, radius - 4.0), radius + 5.0)
        xs = np.clip(np.rint(cx + sample_radii * math.cos(angle)).astype(int), 0, gray.shape[1] - 1)
        ys = np.clip(np.rint(cy + sample_radii * math.sin(angle)).astype(int), 0, gray.shape[0] - 1)
        present = edges[ys, xs] > 0
        supports.append(float(np.mean(present)))
        if np.any(present):
            edge_radii.append(float(sample_radii[np.argmax(present)]))
    angular_coverage = float(np.mean(np.asarray(supports) > 0.20)) if supports else 0.0
    radial_spread = float(np.std(edge_radii) / max(radius, 1.0)) if edge_radii else 1.0
    radial_consistency = float(np.clip(1.0 - radial_spread / 0.12, 0.0, 1.0))
    roundness = float(np.clip(0.60 * angular_coverage + 0.40 * radial_consistency, 0.0, 1.0))
    return angular_coverage, radial_consistency, roundness


def _periodic_ring_metrics(gray: np.ndarray, cx: float, cy: float, radius: float) -> tuple[float, float, float, float]:
    inner, ring, whole = _circle_masks(gray.shape, cx, cy, radius)
    if not np.any(whole):
        return 0.0, 0.0, 0.0, 0.0
    edge = cv2.Canny(gray, 40, 120)
    edge_density = float(np.mean(edge[whole] > 0))
    ring_value = float(np.median(gray[ring])) if np.any(ring) else float(np.median(gray[whole]))
    inner_dark_ratio = float(np.mean(gray[inner] < ring_value * 0.82)) if np.any(inner) else 0.0
    radii = np.linspace(max(2.0, radius * 0.12), max(3.0, radius * 0.88), 24)
    yy, xx = np.ogrid[: gray.shape[0], : gray.shape[1]]
    distance = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
    profile = [
        float(np.median(gray[(distance >= sample_radius - 1.0) & (distance <= sample_radius + 1.0)]))
        for sample_radius in radii
    ]
    differences = np.abs(np.diff(np.asarray(profile, dtype=np.float32)))
    periodicity = float(np.clip(np.mean(differences) / 36.0, 0.0, 1.0)) if differences.size else 0.0
    transition_limit = max(12.0, float(np.median(differences) * 1.5)) if differences.size else 12.0
    transition_score = float(np.clip(np.count_nonzero(differences > transition_limit) / 5.0, 0.0, 1.0))
    return edge_density, inner_dark_ratio, periodicity, transition_score


class ThreadPresenceDetector:
    algorithm = "ThreadPresenceDetector"

    def detect(
        self,
        image: np.ndarray,
        config: dict[str, Any] | None = None,
        debug_path: Path | None = None,
    ) -> DetectionResult:
        started = time.perf_counter()
        config = config or {}
        invalid = validate_image(image)
        if invalid:
            return _result(self.algorithm, started, "ERROR", None, invalid)
        gray, mask, mask_metrics = masked_gray(image)
        if min(gray.shape) < 24:
            return _result(self.algorithm, started, "ERROR", None, "ROI is too small for circle and texture evidence")
        work = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(gray)
        work = cv2.GaussianBlur(work, (3, 3), 0)
        height, width = gray.shape[:2]
        min_dim = min(height, width)
        min_radius = max(3, int(min_dim * float(config.get("minRadiusRatio", 0.12))))
        max_radius = max(min_radius + 1, int(min_dim * float(config.get("maxRadiusRatio", 0.48))))
        circles = cv2.HoughCircles(
            work,
            cv2.HOUGH_GRADIENT,
            dp=float(config.get("houghDp", 1.2)),
            minDist=max(5.0, min_dim * float(config.get("minDistRatio", 0.25))),
            param1=float(config.get("houghParam1", 70.0)),
            param2=float(config.get("circleParam2", max(8.0, min_dim * 0.12))),
            minRadius=min_radius,
            maxRadius=max_radius,
        )
        candidates: list[dict[str, float]] = []
        if circles is not None:
            for cx, cy, radius in np.round(circles[0], 2):
                if not (radius > 0 and radius * 1.05 < min(cx, cy, width - cx, height - cy)):
                    continue
                angular_coverage, radial_consistency, geometry = _circle_geometry(gray, cx, cy, radius)
                edge_density, dark_ratio, periodicity, transition_score = _periodic_ring_metrics(gray, cx, cy, radius)
                texture = float(
                    np.clip(
                        0.34 * np.clip(edge_density * 7.0, 0.0, 1.0)
                        + 0.46 * periodicity
                        + 0.20 * transition_score,
                        0.0,
                        1.0,
                    )
                )
                candidates.append(
                    {
                        "cx": float(cx),
                        "cy": float(cy),
                        "radius": float(radius),
                        "geometry": geometry,
                        "angularCoverage": angular_coverage,
                        "radialConsistency": radial_consistency,
                        "darkRatio": dark_ratio,
                        "texture": texture,
                    }
                )
        if not candidates:
            metrics = {**mask_metrics, "circleCandidates": 0.0, "quality": _quality(gray)}
            return _result(self.algorithm, started, "FAIL", 0.0, "no circular aperture candidate", metrics)

        # ponytail: HoughCircles is intentionally paired with internal ring/texture tests;
        # plain circular holes are not enough, with learned thread texture deferred.
        candidates.sort(key=lambda item: 0.30 * item["geometry"] + 0.25 * item["darkRatio"] + 0.30 * item["texture"] + 0.15 * _quality(gray), reverse=True)
        best = candidates[0]
        cx, cy, radius = best["cx"], best["cy"], best["radius"]
        quality = _quality(gray)
        sharpness = float(np.clip(cv2.Laplacian(gray, cv2.CV_64F).var() / float(config.get("sharpnessScale", 1000.0)), 0.0, 1.0))
        score = 0.30 * best["geometry"] + 0.22 * best["darkRatio"] + 0.33 * best["texture"] + 0.15 * quality
        metrics = {
            **mask_metrics,
            "circleCandidates": float(len(candidates)),
            "circleCenterX": cx,
            "circleCenterY": cy,
            "circleRadius": radius,
            "circleGeometryScore": best["geometry"],
            "circleAngularCoverage": best["angularCoverage"],
            "circleRadialConsistency": best["radialConsistency"],
            "innerDarkRatio": best["darkRatio"],
            "texturePeriodicityScore": best["texture"],
            "quality": quality,
            "sharpness": sharpness,
        }
        texture_min = float(config.get("textureMin", 0.18))
        geometry_min = float(config.get("geometryMin", 0.30))
        pass_threshold = float(config.get("passThreshold", 0.50))
        if quality < float(config.get("qualityReviewMin", 0.04)) or sharpness < float(config.get("sharpnessReviewMin", 0.03)):
            status, message = "REVIEW", "circular evidence exists but image quality is low"
        elif best["geometry"] < geometry_min or best["texture"] < texture_min:
            status, message = "REVIEW", "circular aperture found but thread texture evidence is insufficient"
        elif score >= pass_threshold:
            status, message = "PASS", "circular aperture and internal periodic texture are present"
        else:
            status, message = "REVIEW", "thread evidence is between configured thresholds"
        result = _result(
            self.algorithm,
            started,
            status,
            score,
            message,
            metrics,
            [[round(cx - radius), round(cy - radius), round(2 * radius), round(2 * radius)]],
        )
        if debug_path:
            result.debug_path = str(debug_path)
            self.write_debug(gray, mask, result, debug_path, (cx, cy, radius))
        return result

    @staticmethod
    def write_debug(gray: np.ndarray, mask: np.ndarray, result: DetectionResult, path: Path, circle: tuple[float, float, float]) -> None:
        canvas = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
        cx, cy, radius = circle
        cv2.circle(canvas, (round(cx), round(cy)), round(radius), (0, 220, 0), 2)
        cv2.circle(canvas, (round(cx), round(cy)), round(radius * 0.72), (255, 180, 0), 1)
        canvas[mask > 0] = (180, 0, 255)
        cv2.putText(canvas, f"{result.status} {result.score:.3f}", (6, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 255), 1)
        path.parent.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(path), canvas)


def _box_iou(first: list[int], second: list[int]) -> float:
    ax, ay, aw, ah = first
    bx, by, bw, bh = second
    x0, y0 = max(ax, bx), max(ay, by)
    x1, y1 = min(ax + aw, bx + bw), min(ay + ah, by + bh)
    intersection = max(0, x1 - x0) * max(0, y1 - y0)
    union = aw * ah + bw * bh - intersection
    return intersection / union if union else 0.0


def _center_hole_evidence(gray: np.ndarray, box: list[int], config: dict[str, Any]) -> tuple[float, float, float, float]:
    """Find a small circular interior and return score, radius, edge and contrast."""

    x, y, box_width, box_height = box
    crop = gray[y : y + box_height, x : x + box_width]
    diameter = min(box_width, box_height)
    if diameter < 16:
        return 0.0, 0.0, 0.0, 0.0
    enhanced = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(4, 4)).apply(crop)
    circles = cv2.HoughCircles(
        cv2.GaussianBlur(enhanced, (3, 3), 0),
        cv2.HOUGH_GRADIENT,
        dp=float(config.get("holeHoughDp", 1.0)),
        minDist=max(4.0, diameter * 0.25),
        param1=float(config.get("holeHoughParam1", 60.0)),
        param2=float(config.get("holeHoughParam2", 12.0)),
        minRadius=max(2, round(diameter * float(config.get("holeMinRadiusRatio", 0.08)))),
        maxRadius=max(3, round(diameter * float(config.get("holeMaxRadiusRatio", 0.38)))),
    )
    edge = cv2.Canny(crop, 40, 120)
    center = np.array([box_width / 2.0, box_height / 2.0], dtype=np.float32)
    options: list[tuple[float, float, float, float]] = []
    if circles is not None:
        for cx, cy, radius in np.round(circles[0], 2):
            point = np.array([cx, cy], dtype=np.float32)
            if np.linalg.norm(point - center) > diameter * float(config.get("holeCenterDistanceRatio", 0.32)):
                continue
            yy, xx = np.ogrid[:crop.shape[0], :crop.shape[1]]
            distance = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
            inner = distance <= radius * 0.45
            ring = (distance >= radius * 0.72) & (distance <= radius * 1.05)
            if not np.any(inner) or not np.any(ring):
                continue
            inner_mean = float(np.mean(crop[inner]))
            ring_mean = float(np.mean(crop[ring]))
            dark_contrast = float(np.clip((ring_mean - inner_mean) / 90.0 + 0.50, 0.0, 1.0))
            ring_edge = float(np.mean(edge[ring] > 0))
            edge_score = float(np.clip(ring_edge / float(config.get("holeRingEdgeScale", 0.20)), 0.0, 1.0))
            center_score = float(np.clip(1.0 - np.linalg.norm(point - center) / max(diameter * 0.32, 1.0), 0.0, 1.0))
            score = float(np.clip(0.45 * dark_contrast + 0.40 * edge_score + 0.15 * center_score, 0.0, 1.0))
            options.append((score, float(radius), edge_score, dark_contrast))
    if not options:
        return 0.0, 0.0, 0.0, 0.0
    return max(options, key=lambda item: item[0])


class NutPresenceDetector:
    algorithm = "NutPresenceDetector"

    def detect(
        self,
        image: np.ndarray,
        config: dict[str, Any] | None = None,
        debug_path: Path | None = None,
    ) -> DetectionResult:
        started = time.perf_counter()
        config = config or {}
        invalid = validate_image(image)
        if invalid:
            return _result(self.algorithm, started, "ERROR", None, invalid)
        gray, mask, mask_metrics = masked_gray(image)
        height, width = gray.shape[:2]
        if min(height, width) < 24:
            return _result(self.algorithm, started, "ERROR", None, "ROI is too small for polygon evidence")
        enhanced = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(gray)
        edges = cv2.Canny(enhanced, float(config.get("cannyLow", 50.0)), float(config.get("cannyHigh", 150.0)))
        threshold = cv2.threshold(enhanced, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)[1]
        threshold = cv2.morphologyEx(threshold, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
        candidates: list[dict[str, Any]] = []
        for contour in cv2.findContours(threshold, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)[0]:
            area = float(cv2.contourArea(contour))
            if area < max(20.0, height * width * float(config.get("minAreaRatio", 0.004))) or area > height * width * float(config.get("maxAreaRatio", 0.85)):
                continue
            perimeter = float(cv2.arcLength(contour, True))
            if perimeter <= 0:
                continue
            polygon = cv2.approxPolyDP(contour, float(config.get("epsilonRatio", 0.04)) * perimeter, True)
            vertices = len(polygon)
            if vertices < int(config.get("minVertices", 5)) or vertices > int(config.get("maxVertices", 7)):
                continue
            if not cv2.isContourConvex(polygon):
                continue
            x, y, box_width, box_height = cv2.boundingRect(polygon)
            if box_width <= 0 or box_height <= 0:
                continue
            aspect = min(box_width / box_height, box_height / box_width)
            if aspect < float(config.get("minAspectRatio", 0.62)):
                continue
            fill_ratio = area / float(box_width * box_height)
            if fill_ratio < float(config.get("minFillRatio", 0.45)) or fill_ratio > float(config.get("maxFillRatio", 0.98)):
                continue
            hull_area = float(cv2.contourArea(cv2.convexHull(contour)))
            solidity = area / hull_area if hull_area else 0.0
            if solidity < float(config.get("minSolidity", 0.82)):
                continue
            circularity = float(np.clip(4.0 * math.pi * area / (perimeter * perimeter), 0.0, 1.0))
            hole_score, hole_radius, hole_edge, hole_contrast = _center_hole_evidence(gray, [x, y, box_width, box_height], config)
            local_edges = edges[y : y + box_height, x : x + box_width]
            edge_density = float(np.mean(local_edges > 0)) if local_edges.size else 0.0
            vertex_score = 1.0 - min(abs(vertices - 6) / 2.0, 1.0)
            fill_score = float(np.clip(1.0 - abs(fill_ratio - 0.72) / 0.35, 0.0, 1.0))
            aspect_score = float(np.clip((aspect - float(config.get("minAspectRatio", 0.62))) / 0.38, 0.0, 1.0))
            geometry_score = float(np.clip(0.40 * vertex_score + 0.25 * fill_score + 0.20 * solidity + 0.15 * aspect_score, 0.0, 1.0))
            score = float(np.clip(0.58 * geometry_score + 0.18 * hole_score + 0.14 * circularity + 0.10 * np.clip(edge_density * 3.0, 0.0, 1.0), 0.0, 1.0))
            candidates.append(
                {
                    "contour": contour,
                    "box": [x, y, box_width, box_height],
                    "score": score,
                    "geometryScore": geometry_score,
                    "holeScore": hole_score,
                    "holeRadius": hole_radius,
                    "holeEdgeScore": hole_edge,
                    "holeContrastScore": hole_contrast,
                    "vertices": vertices,
                    "aspect": aspect,
                    "fillRatio": fill_ratio,
                    "solidity": solidity,
                    "circularity": circularity,
                    "edgeDensity": edge_density,
                }
            )
        candidates.sort(key=lambda item: item["score"], reverse=True)
        selected: list[dict[str, Any]] = []
        for candidate in candidates:
            if not any(_box_iou(candidate["box"], existing["box"]) > float(config.get("nmsIou", 0.45)) for existing in selected):
                selected.append(candidate)
        boxes = [item["box"] for item in selected]
        average_score = float(np.mean([item["score"] for item in selected])) if selected else 0.0
        metrics = {
            **mask_metrics,
            "candidateCountBeforeNms": float(len(candidates)),
            "candidateCount": float(len(selected)),
            "averageGeometryScore": float(np.mean([item["geometryScore"] for item in selected])) if selected else 0.0,
            "averageCenterHoleScore": float(np.mean([item["holeScore"] for item in selected])) if selected else 0.0,
            "minCenterHoleScore": float(min((item["holeScore"] for item in selected), default=0.0)),
            "averageCircularity": float(np.mean([item["circularity"] for item in selected])) if selected else 0.0,
            "averageEdgeDensity": float(np.mean([item["edgeDensity"] for item in selected])) if selected else 0.0,
            "quality": _quality(gray),
        }
        expected = config.get("expectedCount")
        if expected is None:
            status, message, score = "REVIEW", "expectedCount is not configured; candidate count is informational", average_score
        elif int(expected) != len(selected):
            status, message, score = "FAIL", f"expected {int(expected)} nut candidates, found {len(selected)}", average_score
        elif not selected or average_score < float(config.get("passThreshold", 0.55)) or min(item["holeScore"] for item in selected) < float(config.get("holeReviewMin", 0.05)):
            status, message, score = "REVIEW", "candidate count matches but polygon or center-hole quality is uncertain", average_score
        else:
            status, message, score = "PASS", "configured nut count and near-hexagonal geometry match", average_score
        result = _result(self.algorithm, started, status, score, message, metrics, boxes)
        if debug_path:
            result.debug_path = str(debug_path)
            self.write_debug(gray, mask, result, debug_path, selected)
        return result

    @staticmethod
    def write_debug(gray: np.ndarray, mask: np.ndarray, result: DetectionResult, path: Path, candidates: list[dict[str, Any]]) -> None:
        canvas = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
        for index, candidate in enumerate(candidates, start=1):
            cv2.drawContours(canvas, [candidate["contour"]], -1, (0, 220, 0), 2)
            x, y, box_width, box_height = candidate["box"]
            cv2.putText(canvas, f"{index}:{candidate['score']:.2f}", (x, max(12, y - 4)), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 180, 0), 1)
            cv2.rectangle(canvas, (x, y), (x + box_width, y + box_height), (255, 0, 0), 1)
        canvas[mask > 0] = (180, 0, 255)
        cv2.putText(canvas, f"{result.status} count={len(candidates)}", (6, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 255), 1)
        path.parent.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(path), canvas)


def _phash(image: np.ndarray) -> np.ndarray:
    small = cv2.resize(image, (32, 32), interpolation=cv2.INTER_AREA).astype(np.float32)
    low = cv2.dct(small)[:8, :8]
    return low > np.median(low[1:])


def _multi_scale_match(image: np.ndarray, template: np.ndarray, config: dict[str, Any]) -> tuple[float, float, list[int]]:
    """Return best normalized template score, scale and image-space box."""

    explicit_scales = config.get("matchScales")
    if isinstance(explicit_scales, (list, tuple)) and explicit_scales:
        scales = [float(value) for value in explicit_scales]
    else:
        start = float(config.get("matchScaleMin", 0.60))
        stop = float(config.get("matchScaleMax", 1.40))
        steps = max(2, int(config.get("matchScaleSteps", 9)))
        scales = np.linspace(start, stop, steps).tolist()
    best = (-1.0, 1.0, [])
    for scale in scales:
        if not math.isfinite(scale) or scale <= 0:
            continue
        scaled_width = max(8, round(template.shape[1] * scale))
        scaled_height = max(8, round(template.shape[0] * scale))
        if scaled_width > image.shape[1] or scaled_height > image.shape[0]:
            continue
        scaled = cv2.resize(template, (scaled_width, scaled_height), interpolation=cv2.INTER_AREA if scale < 1 else cv2.INTER_CUBIC)
        response = cv2.matchTemplate(image, scaled, cv2.TM_CCOEFF_NORMED)
        _, score, _, location = cv2.minMaxLoc(response)
        if not math.isfinite(score):
            continue
        if score > best[0]:
            best = (float(score), scale, [int(location[0]), int(location[1]), scaled_width, scaled_height])
    return best


def _feature_matches(template: np.ndarray, image: np.ndarray, config: dict[str, Any]) -> tuple[list[cv2.KeyPoint], list[cv2.KeyPoint], list[cv2.DMatch], float, float, list[int]]:
    akaze = cv2.AKAZE_create()
    keypoints_template, descriptors_template = akaze.detectAndCompute(template, None)
    keypoints_image, descriptors_image = akaze.detectAndCompute(image, None)
    good_matches: list[cv2.DMatch] = []
    inlier_ratio = 0.0
    coverage = 0.0
    inlier_box: list[int] = []
    if descriptors_template is None or descriptors_image is None or len(descriptors_template) < 2 or len(descriptors_image) < 2:
        return keypoints_template or [], keypoints_image or [], good_matches, inlier_ratio, coverage, inlier_box
    matcher = cv2.BFMatcher(cv2.NORM_HAMMING)
    pairs = matcher.knnMatch(descriptors_template, descriptors_image, k=2)
    ratio = float(config.get("loweRatio", 0.75))
    good_matches = [first for pair in pairs if len(pair) == 2 and (first := pair[0]).distance < ratio * pair[1].distance]
    if len(good_matches) < 3:
        return keypoints_template, keypoints_image, good_matches, inlier_ratio, coverage, inlier_box
    source = np.float32([keypoints_template[item.queryIdx].pt for item in good_matches]).reshape(-1, 1, 2)
    destination = np.float32([keypoints_image[item.trainIdx].pt for item in good_matches]).reshape(-1, 1, 2)
    _, inliers = cv2.estimateAffinePartial2D(
        source,
        destination,
        method=cv2.RANSAC,
        ransacReprojThreshold=float(config.get("ransacReprojThreshold", 5.0)),
    )
    if inliers is None:
        return keypoints_template, keypoints_image, good_matches, inlier_ratio, coverage, inlier_box
    inlier_mask = inliers.ravel() > 0
    inlier_ratio = float(np.mean(inlier_mask))
    points = destination[inlier_mask].reshape(-1, 2)
    if len(points) >= 3:
        hull = cv2.convexHull(points.astype(np.float32))
        coverage = float(np.clip(cv2.contourArea(hull) / max(image.shape[0] * image.shape[1], 1), 0.0, 1.0))
        x, y, width, height = cv2.boundingRect(hull.astype(np.float32))
        inlier_box = [int(x), int(y), int(width), int(height)]
    return keypoints_template, keypoints_image, good_matches, inlier_ratio, coverage, inlier_box


class FeaturePresenceDetector:
    algorithm = "FeaturePresenceDetector"

    def detect(
        self,
        image: np.ndarray,
        template: np.ndarray | None,
        config: dict[str, Any] | None = None,
        debug_path: Path | None = None,
    ) -> DetectionResult:
        started = time.perf_counter()
        config = config or {}
        invalid = validate_image(image)
        if invalid:
            return _result(self.algorithm, started, "ERROR", None, invalid)
        template_error = validate_image(template)
        if template_error:
            return _result(self.algorithm, started, "ERROR", None, "template is missing or invalid")
        gray, mask, mask_metrics = masked_gray(image)
        template_gray, _, _ = masked_gray(template)
        phash_similarity = 1.0 - float(np.mean(_phash(gray) != _phash(template_gray)))
        normalized_template = cv2.resize(template_gray, (128, 128), interpolation=cv2.INTER_AREA)
        normalized_image = cv2.resize(gray, (128, 128), interpolation=cv2.INTER_AREA)
        normalized_similarity = 1.0 - float(np.mean(np.abs(normalized_image.astype(np.float32) - normalized_template.astype(np.float32))) / 255.0)
        template_match_score, template_match_scale, template_box = _multi_scale_match(gray, template_gray, config)
        keypoints_template, keypoints_image, good_matches, inlier_ratio, coverage, inlier_box = _feature_matches(template_gray, gray, config)
        min_matches = int(config.get("minMatches", 6))
        match_score = float(np.clip(len(good_matches) / max(float(min_matches), 1.0), 0.0, 1.0))
        geometry_score = float(np.clip(0.55 * inlier_ratio + 0.45 * np.clip(coverage * 12.0, 0.0, 1.0), 0.0, 1.0))
        score = float(np.clip(0.20 * phash_similarity + 0.30 * max(template_match_score, 0.0) + 0.25 * match_score + 0.25 * geometry_score, 0.0, 1.0))
        metrics = {
            **mask_metrics,
            "phashSimilarity": phash_similarity,
            "normalizedTemplateSimilarity": normalized_similarity,
            "templateMatchScore": max(template_match_score, 0.0),
            "templateMatchScale": template_match_scale,
            "templateMatchX": float(template_box[0]) if template_box else -1.0,
            "templateMatchY": float(template_box[1]) if template_box else -1.0,
            "templateMatchWidth": float(template_box[2]) if template_box else 0.0,
            "templateMatchHeight": float(template_box[3]) if template_box else 0.0,
            "templateKeypoints": float(len(keypoints_template)),
            "imageKeypoints": float(len(keypoints_image)),
            "goodMatches": float(len(good_matches)),
            "inlierRatio": inlier_ratio,
            "projectedCoverage": coverage,
            "inlierBoxX": float(inlier_box[0]) if inlier_box else -1.0,
            "inlierBoxY": float(inlier_box[1]) if inlier_box else -1.0,
            "inlierBoxWidth": float(inlier_box[2]) if inlier_box else 0.0,
            "inlierBoxHeight": float(inlier_box[3]) if inlier_box else 0.0,
            "matchScore": match_score,
            "geometryScore": geometry_score,
        }
        # ponytail: pHash and matchTemplate are coarse filters; affine RANSAC is
        # only a lightweight consistency check, with learned descriptors deferred.
        if phash_similarity < float(config.get("phashFailMin", 0.35)) or template_match_score < float(config.get("templateMatchFailMin", 0.25)):
            status, message = "FAIL", "template coarse similarity is too low"
        elif len(good_matches) < min_matches or inlier_ratio < float(config.get("inlierRatioMin", 0.45)) or coverage < float(config.get("coverageMin", 0.01)):
            status, message = "REVIEW", "coarse similarity exists but AKAZE/RANSAC geometry is insufficient"
        elif score >= float(config.get("passThreshold", 0.62)):
            status, message = "PASS", "coarse similarity and AKAZE/RANSAC geometry agree"
        else:
            status, message = "REVIEW", "feature evidence is between configured thresholds"
        result = _result(self.algorithm, started, status, score, message, metrics, [template_box] if template_box else [])
        if debug_path:
            result.debug_path = str(debug_path)
            self.write_debug(gray, mask, result, debug_path, keypoints_image, good_matches, template_box, inlier_box)
        return result

    @staticmethod
    def write_debug(
        gray: np.ndarray,
        mask: np.ndarray,
        result: DetectionResult,
        path: Path,
        keypoints: list[cv2.KeyPoint],
        matches: list[cv2.DMatch],
        template_box: list[int],
        inlier_box: list[int],
    ) -> None:
        canvas = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
        for point in keypoints:
            x, y = round(point.pt[0]), round(point.pt[1])
            cv2.circle(canvas, (x, y), 2, (0, 220, 0), -1)
        if template_box:
            x, y, width, height = template_box
            cv2.rectangle(canvas, (x, y), (x + width, y + height), (255, 180, 0), 2)
        if inlier_box:
            x, y, width, height = inlier_box
            cv2.rectangle(canvas, (x, y), (x + width, y + height), (0, 220, 0), 2)
        canvas[mask > 0] = (180, 0, 255)
        cv2.putText(canvas, f"{result.status} matches={len(matches)}", (6, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 255), 1)
        path.parent.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(path), canvas)


Detector = Any
DETECTOR_REGISTRY: dict[str, Callable[[], Detector]] = {
    "THREAD_PRESENCE": ThreadPresenceDetector,
    "NUT_PRESENCE": NutPresenceDetector,
    "FEATURE_PRESENCE": FeaturePresenceDetector,
}


def detector_for(inspection_type: str) -> Detector:
    try:
        return DETECTOR_REGISTRY[inspection_type]()
    except KeyError as exc:
        raise ValueError(f"unregistered inspection type: {inspection_type}") from exc


def load_bgr(path: str | Path) -> np.ndarray:
    data = np.fromfile(str(path), dtype=np.uint8)
    image = cv2.imdecode(data, cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError(f"cannot decode image: {path}")
    return image


def parse_json_config(value: str | None) -> dict[str, Any]:
    if not value:
        return {}
    parsed = json.loads(value)
    if not isinstance(parsed, dict):
        raise ValueError("config must be a JSON object")
    return parsed
