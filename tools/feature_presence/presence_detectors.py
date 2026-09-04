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


ALGORITHM_VERSION = "presence-offline.3"
TEAL_MASK = {
    "hueRangeOpenCv": [75, 105],
    "saturationMin": 80,
    "valueMin": 50,
    "dilateKernel": [3, 3],
    "dilateIterations": 1,
}

# Nut recovery thresholds are candidate-generation variants only.  Otsu
# remains the default pass; bright variants recover overexposed metal where
# the nut separates from its washer only at a higher intensity level.
NUT_DEFAULTS = {
    "brightThresholds": [180, 200, 220, 230, 240],
    "hullRecoveryMinSolidity": 0.78,
    "nmsContainment": 0.70,
    # A binary bright component is often the washer around the nut.  These
    # ratios describe the body relative to that component; they are not image
    # coordinates and are only used when an inner hexagonal structure exists.
    "bodyWidthRatio": 0.68,
    "bodyHeightRatio": 0.72,
    "bodyCenterYOffsetRatio": -0.08,
    "bodyInnerWidthExpansion": 1.10,
    "bodyInnerHeightExpansion": 1.25,
    "bodySideFaceHeightExpansion": 1.50,
    "bodyInnerMinAreaRatio": 0.05,
    "bodyInnerMaxAreaRatio": 0.55,
    "bodyInnerMinDimensionRatio": 0.20,
    "bodyInnerMinScore": 0.42,
    "bodyHoleMinScore": 0.08,
    "bodyNmsMinEvidence": 0.50,
    "bodyHexAngle": 0.0,
    # The recovered body is anchored by an inner contour.  Searching broad
    # angles against the whole edge map can select a washer/background edge
    # instead of the nut faces, so the production default keeps the stable
    # front-facing geometry prior.  Callers may still provide a custom list
    # through the existing configuration interface when the view is known to
    # require a different pose.
    "bodyHexAngleCandidates": [0.0],
}

# Thread detection keeps the original Hough configuration as the broad pass.
# The fine pass and refinement values are explicit so a dataset can tune them
# without changing the detector implementation.
THREAD_DEFAULTS = {
    "maxWorkingDimension": 512.0,
    "fineMinRadiusRatio": 0.04,
    "fineMinDistRatio": 0.08,
    "fineCircleParam2": 8.0,
    "fineHoughDp": 1.0,
    "fineLowMinDistRatio": 0.04,
    "fineLowCircleParam2": 10.0,
    "fineLowMinRadiusRatio": 0.03,
    "minimumWorkingRadiusPx": 16.0,
    "maxRefinementSeeds": 12,
    "centerSeedMaxRadiusRatio": 0.30,
    "centerSeedCount": 3,
    "refinementCenterRangeFactor": 0.65,
    "refinementCenterSteps": 3,
    "refinementRadiusMinFactor": 0.40,
    "refinementRadiusMaxFactor": 1.20,
    "refinementDarkSeedRadiusMaxFactor": 1.35,
    "refinementRadiusSteps": 12,
    "refinementFineCenterRangePx": 2.0,
    "refinementFineRadiusRangePx": 4.0,
    "refinementFineCenterSteps": 3,
    "refinementFineRadiusSteps": 5,
    "refinementFineSteps": 3,
    "refinementMinRadiusRatio": 0.08,
    "refinementLegacyMinRadiusFactor": 1.15,
    "refinementStrongGeometryMin": 0.55,
    "refinementStrongDarkCoreMin": 0.65,
    "darkCoreMin": 0.20,
    "edgeAngleCount": 48,
    "edgeSearchHalfWidthRatio": 0.20,
    "edgeSupportThreshold": 0.30,
    "edgeRadialSpreadScale": 0.22,
    "edgeConcentrationScale": 0.70,
    "edgeGradientFloorPercentile": 50.0,
    "edgeGradientCeilingPercentile": 90.0,
    "backgroundPenaltyScale": 0.75,
    "darkCoreContrastMin": 0.22,
    "darkCoreInnerRatio": 0.65,
    "darkCoreCenterRatio": 0.30,
    "darkCoreRingInnerRatio": 0.75,
    "darkCoreRingOuterRatio": 0.95,
    "darkCorePercentile": 20.0,
    "darkCoreContrastScale": 90.0,
    "darkCoreUniformityScale": 60.0,
    "darkCoreCenterDistanceFactor": 0.45,
    "darkCoreIsotropyMin": 0.15,
    "darkCoreIsotropyMax": 0.40,
    "darkCoreAreaTarget": 0.28,
    "darkCoreAreaScale": 0.28,
    "centerRingContrastScale": 90.0,
    "centralApertureTargetRatio": 0.45,
    "centralApertureTolerance": 0.25,
    "centralApertureContrastMin": 0.12,
    "ringGradientScale": 80.0,
    "textureProfileSamples": 24,
    "textureProfileMinRadiusRatio": 0.12,
    "textureProfileMaxRadiusRatio": 0.88,
    "textureInnerRadiusRatio": 0.72,
    "textureDerivativeMin": 5.0,
    "textureAmplitudeScale": 60.0,
    "textureInnerEdgeScale": 5.0,
    "textureMinTransitions": 2.0,
    "textureEdgeProfileMinRadiusRatio": 0.20,
    "textureEdgeProfileMaxRadiusRatio": 0.88,
    "textureEdgePeakPercentile": 72.0,
    "textureEdgePeakProminence": 0.08,
    "textureGradientPeakPercentile": 70.0,
    "textureGradientPeakProminence": 0.10,
    "textureMinEdgePeaks": 2.0,
    "texturePeriodicitySupportMin": 0.50,
    "texturePeriodicityRingCoverageMin": 0.18,
    "plainCirclePenaltyWeight": 0.45,
    "textureEdgePeakCountScale": 4.0,
    "texturePeakSpacingScale": 0.40,
    "textureInnerAngleCount": 24,
    "textureInnerAngleSupportThreshold": 0.18,
    "textureInnerAngleCoverageWeight": 0.16,
    "textureGradientRingThreshold": 0.45,
    "textureRadialRingSupportThreshold": 0.28,
    "textureRadialRingAngleCoverageThreshold": 0.28,
    "textureRadialRingCountScale": 4.0,
    "textureAngularVariationScale": 35.0,
    "textureAngularConsistencyWeight": 0.30,
    # A line/arc crossing the candidate can otherwise create strong local
    # gradients while the surrounding band is unsupported background.
    "backgroundPenaltyMax": 0.30,
    # A real threaded aperture normally has either a measurable central
    # opening transition or enough texture amplitude to support it.  These
    # values only gate the ambiguous missing-aperture case and remain tunable.
    "centralApertureMissingScoreMax": 0.05,
    "centralApertureMissingConfidenceMin": 0.18,
    "centralApertureMissingPolarityMin": -0.05,
    "centralApertureMissingTextureAmplitudeMin": 0.20,
    "insideMarginFactor": 1.05,
    "refinementSelectionTieMargin": 0.10,
    "refinementTieTextureConsistencyMin": 0.65,
    "refinementTieTextureMin": 0.75,
    "refinementTieCentralApertureMin": 0.30,
    "refinementTieMaxRadiusRatio": 0.80,
    "refinementTieMinRadiusRatio": 0.70,
    "refinementTieCenterDistanceFactor": 0.60,
    "refinementDarkPolarityThreshold": -0.10,
    "backgroundPenaltyWeight": 0.08,
    "oversizedCircleStartRatio": 0.22,
    "oversizedCircleScale": 0.18,
    "oversizedCirclePenaltyWeight": 0.16,
    "darkMiscenterPenaltyWeight": 0.08,
    "imageCenterPriorWeight": 0.14,
    "imageCenterPriorPower": 2.0,
    "refinementProposalWeight": 0.0,
    "refinementSeedScoreWeight": 0.0,
    "refinementShiftScale": 0.65,
    "refinementRadiusShiftScale": 0.30,
}

THREAD_SCORE_WEIGHTS = {
    "geometry": 0.18,
    "darkCore": 0.13,
    "texture": 0.19,
    "ringGradient": 0.08,
    "darkAlignment": 0.07,
    "sharpness": 0.08,
    "centralAperture": 0.14,
    "textureAngularConsistency": 0.13,
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


def _circle_edge_metrics(
    gray: np.ndarray,
    cx: float,
    cy: float,
    radius: float,
    edges: np.ndarray,
    gradient: np.ndarray,
    config: dict[str, Any],
) -> dict[str, float]:
    """Measure full-circle support instead of trusting Hough's ranking."""

    if radius <= 0:
        return {"angularCoverage": 0.0, "radialConsistency": 0.0, "geometry": 0.0}
    angle_count = max(24, int(config.get("edgeAngleCount", THREAD_DEFAULTS["edgeAngleCount"])))
    angles = np.linspace(0.0, 2.0 * math.pi, angle_count, endpoint=False)
    half_width = max(1.5, radius * float(config["edgeSearchHalfWidthRatio"]))
    sample_count = max(9, 2 * int(math.ceil(half_width)) + 1)
    sample_radii = np.linspace(max(1.0, radius - half_width), radius + half_width, sample_count)
    sample_x = np.clip(
        np.rint(cx + np.cos(angles)[:, None] * sample_radii[None, :]).astype(int),
        0,
        gray.shape[1] - 1,
    )
    sample_y = np.clip(
        np.rint(cy + np.sin(angles)[:, None] * sample_radii[None, :]).astype(int),
        0,
        gray.shape[0] - 1,
    )
    edge_samples = edges[sample_y, sample_x] > 0
    gradient_samples = gradient[sample_y, sample_x]
    gradient_floor = float(
        config.get(
            "_edgeGradientFloor",
            np.percentile(gradient, float(config["edgeGradientFloorPercentile"])),
        )
    )
    gradient_ceiling = float(
        config.get(
            "_edgeGradientCeiling",
            np.percentile(gradient, float(config["edgeGradientCeilingPercentile"])),
        )
    )
    gradient_strength = np.clip(
        (np.max(gradient_samples, axis=1) - gradient_floor) / max(gradient_ceiling - gradient_floor, 1.0),
        0.0,
        1.0,
    )
    canny_support = np.mean(edge_samples, axis=1)
    support = np.clip(0.60 * canny_support + 0.40 * gradient_strength, 0.0, 1.0)
    valid = support >= float(config["edgeSupportThreshold"])
    angular_coverage = float(np.mean(valid)) if valid.size else 0.0
    edge_response = float(np.mean(support)) if support.size else 0.0

    weights = np.maximum(support, 1e-6)
    resultant = float(
        np.hypot(np.mean(weights * np.cos(angles)), np.mean(weights * np.sin(angles)))
        / max(np.mean(weights), 1e-6)
    )
    angular_uniformity = float(
        np.clip(1.0 - resultant / max(float(config["edgeConcentrationScale"]), 1e-6), 0.0, 1.0)
    )
    peak_indices = np.argmax(gradient_samples, axis=1)
    peak_radii = sample_radii[peak_indices][valid]
    if peak_radii.size:
        radial_spread = float(
            1.4826 * np.median(np.abs(peak_radii - np.median(peak_radii))) / max(radius, 1.0)
        )
    else:
        radial_spread = 1.0
    radial_consistency = float(
        np.clip(1.0 - radial_spread / max(float(config["edgeRadialSpreadScale"]), 1e-6), 0.0, 1.0)
    )

    outside_start = radius + half_width + max(1.0, radius * 0.08)
    outside_radii = outside_start + np.linspace(0.0, max(1.0, radius * 0.10), 5)
    outside_x = np.clip(
        np.rint(cx + np.cos(angles)[:, None] * outside_radii[None, :]).astype(int),
        0,
        gray.shape[1] - 1,
    )
    outside_y = np.clip(
        np.rint(cy + np.sin(angles)[:, None] * outside_radii[None, :]).astype(int),
        0,
        gray.shape[0] - 1,
    )
    outside_strength = np.clip(
        (np.mean(gradient[outside_y, outside_x], axis=1) - gradient_floor)
        / max(gradient_ceiling - gradient_floor, 1.0),
        0.0,
        1.0,
    )
    background_penalty = float(np.mean(np.clip(outside_strength - support, 0.0, 1.0)))
    ring_gradient_score = float(np.mean(gradient_strength)) if gradient_strength.size else 0.0
    geometry = float(
        np.clip(
            0.36 * angular_coverage
            + 0.24 * radial_consistency
            + 0.16 * angular_uniformity
            + 0.24 * edge_response
            - float(config["backgroundPenaltyScale"]) * background_penalty,
            0.0,
            1.0,
        )
    )
    return {
        "angularCoverage": angular_coverage,
        "radialConsistency": radial_consistency,
        "radialSpread": radial_spread,
        "angularUniformity": angular_uniformity,
        "edgeSupport": edge_response,
        "ringGradientScore": ring_gradient_score,
        "backgroundPenalty": background_penalty,
        "geometry": geometry,
    }


def _circle_geometry(
    gray: np.ndarray,
    cx: float,
    cy: float,
    radius: float,
    edges: np.ndarray | None = None,
    gradient: np.ndarray | None = None,
    config: dict[str, Any] | None = None,
) -> tuple[float, float, float]:
    """Return angular edge coverage, radial consistency, and combined roundness."""

    edge = cv2.Canny(gray, 40, 120) if edges is None else edges
    grad = (
        cv2.magnitude(
            cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3),
            cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3),
        )
        if gradient is None
        else gradient
    )
    settings = {**THREAD_DEFAULTS, **(config or {})}
    metrics = _circle_edge_metrics(gray, cx, cy, radius, edge, grad, settings)
    return metrics["angularCoverage"], metrics["radialConsistency"], metrics["geometry"]


def _thread_texture_metrics(
    gray: np.ndarray,
    cx: float,
    cy: float,
    radius: float,
    edges: np.ndarray,
    config: dict[str, Any],
    gradient: np.ndarray | None = None,
) -> dict[str, float]:
    """Measure inner concentric texture; a single plain boundary scores low."""

    margin = max(2, int(math.ceil(radius * 1.05)))
    x0 = max(0, int(math.floor(cx - margin)))
    x1 = min(gray.shape[1], int(math.ceil(cx + margin + 1)))
    y0 = max(0, int(math.floor(cy - margin)))
    y1 = min(gray.shape[0], int(math.ceil(cy + margin + 1)))
    local_gray = gray[y0:y1, x0:x1]
    local_edges = edges[y0:y1, x0:x1]
    local_gradient = gradient[y0:y1, x0:x1] if gradient is not None else cv2.magnitude(
        cv2.Sobel(local_gray, cv2.CV_32F, 1, 0, ksize=3),
        cv2.Sobel(local_gray, cv2.CV_32F, 0, 1, ksize=3),
    )
    yy, xx = np.ogrid[y0:y1, x0:x1]
    distance = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
    inner = distance <= radius * 0.72
    ring = (distance >= radius * 0.78) & (distance <= radius * 0.98)
    whole = distance <= radius
    if not np.any(whole):
        return {
            "edgeDensity": 0.0,
            "innerEdgeDensity": 0.0,
            "darkRatio": 0.0,
            "periodicity": 0.0,
            "transitionScore": 0.0,
            "transitionCount": 0.0,
            "oscillation": 0.0,
            "textureAmplitude": 0.0,
            "edgePeakCount": 0.0,
            "edgePeakSpacing": 0.0,
            "edgePeakScore": 0.0,
            "gradientPeakCount": 0.0,
            "gradientPeakSpacing": 0.0,
            "gradientPeakScore": 0.0,
            "innerAngularCoverage": 0.0,
            "radialRingCoverage": 0.0,
            "radialRingCount": 0.0,
            "radialRingScore": 0.0,
            "angularConsistency": 0.0,
            "centerMean": 0.0,
            "outerRingMean": 0.0,
            "centerRingPolarity": 0.0,
            "centralApertureScore": 0.0,
            "centralApertureScaleScore": 0.0,
            "centralApertureRadiusRatio": 0.0,
            "texturePeriodicitySupport": 0.0,
            "texture": 0.0,
        }
    edge_density = float(np.mean(local_edges[whole] > 0))
    inner_texture_region = inner & (distance >= radius * float(config["textureEdgeProfileMinRadiusRatio"]))
    inner_edge_density = float(np.mean(local_edges[inner_texture_region] > 0)) if np.any(inner_texture_region) else 0.0
    ring_value = float(np.median(local_gray[ring])) if np.any(ring) else float(np.median(local_gray[whole]))
    inner_dark_ratio = float(np.mean(local_gray[inner] < ring_value * 0.82)) if np.any(inner) else 0.0
    center_region = distance <= radius * float(config.get("darkCoreCenterRatio", 0.30))
    center_mean = float(np.median(local_gray[center_region])) if np.any(center_region) else ring_value
    outer_ring_mean = ring_value
    center_ring_polarity = float(
        np.clip(
            (center_mean - outer_ring_mean) / max(float(config.get("centerRingContrastScale", 90.0)), 1.0),
            -1.0,
            1.0,
        )
    )

    profile_count = max(12, int(config["textureProfileSamples"]))
    radii = np.linspace(
        max(2.0, radius * float(config["textureProfileMinRadiusRatio"])),
        max(3.0, radius * float(config["textureProfileMaxRadiusRatio"])),
        profile_count,
    )
    profile: list[float] = []
    edge_profile: list[float] = []
    gradient_profile: list[float] = []
    edge_profile_radii = np.linspace(
        max(2.0, radius * float(config["textureEdgeProfileMinRadiusRatio"])),
        max(3.0, radius * float(config["textureEdgeProfileMaxRadiusRatio"])),
        profile_count,
    )
    for sample_radius in radii:
        band = (distance >= sample_radius - 1.0) & (distance <= sample_radius + 1.0)
        profile.append(float(np.median(local_gray[band])) if np.any(band) else 0.0)
    for sample_radius in edge_profile_radii:
        band = (distance >= sample_radius - 1.0) & (distance <= sample_radius + 1.0)
        edge_profile.append(float(np.mean(local_edges[band] > 0)) if np.any(band) else 0.0)
        gradient_profile.append(float(np.percentile(local_gradient[band], 75.0)) if np.any(band) else 0.0)
    values = np.asarray(profile, dtype=np.float32)
    edge_values = np.asarray(edge_profile, dtype=np.float32)
    gradient_values = np.asarray(gradient_profile, dtype=np.float32)
    center_contrast = abs(center_mean - outer_ring_mean) / max(
        float(config.get("centerRingContrastScale", 90.0)), 1.0
    )
    central_aperture_score = 0.0
    central_aperture_scale_score = 0.0
    central_aperture_radius_ratio = 0.0
    if values.size and center_contrast >= float(config["centralApertureContrastMin"]):
        midpoint = (center_mean + outer_ring_mean) * 0.5
        direction = 1.0 if center_mean > outer_ring_mean else -1.0
        inside_side = direction * (values - midpoint) >= 0.0
        crossing_indices = np.flatnonzero(~inside_side)
        if crossing_indices.size:
            crossing_index = int(crossing_indices[0])
            central_aperture_radius_ratio = float(
                np.clip(radii[min(crossing_index, radii.size - 1)] / max(radius, 1.0), 0.0, 1.0)
            )
            target_ratio = float(config["centralApertureTargetRatio"])
            tolerance = max(float(config["centralApertureTolerance"]), 1e-6)
            central_aperture_scale_score = float(
                np.clip(1.0 - abs(central_aperture_radius_ratio - target_ratio) / tolerance, 0.0, 1.0)
            )
            central_aperture_score = float(
                np.clip(
                    min(center_contrast, 1.0) * central_aperture_scale_score,
                    0.0,
                    1.0,
                )
            )
    edge_peak_count = 0.0
    edge_peak_spacing = 0.0
    if edge_values.size >= 5 and float(np.max(edge_values)) > 0.0:
        edge_baseline = float(np.median(edge_values))
        edge_threshold = max(
            edge_baseline + float(config["textureEdgePeakProminence"]),
            float(np.percentile(edge_values, float(config["textureEdgePeakPercentile"]))),
        )
        peak_indices = [
            index
            for index in range(1, edge_values.size - 1)
            if edge_values[index] >= edge_values[index - 1]
            and edge_values[index] >= edge_values[index + 1]
            and edge_values[index] >= edge_threshold
        ]
        edge_peak_count = float(len(peak_indices))
        if len(peak_indices) >= 2:
            spacings = np.diff(np.asarray(peak_indices, dtype=np.float32)) / max(edge_values.size - 1, 1)
            edge_peak_spacing = float(
                np.clip(1.0 - np.std(spacings) / max(float(config["texturePeakSpacingScale"]), 1e-6), 0.0, 1.0)
            )
        elif len(peak_indices) == 1:
            edge_peak_spacing = 0.0
    edge_peak_score = float(
        np.clip(
            edge_peak_count / max(float(config["textureEdgePeakCountScale"]), 1.0),
            0.0,
            1.0,
        )
        * edge_peak_spacing
    )
    inner_angles = np.linspace(
        0.0,
        2.0 * math.pi,
        max(24, int(config["textureInnerAngleCount"])),
        endpoint=False,
    )
    inner_radii = np.linspace(
        max(1.0, radius * float(config["textureProfileMinRadiusRatio"])),
        max(2.0, radius * float(config.get("textureInnerRadiusRatio", 0.72))),
        max(8, profile_count // 2),
    )
    inner_x = np.clip(
        np.rint((cx + np.cos(inner_angles)[:, None] * inner_radii[None, :])).astype(int),
        0,
        gray.shape[1] - 1,
    )
    inner_y = np.clip(
        np.rint((cy + np.sin(inner_angles)[:, None] * inner_radii[None, :])).astype(int),
        0,
        gray.shape[0] - 1,
    )
    inner_edge_samples = edges[inner_y, inner_x] > 0
    inner_gradient_samples = gradient[inner_y, inner_x] if gradient is not None else local_gradient[
        np.clip(inner_y - y0, 0, local_gradient.shape[0] - 1),
        np.clip(inner_x - x0, 0, local_gradient.shape[1] - 1),
    ]
    gradient_floor = float(config.get("_edgeGradientFloor", np.percentile(gradient, 50.0) if gradient is not None else 0.0))
    gradient_ceiling = float(config.get("_edgeGradientCeiling", np.percentile(gradient, 90.0) if gradient is not None else 1.0))
    inner_gradient_strength = np.clip(
        (np.max(inner_gradient_samples, axis=1) - gradient_floor)
        / max(gradient_ceiling - gradient_floor, 1.0),
        0.0,
        1.0,
    )
    inner_angle_support = np.clip(
        0.65 * np.mean(inner_edge_samples, axis=1) + 0.35 * inner_gradient_strength,
        0.0,
        1.0,
    )
    inner_angular_coverage = float(
        np.mean(inner_angle_support >= float(config["textureInnerAngleSupportThreshold"]))
    )
    gradient_normalized = np.clip(
        (inner_gradient_samples - gradient_floor)
        / max(gradient_ceiling - gradient_floor, 1.0),
        0.0,
        1.0,
    )
    radial_ring_support = (
        0.65 * np.mean(inner_edge_samples, axis=0)
        + 0.35
        * np.mean(
            gradient_normalized >= float(config["textureGradientRingThreshold"]),
            axis=0,
        )
    )
    radial_ring_coverage = float(np.mean(radial_ring_support))
    radial_ring_count = float(
        np.count_nonzero(
            radial_ring_support >= float(config["textureRadialRingAngleCoverageThreshold"])
        )
    )
    radial_ring_score = float(
        np.clip(
            radial_ring_count / max(float(config["textureRadialRingCountScale"]), 1.0),
            0.0,
            1.0,
        )
        * np.clip(
            radial_ring_coverage / max(float(config["textureRadialRingSupportThreshold"]), 1e-6),
            0.0,
            1.0,
        )
    )
    angular_gray_samples = gray[inner_y, inner_x].astype(np.float32)
    angular_mad = float(
        np.median(
            np.median(
                np.abs(angular_gray_samples - np.median(angular_gray_samples, axis=0)),
                axis=0,
            )
        )
    )
    angular_consistency = float(
        np.clip(
            1.0 - angular_mad / max(float(config["textureAngularVariationScale"]), 1e-6),
            0.0,
            1.0,
        )
    )
    gradient_peak_count = 0.0
    gradient_peak_spacing = 0.0
    if gradient_values.size >= 5 and float(np.max(gradient_values)) > 0.0:
        gradient_baseline = float(np.median(gradient_values))
        gradient_threshold = max(
            gradient_baseline + float(config["textureGradientPeakProminence"])
            * max(float(np.percentile(gradient_values, 90.0)), 1.0),
            float(np.percentile(gradient_values, float(config["textureGradientPeakPercentile"]))),
        )
        gradient_peak_indices = [
            index
            for index in range(1, gradient_values.size - 1)
            if gradient_values[index] >= gradient_values[index - 1]
            and gradient_values[index] >= gradient_values[index + 1]
            and gradient_values[index] >= gradient_threshold
        ]
        gradient_peak_count = float(len(gradient_peak_indices))
        if len(gradient_peak_indices) >= 2:
            spacings = np.diff(np.asarray(gradient_peak_indices, dtype=np.float32)) / max(gradient_values.size - 1, 1)
            gradient_peak_spacing = float(
                np.clip(1.0 - np.std(spacings) / max(float(config["texturePeakSpacingScale"]), 1e-6), 0.0, 1.0)
            )
    gradient_peak_score = float(
        np.clip(
            gradient_peak_count / max(float(config["textureEdgePeakCountScale"]), 1.0),
            0.0,
            1.0,
        )
        * gradient_peak_spacing
    )
    if values.size < 4:
        periodicity = transition_score = transition_count = oscillation = amplitude = 0.0
    else:
        smooth = cv2.GaussianBlur(values.reshape(1, -1), (5, 1), 0).reshape(-1)
        differences = np.diff(smooth)
        active_limit = max(
            float(config["textureDerivativeMin"]),
            float(np.median(np.abs(differences)) * 0.75),
        )
        active_signs = np.sign(differences[np.abs(differences) >= active_limit])
        transition_count = float(
            np.count_nonzero(active_signs[1:] * active_signs[:-1] < 0) if active_signs.size > 1 else 0
        )
        total_variation = float(np.sum(np.abs(differences)))
        oscillation = float(
            np.clip(
                (total_variation - abs(float(smooth[-1] - smooth[0]))) / max(total_variation, 1.0),
                0.0,
                1.0,
            )
        )
        amplitude = float(
            np.clip(
                (np.percentile(values, 90) - np.percentile(values, 10))
                / max(float(config["textureAmplitudeScale"]), 1.0),
                0.0,
                1.0,
            )
        )
        transition_score = float(np.clip(transition_count / 4.0, 0.0, 1.0))
        periodicity = float(np.clip(0.55 * oscillation + 0.25 * transition_score + 0.20 * amplitude, 0.0, 1.0))
    periodicity_support = float(
        1.0
        if (
            inner_edge_density >= float(config["textureInnerAngleSupportThreshold"])
            and (
                transition_count >= float(config["textureMinTransitions"])
                or (
                    gradient_peak_count >= float(config["textureMinEdgePeaks"]) + 1.0
                    and radial_ring_count >= float(config["textureMinTransitions"])
                )
                or (
                    radial_ring_count >= float(config["textureMinTransitions"])
                    and radial_ring_coverage >= float(config["texturePeriodicityRingCoverageMin"])
                )
            )
        )
        else 0.0
    )
    inner_edge_score = float(np.clip(inner_edge_density * float(config["textureInnerEdgeScale"]), 0.0, 1.0))
    texture = float(
        np.clip(
            0.42 * inner_edge_score
            + 0.27 * periodicity
            + 0.13 * transition_score
            + 0.10 * edge_peak_score
            + 0.08 * gradient_peak_score
            + float(config["textureInnerAngleCoverageWeight"]) * inner_angular_coverage
            + 0.04 * radial_ring_score
            + float(config["textureAngularConsistencyWeight"]) * angular_consistency,
            0.0,
            1.0,
        )
    )
    return {
        "edgeDensity": edge_density,
        "innerEdgeDensity": inner_edge_density,
        "darkRatio": inner_dark_ratio,
        "periodicity": periodicity,
        "transitionScore": transition_score,
        "transitionCount": transition_count,
        "oscillation": oscillation,
        "textureAmplitude": amplitude,
        "edgePeakCount": edge_peak_count,
        "edgePeakSpacing": edge_peak_spacing,
        "edgePeakScore": edge_peak_score,
        "gradientPeakCount": gradient_peak_count,
        "gradientPeakSpacing": gradient_peak_spacing,
        "gradientPeakScore": gradient_peak_score,
        "innerAngularCoverage": inner_angular_coverage,
        "centerMean": center_mean,
        "outerRingMean": outer_ring_mean,
        "centerRingPolarity": center_ring_polarity,
        "centralApertureScore": central_aperture_score,
        "centralApertureScaleScore": central_aperture_scale_score,
        "centralApertureRadiusRatio": central_aperture_radius_ratio,
        "radialRingCoverage": radial_ring_coverage,
        "radialRingCount": radial_ring_count,
        "radialRingScore": radial_ring_score,
        "angularConsistency": angular_consistency,
        "texturePeriodicitySupport": periodicity_support,
        "texture": texture,
    }


def _periodic_ring_metrics(
    gray: np.ndarray,
    cx: float,
    cy: float,
    radius: float,
    edges: np.ndarray | None = None,
) -> tuple[float, float, float, float]:
    """Compatibility tuple for callers that need the original ring metrics."""

    edge = cv2.Canny(gray, 40, 120) if edges is None else edges
    metrics = _thread_texture_metrics(gray, cx, cy, radius, edge, THREAD_DEFAULTS)
    return metrics["edgeDensity"], metrics["darkRatio"], metrics["periodicity"], metrics["transitionScore"]


def _thread_dark_core_metrics(
    gray: np.ndarray,
    cx: float,
    cy: float,
    radius: float,
    dark_ratio: float,
    ring_gradient_score: float,
    config: dict[str, Any],
) -> dict[str, float]:
    """Score a dark core, down-weighting asymmetric perspective/occlusion."""

    margin = max(2, int(math.ceil(radius * float(config["insideMarginFactor"]))))
    x0 = max(0, int(math.floor(cx - margin)))
    x1 = min(gray.shape[1], int(math.ceil(cx + margin + 1)))
    y0 = max(0, int(math.floor(cy - margin)))
    y1 = min(gray.shape[0], int(math.ceil(cy + margin + 1)))
    local_gray = gray[y0:y1, x0:x1]
    yy, xx = np.ogrid[y0:y1, x0:x1]
    distance = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
    inner = distance <= radius * float(config["darkCoreInnerRatio"])
    center = distance <= radius * float(config["darkCoreCenterRatio"])
    middle = (distance >= radius * float(config["darkCoreCenterRatio"])) & (distance <= radius * float(config["darkCoreInnerRatio"]))
    ring = (distance >= radius * float(config["darkCoreRingInnerRatio"])) & (distance <= radius * float(config["darkCoreRingOuterRatio"]))
    if not np.any(inner) or not np.any(center) or not np.any(middle) or not np.any(ring):
        return {
            "darkCoreScore": 0.0,
            "darkCoreContrast": 0.0,
            "darkCoreUniformity": 0.0,
            "darkCoreAlignment": 0.0,
            "darkCoreAlignmentRaw": 0.0,
            "darkCoreConfidence": 0.0,
            "darkCoreOffset": float(radius),
            "innerOuterConcentricity": 0.0,
            "darkCoreMiscenterPenalty": 1.0,
        }

    values = local_gray[inner]
    percentile = float(config["darkCorePercentile"])
    dark_threshold = float(np.percentile(values, np.clip(percentile, 1.0, 50.0)))
    dark_pixels = inner & (local_gray <= dark_threshold)
    if np.count_nonzero(dark_pixels) < 4:
        dark_core_offset = float(radius)
        dark_core_alignment_raw = 0.0
        dark_core_uniformity = 0.0
        dark_core_confidence = 0.0
    else:
        dark_y, dark_x = np.nonzero(dark_pixels)
        dark_core_offset = float(np.hypot(np.mean(dark_x + x0) - cx, np.mean(dark_y + y0) - cy))
        distance_factor = float(config["darkCoreCenterDistanceFactor"])
        dark_core_alignment_raw = float(
            np.clip(1.0 - dark_core_offset / max(radius * distance_factor, 1.0), 0.0, 1.0)
        )
        covariance = np.cov(dark_x, dark_y) if dark_x.size > 4 else np.zeros((2, 2), dtype=float)
        eigenvalues = np.linalg.eigvalsh(covariance) if np.all(np.isfinite(covariance)) else np.array([0.0, 0.0])
        isotropy = float(eigenvalues[0] / max(eigenvalues[1], 1e-6)) if eigenvalues[1] > 0 else 0.0
        isotropy_min = float(config["darkCoreIsotropyMin"])
        isotropy_max = float(config["darkCoreIsotropyMax"])
        isotropy_score = float(np.clip((isotropy - isotropy_min) / max(isotropy_max - isotropy_min, 1e-6), 0.0, 1.0))
        area_ratio = float(np.count_nonzero(dark_pixels) / max(np.count_nonzero(inner), 1))
        area_score = float(
            np.clip(
                1.0 - abs(area_ratio - float(config["darkCoreAreaTarget"]))
                / max(float(config["darkCoreAreaScale"]), 1e-6),
                0.0,
                1.0,
            )
        )
        dark_core_confidence = float(0.65 * isotropy_score + 0.35 * area_score)
        core_values = local_gray[dark_pixels]
        core_value = float(np.median(core_values))
        middle_value = float(np.median(local_gray[middle]))
        dark_core_uniformity = float(
            np.clip(1.0 - np.std(core_values) / max(float(config["darkCoreUniformityScale"]), 1.0), 0.0, 1.0)
        )
        dark_core_contrast = float(
            np.clip((middle_value - core_value) / max(float(config["darkCoreContrastScale"]), 1.0), 0.0, 1.0)
        )
        contrast_confidence = float(
            np.clip(
                (dark_core_contrast - float(config["darkCoreContrastMin"]))
                / max(1.0 - float(config["darkCoreContrastMin"]), 1e-6),
                0.0,
                1.0,
            )
        )
        dark_core_confidence *= contrast_confidence
        dark_core_alignment = float(
            0.5 + (dark_core_alignment_raw - 0.5) * dark_core_confidence
        )
        dark_core_score = float(
            np.clip(
                0.30 * dark_ratio
                + 0.25 * dark_core_contrast
                + 0.15 * dark_core_uniformity
                + 0.20 * dark_core_alignment
                + 0.10 * ring_gradient_score,
                0.0,
                1.0,
            )
        )
        return {
            "darkCoreScore": dark_core_score,
            "darkCoreContrast": dark_core_contrast,
            "darkCoreUniformity": dark_core_uniformity,
            "darkCoreAlignment": dark_core_alignment,
            "darkCoreAlignmentRaw": dark_core_alignment_raw,
            "darkCoreConfidence": dark_core_confidence,
            "darkCoreOffset": dark_core_offset,
            "innerOuterConcentricity": dark_core_alignment,
            "darkCoreMiscenterPenalty": float(1.0 - dark_core_alignment),
        }

    dark_core_contrast = 0.0
    dark_core_score = float(np.clip(0.30 * dark_ratio + 0.10 * ring_gradient_score, 0.0, 1.0))
    return {
        "darkCoreScore": dark_core_score,
        "darkCoreContrast": dark_core_contrast,
        "darkCoreUniformity": dark_core_uniformity,
        "darkCoreAlignment": dark_core_alignment_raw,
        "darkCoreAlignmentRaw": dark_core_alignment_raw,
        "darkCoreConfidence": dark_core_confidence,
        "darkCoreOffset": dark_core_offset,
        "innerOuterConcentricity": dark_core_alignment_raw,
        "darkCoreMiscenterPenalty": float(1.0 - dark_core_alignment_raw),
    }


def _thread_candidate_metrics(
    gray: np.ndarray,
    cx: float,
    cy: float,
    radius: float,
    edges: np.ndarray,
    gradient: np.ndarray,
    config: dict[str, Any],
) -> dict[str, float]:
    """Score one circle using geometry, texture, dark-core and background evidence."""

    edge_metrics = _circle_edge_metrics(gray, cx, cy, radius, edges, gradient, config)
    texture_metrics = _thread_texture_metrics(gray, cx, cy, radius, edges, config, gradient)
    dark_metrics = _thread_dark_core_metrics(
        gray,
        cx,
        cy,
        radius,
        texture_metrics["darkRatio"],
        edge_metrics["ringGradientScore"],
        config,
    )
    texture = texture_metrics["texture"]
    circle_scale_ratio = radius / max(float(min(gray.shape)), 1.0)
    oversized_circle_penalty = float(
        np.clip(
            (circle_scale_ratio - float(config["oversizedCircleStartRatio"]))
            / max(float(config["oversizedCircleScale"]), 1e-6),
            0.0,
            1.0,
        )
        * np.clip(1.0 - texture_metrics["centralApertureScore"], 0.0, 1.0)
    )
    plain_circle_penalty = float(
        np.clip(
            float(config["texturePeriodicitySupportMin"])
            - texture_metrics["texturePeriodicitySupport"],
            0.0,
            1.0,
        )
        / max(float(config["texturePeriodicitySupportMin"]), 1e-6)
    )
    evidence_score = float(
        np.clip(
            THREAD_SCORE_WEIGHTS["geometry"] * edge_metrics["geometry"]
            + THREAD_SCORE_WEIGHTS["darkCore"] * dark_metrics["darkCoreScore"]
            + THREAD_SCORE_WEIGHTS["texture"] * texture
            + THREAD_SCORE_WEIGHTS["ringGradient"] * edge_metrics["ringGradientScore"]
            + THREAD_SCORE_WEIGHTS["darkAlignment"] * dark_metrics["innerOuterConcentricity"]
            + THREAD_SCORE_WEIGHTS["sharpness"] * float(config.get("_sharpness", 0.0))
            + THREAD_SCORE_WEIGHTS["centralAperture"] * texture_metrics["centralApertureScore"]
            + THREAD_SCORE_WEIGHTS["textureAngularConsistency"] * texture_metrics["angularConsistency"]
            - float(config["backgroundPenaltyWeight"]) * edge_metrics["backgroundPenalty"]
            - float(config["darkMiscenterPenaltyWeight"])
            * dark_metrics["darkCoreMiscenterPenalty"]
            * dark_metrics["darkCoreConfidence"]
            - float(config["oversizedCirclePenaltyWeight"]) * oversized_circle_penalty
            - float(config["plainCirclePenaltyWeight"]) * plain_circle_penalty,
            0.0,
            1.0,
        )
    )
    image_center_x = float(config.get("_imageCenterX", (gray.shape[1] - 1) / 2.0))
    image_center_y = float(config.get("_imageCenterY", (gray.shape[0] - 1) / 2.0))
    image_center_distance = math.hypot(cx - image_center_x, cy - image_center_y)
    image_center_diagonal = max(math.hypot(gray.shape[1] / 2.0, gray.shape[0] / 2.0), 1.0)
    center_prior = float(
        np.clip(
            1.0 - image_center_distance / image_center_diagonal,
            0.0,
            1.0,
        )
        ** float(config["imageCenterPriorPower"])
    )
    center_prior_weight = float(config["imageCenterPriorWeight"])
    selection_score = float(
        np.clip(
            (1.0 - center_prior_weight) * evidence_score + center_prior_weight * center_prior,
            0.0,
            1.0,
        )
    )
    return {
        "geometry": edge_metrics["geometry"],
        "angularCoverage": edge_metrics["angularCoverage"],
        "radialConsistency": edge_metrics["radialConsistency"],
        "radialSpread": edge_metrics["radialSpread"],
            "angularUniformity": edge_metrics["angularUniformity"],
            "edgeSupport": edge_metrics["edgeSupport"],
            "backgroundPenalty": edge_metrics["backgroundPenalty"],
        "oversizedCirclePenalty": oversized_circle_penalty,
        "plainCirclePenalty": plain_circle_penalty,
        "darkRatio": texture_metrics["darkRatio"],
        "texture": texture,
        "innerEdgeDensity": texture_metrics["innerEdgeDensity"],
        "periodicity": texture_metrics["periodicity"],
        "transitionScore": texture_metrics["transitionScore"],
        "transitionCount": texture_metrics["transitionCount"],
        "textureOscillation": texture_metrics["oscillation"],
        "textureAmplitude": texture_metrics["textureAmplitude"],
        "textureEdgePeakCount": texture_metrics["edgePeakCount"],
        "textureEdgePeakSpacing": texture_metrics["edgePeakSpacing"],
        "textureEdgePeakScore": texture_metrics["edgePeakScore"],
        "textureGradientPeakCount": texture_metrics["gradientPeakCount"],
        "textureGradientPeakSpacing": texture_metrics["gradientPeakSpacing"],
        "textureGradientPeakScore": texture_metrics["gradientPeakScore"],
        "textureInnerAngularCoverage": texture_metrics["innerAngularCoverage"],
        "textureRadialRingCoverage": texture_metrics["radialRingCoverage"],
        "textureRadialRingCount": texture_metrics["radialRingCount"],
        "textureRadialRingScore": texture_metrics["radialRingScore"],
        "textureAngularConsistency": texture_metrics["angularConsistency"],
        "texturePeriodicitySupport": texture_metrics["texturePeriodicitySupport"],
        "centerMean": texture_metrics["centerMean"],
        "outerRingMean": texture_metrics["outerRingMean"],
        "centerRingPolarity": texture_metrics["centerRingPolarity"],
        "centralApertureScore": texture_metrics["centralApertureScore"],
        "centralApertureScaleScore": texture_metrics["centralApertureScaleScore"],
        "centralApertureRadiusRatio": texture_metrics["centralApertureRadiusRatio"],
        **dark_metrics,
        "ringGradientScore": edge_metrics["ringGradientScore"],
        "selectionScore": selection_score,
        "imageCenterPrior": center_prior,
    }


class ThreadPresenceDetector:
    algorithm = "ThreadPresenceDetector"

    def detect(
        self,
        image: np.ndarray,
        config: dict[str, Any] | None = None,
        debug_path: Path | None = None,
    ) -> DetectionResult:
        started = time.perf_counter()
        config = {**THREAD_DEFAULTS, **(config or {})}
        # HoughCircles may use parallel reductions whose equal-score order can
        # vary between runs.  Thread geometry is an audit artifact, so keep
        # this detector deterministic before candidate ranking/refinement.
        cv2.setNumThreads(1)
        invalid = validate_image(image)
        if invalid:
            return _result(self.algorithm, started, "ERROR", None, invalid)
        original_gray, original_mask, mask_metrics = masked_gray(image)
        if min(original_gray.shape) < 24:
            return _result(self.algorithm, started, "ERROR", None, "ROI is too small for circle and texture evidence")

        original_height, original_width = original_gray.shape[:2]
        max_dimension = max(24, int(float(config["maxWorkingDimension"])))
        scale = min(1.0, max_dimension / max(original_height, original_width))
        if scale < 1.0:
            gray = cv2.resize(original_gray, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
            mask = cv2.resize(original_mask, (gray.shape[1], gray.shape[0]), interpolation=cv2.INTER_NEAREST)
        else:
            gray, mask = original_gray, original_mask
        work = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(gray)
        work = cv2.GaussianBlur(work, (3, 3), 0)
        height, width = gray.shape[:2]
        min_dim = min(height, width)
        max_radius = max(4, int(min_dim * float(config.get("maxRadiusRatio", 0.48))))
        edges = cv2.Canny(gray, 40, 120)
        gradient = cv2.magnitude(
            cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3),
            cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3),
        )
        config["_edgeGradientFloor"] = float(np.percentile(gradient, float(config["edgeGradientFloorPercentile"])))
        config["_edgeGradientCeiling"] = float(np.percentile(gradient, float(config["edgeGradientCeilingPercentile"])))
        quality = _quality(gray)
        sharpness = float(
            np.clip(
                cv2.Laplacian(gray, cv2.CV_64F).var() / float(config.get("sharpnessScale", 1000.0)),
                0.0,
                1.0,
            )
        )
        config["_sharpness"] = sharpness
        candidates: list[dict[str, float]] = []

        def inside(cx: float, cy: float, radius: float) -> bool:
            return radius > 0 and radius * float(config["insideMarginFactor"]) < min(cx, cy, width - cx, height - cy)

        def add_hough_candidates(circles: np.ndarray | None, source: str) -> None:
            if circles is None:
                return
            raw_circles = sorted(
                np.round(circles[0], 2).tolist(),
                key=lambda item: (float(item[0]), float(item[1]), float(item[2])),
            )
            for raw_cx, raw_cy, raw_radius in raw_circles:
                cx, cy, radius = float(raw_cx), float(raw_cy), float(raw_radius)
                if not inside(cx, cy, radius):
                    continue
                metrics = _thread_candidate_metrics(gray, cx, cy, radius, edges, gradient, config)
                candidate = {
                    "cx": cx,
                    "cy": cy,
                    "radius": radius,
                    "rawCx": cx,
                    "rawCy": cy,
                    "rawRadius": radius,
                    "source": source,
                    **metrics,
                }
                duplicate = next(
                    (
                        item
                        for item in candidates
                        if math.hypot(item["cx"] - cx, item["cy"] - cy) <= min_dim * 0.025
                        and abs(item["radius"] - radius) <= max(2.0, radius * 0.10)
                    ),
                    None,
                )
                if duplicate is None:
                    candidates.append(candidate)
                elif candidate["selectionScore"] > duplicate["selectionScore"]:
                    candidates[candidates.index(duplicate)] = candidate

        legacy_min_radius = max(3, int(min_dim * float(config.get("minRadiusRatio", 0.12))))
        legacy_circles = cv2.HoughCircles(
            work,
            cv2.HOUGH_GRADIENT,
            dp=float(config.get("houghDp", 1.2)),
            minDist=max(5.0, min_dim * float(config.get("minDistRatio", 0.25))),
            param1=float(config.get("houghParam1", 70.0)),
            param2=float(config.get("circleParam2", max(8.0, min_dim * 0.12))),
            minRadius=legacy_min_radius,
            maxRadius=max(legacy_min_radius + 1, max_radius),
        )
        add_hough_candidates(legacy_circles, "legacy")
        fine_min_radius = max(
            3,
            int(min_dim * float(config["fineMinRadiusRatio"])),
            round(float(config["minimumWorkingRadiusPx"])),
        )
        fine_circles = cv2.HoughCircles(
            work,
            cv2.HOUGH_GRADIENT,
            dp=float(config["fineHoughDp"]),
            minDist=max(4.0, min_dim * float(config["fineMinDistRatio"])),
            param1=float(config.get("houghParam1", 70.0)),
            param2=float(config["fineCircleParam2"]),
            minRadius=fine_min_radius,
            maxRadius=max(fine_min_radius + 1, max_radius),
        )
        add_hough_candidates(fine_circles, "fine")
        low_circles = cv2.HoughCircles(
            work,
            cv2.HOUGH_GRADIENT,
            dp=float(config["fineHoughDp"]),
            minDist=max(3.0, min_dim * float(config["fineLowMinDistRatio"])),
            param1=float(config.get("houghParam1", 70.0)),
            param2=float(config["fineLowCircleParam2"]),
            minRadius=max(
                3,
                int(min_dim * float(config.get("fineLowMinRadiusRatio", 0.03))),
                round(float(config["minimumWorkingRadiusPx"])),
            ),
            maxRadius=max(fine_min_radius + 1, max_radius),
        )
        add_hough_candidates(low_circles, "fine-low")
        if not candidates:
            metrics = {
                **mask_metrics,
                "circleCandidates": 0.0,
                "quality": quality,
                "sharpness": sharpness,
                "workingScale": scale,
            }
            return _result(self.algorithm, started, "FAIL", 0.0, "no circular aperture candidate", metrics)

        candidates.sort(
            key=lambda item: (
                -item["selectionScore"],
                item["cx"],
                item["cy"],
                item["radius"],
            )
        )
        max_seeds = max(1, int(config["maxRefinementSeeds"]))
        seeds: list[dict[str, float]] = []

        def add_seed(candidate: dict[str, float]) -> None:
            if len(seeds) >= max_seeds:
                return
            if any(
                math.hypot(item["cx"] - candidate["cx"], item["cy"] - candidate["cy"]) <= min_dim * 0.025
                and abs(item["radius"] - candidate["radius"]) <= max(2.0, candidate["radius"] * 0.10)
                for item in seeds
            ):
                return
            seeds.append(candidate)

        for candidate in candidates[: max(2, max_seeds // 5)]:
            add_seed(candidate)
        # Keep each Hough pass represented.  A low-threshold pass is allowed
        # to discover the actual aperture, but it must not win by itself.
        for source in ("legacy", "fine", "fine-low"):
            source_candidates = [item for item in candidates if item["source"] == source]
            for candidate in sorted(source_candidates, key=lambda item: item["selectionScore"], reverse=True)[:2]:
                add_seed(candidate)
        for candidate in sorted(
            candidates,
            key=lambda item: item.get("centralApertureScore", 0.0),
            reverse=True,
        )[: max(2, max_seeds // 4)]:
            add_seed(candidate)
        center_candidates = [
            item
            for item in candidates
            if item["radius"] <= min_dim * float(config["centerSeedMaxRadiusRatio"])
        ]
        center_candidates.sort(
            key=lambda item: (
                math.hypot(
                    item["cx"] - float(config.get("_imageCenterX", (width - 1) / 2.0)),
                    item["cy"] - float(config.get("_imageCenterY", (height - 1) / 2.0)),
                ),
                -item["selectionScore"],
            )
        )
        for candidate in center_candidates[: int(config["centerSeedCount"])]:
            add_seed(candidate)
        for candidate in sorted(
            (
                item
                for item in candidates
                if item.get("centerRingPolarity", 0.0)
                <= float(config["refinementDarkPolarityThreshold"])
                and item["darkCoreScore"] >= float(config["darkCoreMin"])
            ),
            key=lambda item: (item["radius"], -item["darkCoreScore"]),
        )[: max(2, max_seeds // 3)]:
            add_seed(candidate)
        for key in ("textureAngularConsistency", "darkCoreAlignment", "texture", "geometry"):
            for candidate in sorted(candidates, key=lambda item: item.get(key, 0.0), reverse=True):
                add_seed(candidate)
                if len(seeds) >= max_seeds:
                    break
        for small in sorted(candidates, key=lambda item: item["radius"])[: max(3, max_seeds // 3)]:
            if all(
                math.hypot(item["cx"] - small["cx"], item["cy"] - small["cy"]) > min_dim * 0.025
                or abs(item["radius"] - small["radius"]) > max(2.0, small["radius"] * 0.10)
                for item in seeds
            ) and len(seeds) < max_seeds:
                seeds.append(small)

        def dark_center(cx: float, cy: float, radius: float) -> tuple[float, float] | None:
            yy, xx = np.ogrid[: gray.shape[0], : gray.shape[1]]
            distance = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
            inner = distance <= radius * float(config["darkCoreInnerRatio"])
            if not np.any(inner):
                return None
            threshold = float(np.percentile(gray[inner], np.clip(float(config["darkCorePercentile"]), 1.0, 50.0)))
            selected = inner & (gray <= threshold)
            if np.count_nonzero(selected) < 4:
                return None
            y_indices, x_indices = np.nonzero(selected)
            return float(np.mean(x_indices)), float(np.mean(y_indices))

        def refine(seed: dict[str, float]) -> dict[str, float]:
            seed_radius = seed["radius"]
            center_range = float(config["refinementCenterRangeFactor"]) * seed_radius
            center_steps = max(3, int(config["refinementCenterSteps"]))
            radius_min = max(3.0, min_dim * float(config["refinementMinRadiusRatio"]))
            radius_max = min(float(max_radius), min_dim * float(config.get("fineMaxRadiusRatio", 0.48)))
            radius_min = min(radius_min, radius_max - 1.0)
            seed_radius_min = max(radius_min, seed_radius * float(config["refinementRadiusMinFactor"]))
            seed_radius_max = min(
                radius_max,
                seed_radius
                * float(
                    config["refinementDarkSeedRadiusMaxFactor"]
                    if seed.get("centerRingPolarity", 0.0)
                    <= float(config["refinementDarkPolarityThreshold"])
                    else config["refinementRadiusMaxFactor"]
                ),
            )
            radius_values = np.linspace(
                seed_radius_min,
                seed_radius_max,
                max(3, int(config["refinementRadiusSteps"])),
            )
            center_offsets = np.linspace(-center_range, center_range, center_steps)
            centers = [(seed["cx"] + dx, seed["cy"] + dy) for dx in center_offsets for dy in center_offsets]
            proposed_center = dark_center(seed["cx"], seed["cy"], seed_radius)
            if proposed_center is not None and math.hypot(proposed_center[0] - seed["cx"], proposed_center[1] - seed["cy"]) <= center_range:
                centers.append(proposed_center)

            best_local: dict[str, float] | None = None
            evaluation_count = 0

            def visit(cx: float, cy: float, radius: float) -> None:
                nonlocal best_local, evaluation_count
                if not inside(cx, cy, radius):
                    return
                evaluation_count += 1
                metrics = _thread_candidate_metrics(gray, cx, cy, radius, edges, gradient, config)
                center_shift = math.hypot(cx - seed["cx"], cy - seed["cy"])
                radius_shift = abs(radius - seed["radius"])
                proposal_prior = math.exp(
                    -center_shift / max(seed["radius"] * float(config["refinementShiftScale"]), 1.0)
                    -radius_shift / max(seed["radius"] * float(config["refinementRadiusShiftScale"]), 1.0)
                )
                candidate = {
                    "cx": float(cx),
                    "cy": float(cy),
                    "radius": float(radius),
                    "refinementScore": float(
                        np.clip(
                            (1.0 - float(config["refinementProposalWeight"])) * metrics["selectionScore"]
                            + float(config["refinementProposalWeight"]) * proposal_prior
                            + float(config["refinementSeedScoreWeight"]) * seed["selectionScore"],
                            0.0,
                            1.0,
                        )
                    ),
                    "proposalPrior": proposal_prior,
                    **metrics,
                }
                if best_local is None or candidate["refinementScore"] > best_local["refinementScore"]:
                    best_local = candidate

            for center_x, center_y in centers:
                for radius in radius_values:
                    visit(center_x, center_y, float(radius))
            if best_local is None:
                best_local = {"cx": seed["cx"], "cy": seed["cy"], "radius": seed_radius, **seed}
            fine_center_range = float(config["refinementFineCenterRangePx"])
            fine_radius_range = float(config["refinementFineRadiusRangePx"])
            fine_center_steps = max(3, int(config.get("refinementFineCenterSteps", config["refinementFineSteps"])))
            fine_radius_steps = max(3, int(config.get("refinementFineRadiusSteps", config["refinementFineSteps"])))
            fine_center_bases = [(best_local["cx"], best_local["cy"])]
            if proposed_center is not None:
                fine_center_bases.append(proposed_center)
            fine_radius_base = best_local["radius"]
            for center_x, center_y in fine_center_bases:
                for dx in np.linspace(-fine_center_range, fine_center_range, fine_center_steps):
                    for dy in np.linspace(-fine_center_range, fine_center_range, fine_center_steps):
                        for radius in np.linspace(
                            max(seed_radius_min, fine_radius_base - fine_radius_range),
                            min(seed_radius_max, fine_radius_base + fine_radius_range),
                            fine_radius_steps,
                        ):
                            visit(center_x + float(dx), center_y + float(dy), float(radius))
            assert best_local is not None
            best_local.update(
                {
                    "rawCx": seed["rawCx"],
                    "rawCy": seed["rawCy"],
                    "rawRadius": seed["rawRadius"],
                    "source": seed["source"],
                    "refinementEvaluations": float(evaluation_count),
                    "refinementShiftPx": float(math.hypot(best_local["cx"] - seed["rawCx"], best_local["cy"] - seed["rawCy"])),
                    "refinementRadiusDelta": float(best_local["radius"] - seed["rawRadius"]),
                }
            )
            return best_local

        refined_candidates = [refine(seed) for seed in seeds]
        best = max(refined_candidates, key=lambda item: item["refinementScore"])
        tie_candidates = [
            item
            for item in refined_candidates
            if best["refinementScore"] - item["refinementScore"]
            <= float(config["refinementSelectionTieMargin"])
            and item["radius"] >= best["radius"] * float(config["refinementTieMinRadiusRatio"])
            and item["radius"] <= best["radius"] * float(config["refinementTieMaxRadiusRatio"])
            and (
                item.get("textureAngularConsistency", 0.0)
                >= float(config["refinementTieTextureConsistencyMin"])
                or (
                    item.get("texture", 0.0) >= float(config["refinementTieTextureMin"])
                    and item.get("texturePeriodicitySupport", 0.0)
                    >= float(config["texturePeriodicitySupportMin"])
                    and item.get("centralApertureScore", 0.0)
                    >= float(config["refinementTieCentralApertureMin"])
                )
            )
            and math.hypot(item["cx"] - best["cx"], item["cy"] - best["cy"])
            <= best["radius"] * float(config["refinementTieCenterDistanceFactor"])
        ]
        if tie_candidates:
            best = min(tie_candidates, key=lambda item: (item["radius"], -item["refinementScore"]))
        refined_candidates.sort(
            key=lambda item: (
                -item["refinementScore"],
                item["cx"],
                item["cy"],
                item["radius"],
            )
        )
        cx, cy, radius = best["cx"], best["cy"], best["radius"]
        score = float(
            np.clip(
                THREAD_SCORE_WEIGHTS["geometry"] * best["geometry"]
                + THREAD_SCORE_WEIGHTS["darkCore"] * best["darkCoreScore"]
                + THREAD_SCORE_WEIGHTS["texture"] * best["texture"]
                + THREAD_SCORE_WEIGHTS["ringGradient"] * best["ringGradientScore"]
                + THREAD_SCORE_WEIGHTS["darkAlignment"] * best["innerOuterConcentricity"]
                + THREAD_SCORE_WEIGHTS["sharpness"] * sharpness
                + THREAD_SCORE_WEIGHTS["centralAperture"] * best["centralApertureScore"]
                + THREAD_SCORE_WEIGHTS["textureAngularConsistency"] * best["textureAngularConsistency"]
                - float(config["backgroundPenaltyWeight"]) * best["backgroundPenalty"]
                - float(config["darkMiscenterPenaltyWeight"])
                * best["darkCoreMiscenterPenalty"]
                * best["darkCoreConfidence"]
                - float(config["oversizedCirclePenaltyWeight"]) * best["oversizedCirclePenalty"]
                - float(config["plainCirclePenaltyWeight"]) * best["plainCirclePenalty"],
                0.0,
                1.0,
            )
        )
        scale_back = 1.0 / scale
        output_cx, output_cy, output_radius = cx * scale_back, cy * scale_back, radius * scale_back
        raw_cx, raw_cy, raw_radius = best["rawCx"] * scale_back, best["rawCy"] * scale_back, best["rawRadius"] * scale_back
        output_candidates = [
            {
                **candidate,
                "rawCx": candidate["rawCx"] * scale_back,
                "rawCy": candidate["rawCy"] * scale_back,
                "rawRadius": candidate["rawRadius"] * scale_back,
            }
            for candidate in candidates
        ]
        metrics = {
            **mask_metrics,
            "circleCandidates": float(len(candidates)),
            "refinedCandidates": float(len(refined_candidates)),
            "circleCenterX": output_cx,
            "circleCenterY": output_cy,
            "circleRadius": output_radius,
            "refinedCircleCenterX": output_cx,
            "refinedCircleCenterY": output_cy,
            "refinedCircleRadius": output_radius,
            "rawCircleCenterX": raw_cx,
            "rawCircleCenterY": raw_cy,
            "rawCircleRadius": raw_radius,
            "rawHoughSource": 1.0 if best["source"] == "fine" else 0.0,
            "refinementScore": best["refinementScore"],
            "refinementProposalPrior": best["proposalPrior"],
            "refinementEvaluations": best["refinementEvaluations"],
            "refinementShiftPx": best["refinementShiftPx"] * scale_back,
            "refinementRadiusDelta": best["refinementRadiusDelta"] * scale_back,
            "workingScale": scale,
            "circleGeometryScore": best["geometry"],
            "circleAngularCoverage": best["angularCoverage"],
            "circleRadialConsistency": best["radialConsistency"],
            "circleRadialSpread": best["radialSpread"],
            "circleAngularUniformity": best["angularUniformity"],
            "circleEdgeSupport": best["edgeSupport"],
            "circleBackgroundPenalty": best["backgroundPenalty"],
            "oversizedCirclePenalty": best["oversizedCirclePenalty"],
            "plainCirclePenalty": best["plainCirclePenalty"],
            "innerDarkRatio": best["darkRatio"],
            "darkCoreScore": best["darkCoreScore"],
            "darkCoreContrast": best["darkCoreContrast"],
            "darkCoreUniformity": best["darkCoreUniformity"],
            "darkCoreAlignment": best["darkCoreAlignment"],
            "darkCoreAlignmentRaw": best["darkCoreAlignmentRaw"],
            "darkCoreConfidence": best["darkCoreConfidence"],
            "darkCoreOffset": best["darkCoreOffset"] * scale_back,
            "darkCoreMiscenterPenalty": best["darkCoreMiscenterPenalty"],
            "innerOuterConcentricity": best["innerOuterConcentricity"],
            "ringGradientScore": best["ringGradientScore"],
            "texturePeriodicityScore": best["texture"],
            "textureInnerEdgeDensity": best["innerEdgeDensity"],
            "textureRadialPeriodicity": best["periodicity"],
            "textureTransitionScore": best["transitionScore"],
            "textureTransitionCount": best["transitionCount"],
            "textureOscillationScore": best["textureOscillation"],
            "textureAmplitude": best["textureAmplitude"],
            "textureEdgePeakCount": best["textureEdgePeakCount"],
            "textureEdgePeakScore": best["textureEdgePeakScore"],
            "textureGradientPeakCount": best["textureGradientPeakCount"],
            "textureGradientPeakScore": best["textureGradientPeakScore"],
            "textureInnerAngularCoverage": best["textureInnerAngularCoverage"],
            "textureRadialRingCoverage": best["textureRadialRingCoverage"],
            "textureRadialRingCount": best["textureRadialRingCount"],
            "textureRadialRingScore": best["textureRadialRingScore"],
            "textureAngularConsistency": best["textureAngularConsistency"],
            "texturePeriodicitySupport": best["texturePeriodicitySupport"],
            "centerMean": best["centerMean"],
            "outerRingMean": best["outerRingMean"],
            "centerRingPolarity": best["centerRingPolarity"],
            "centralApertureScore": best["centralApertureScore"],
            "centralApertureScaleScore": best["centralApertureScaleScore"],
            "centralApertureRadiusRatio": best["centralApertureRadiusRatio"],
            "quality": quality,
            "sharpness": sharpness,
        }
        for rank, candidate in enumerate(refined_candidates[:5], start=1):
            metrics[f"refined{rank}CenterX"] = candidate["cx"] * scale_back
            metrics[f"refined{rank}CenterY"] = candidate["cy"] * scale_back
            metrics[f"refined{rank}Radius"] = candidate["radius"] * scale_back
            metrics[f"refined{rank}Score"] = candidate["refinementScore"]
            metrics[f"refined{rank}Texture"] = candidate["texture"]
            metrics[f"refined{rank}DarkCore"] = candidate["darkCoreScore"]
            metrics[f"refined{rank}CenterPolarity"] = candidate["centerRingPolarity"]
        texture_min = float(config.get("textureMin", 0.18))
        geometry_min = float(config.get("geometryMin", 0.30))
        pass_threshold = float(config.get("passThreshold", 0.50))
        if quality < float(config.get("qualityReviewMin", 0.04)) or sharpness < float(config.get("sharpnessReviewMin", 0.03)):
            status, message = "REVIEW", "circular evidence exists but image quality is low"
        elif best["geometry"] < geometry_min or best["texture"] < texture_min:
            status, message = "REVIEW", "circular aperture found but thread texture evidence is insufficient"
        elif best["texturePeriodicitySupport"] < float(config["texturePeriodicitySupportMin"]):
            status, message = "REVIEW", "plain circular aperture lacks repeated thread texture evidence"
        elif best["darkCoreScore"] < float(config["darkCoreMin"]):
            status, message = "REVIEW", "circular edge is not concentric with a dark center hole"
        elif best["angularCoverage"] < float(config.get("angularCoverageMin", 0.35)):
            status, message = "REVIEW", "circular edge support is concentrated in too few angles"
        elif best["backgroundPenalty"] > float(config["backgroundPenaltyMax"]):
            status, message = "REVIEW", "candidate circle crosses unsupported background structure"
        elif (
            best["centralApertureScore"]
            <= float(config["centralApertureMissingScoreMax"])
            and (
                best["textureAmplitude"]
                < float(config["centralApertureMissingTextureAmplitudeMin"])
                or (
                    best["darkCoreConfidence"]
                    >= float(config["centralApertureMissingConfidenceMin"])
                    and best["centerRingPolarity"]
                    >= float(config["centralApertureMissingPolarityMin"])
                )
            )
        ):
            status, message = "REVIEW", "central dark aperture evidence is insufficient for thread texture"
        elif score >= pass_threshold:
            status, message = "PASS", "refined circular aperture and internal periodic texture are present"
        else:
            status, message = "REVIEW", "thread evidence is between configured thresholds"
        box = [
            round(output_cx - output_radius),
            round(output_cy - output_radius),
            round(2 * output_radius),
            round(2 * output_radius),
        ]
        result = _result(self.algorithm, started, status, score, message, metrics, [box])
        if debug_path:
            result.debug_path = str(debug_path)
            self.write_debug(
                original_gray,
                original_mask,
                result,
                debug_path,
                (output_cx, output_cy, output_radius),
                output_candidates,
            )
        return result

    @staticmethod
    def write_debug(
        gray: np.ndarray,
        mask: np.ndarray,
        result: DetectionResult,
        path: Path,
        circle: tuple[float, float, float],
        raw_candidates: list[dict[str, float]] | None = None,
    ) -> None:
        canvas = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
        canvas[mask > 0] = (180, 0, 255)
        for candidate in (raw_candidates or [])[:8]:
            cv2.circle(
                canvas,
                (round(candidate["rawCx"]), round(candidate["rawCy"])),
                round(candidate["rawRadius"]),
                (0, 165, 255),
                1,
            )
            cv2.circle(canvas, (round(candidate["rawCx"]), round(candidate["rawCy"])), 2, (0, 165, 255), -1)
        cx, cy, radius = circle
        cv2.circle(canvas, (round(cx), round(cy)), round(radius), (0, 220, 0), 2)
        cv2.circle(canvas, (round(cx), round(cy)), max(1, round(radius * 0.65)), (255, 0, 0), 1)
        cv2.drawMarker(canvas, (round(cx), round(cy)), (255, 0, 0), cv2.MARKER_CROSS, 9, 1)
        metrics = result.metrics
        lines = [
            f"{result.status} {result.score or 0.0:.3f}",
            f"raw {metrics.get('rawCircleCenterX', 0):.1f},{metrics.get('rawCircleCenterY', 0):.1f} r{metrics.get('rawCircleRadius', 0):.1f}",
            f"ref {cx:.1f},{cy:.1f} r{radius:.1f} shift{metrics.get('refinementShiftPx', 0):.1f}",
            f"G{metrics.get('circleGeometryScore', 0):.2f} D{metrics.get('darkCoreScore', 0):.2f} T{metrics.get('texturePeriodicityScore', 0):.2f}",
            f"S{metrics.get('sharpness', 0):.2f} E{metrics.get('circleEdgeSupport', 0):.2f} C{metrics.get('innerOuterConcentricity', 0):.2f}",
        ]
        colors = [(0, 0, 255), (0, 165, 255), (0, 220, 0), (255, 255, 255), (255, 255, 255)]
        line_height = max(12, round(gray.shape[0] / 70))
        for index, (line, color) in enumerate(zip(lines, colors)):
            y = min(gray.shape[0] - 3, 15 + index * line_height)
            cv2.putText(canvas, line, (4, y), cv2.FONT_HERSHEY_SIMPLEX, max(0.28, min(0.55, gray.shape[0] / 220)), color, 1)
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


def _box_containment(first: list[int], second: list[int]) -> float:
    """Return overlap divided by the smaller box area for nested duplicates."""

    ax, ay, aw, ah = first
    bx, by, bw, bh = second
    x0, y0 = max(ax, bx), max(ay, by)
    x1, y1 = min(ax + aw, bx + bw), min(ay + ah, by + bh)
    intersection = max(0, x1 - x0) * max(0, y1 - y0)
    smaller_area = min(aw * ah, bw * bh)
    return intersection / smaller_area if smaller_area else 0.0


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


def _inner_contour_metrics(contour: np.ndarray, parent_box: list[int]) -> dict[str, Any] | None:
    """Describe a contour nested in a bright component without calling it a nut yet."""

    area = float(cv2.contourArea(contour))
    perimeter = float(cv2.arcLength(contour, True))
    if area <= 0.0 or perimeter <= 0.0:
        return None
    x, y, width, height = cv2.boundingRect(contour)
    parent_width, parent_height = parent_box[2], parent_box[3]
    area_ratio = area / max(parent_width * parent_height, 1)
    dimension_ratio = min(width / max(parent_width, 1), height / max(parent_height, 1))
    if area_ratio <= 0.0 or width <= 0 or height <= 0:
        return None

    polygon_counts = [
        len(cv2.approxPolyDP(contour, epsilon * perimeter, True))
        for epsilon in (0.02, 0.04, 0.06)
    ]
    vertices = polygon_counts[1]
    vertex_score = 1.0 - min(abs(vertices - 6) / 2.0, 1.0)
    stability_score = float(np.mean([1.0 - min(abs(count - 6) / 2.0, 1.0) for count in polygon_counts]))
    circularity = float(np.clip(4.0 * math.pi * area / (perimeter * perimeter), 0.0, 1.0))
    non_circular_score = float(np.clip((0.82 - circularity) / 0.55, 0.0, 1.0))
    hull_area = float(cv2.contourArea(cv2.convexHull(contour)))
    concavity_score = float(np.clip(1.0 - area / hull_area, 0.0, 1.0)) if hull_area else 0.0
    size_score = float(np.clip((dimension_ratio - 0.20) / 0.45, 0.0, 1.0))
    inner_score = float(
        np.clip(
            0.34 * vertex_score
            + 0.28 * stability_score
            + 0.20 * non_circular_score
            + 0.10 * concavity_score
            + 0.08 * size_score,
            0.0,
            1.0,
        )
    )
    kind = "side-face" if concavity_score >= 0.10 or circularity < 0.45 else "top-core"
    return {
        "contour": contour,
        "box": [x, y, width, height],
        "area": area,
        "areaRatio": area_ratio,
        "dimensionRatio": dimension_ratio,
        "vertices": vertices,
        "polygonStability": stability_score,
        "circularity": circularity,
        "concavity": concavity_score,
        "score": inner_score,
        "kind": kind,
    }


def _hexagon_for_box(box: list[int], angle: float) -> np.ndarray:
    x, y, width, height = box
    center = (round(x + width / 2.0), round(y + height / 2.0))
    axes = (max(2, round(width / 2.0)), max(2, round(height / 2.0)))
    return cv2.ellipse2Poly(center, axes, round(angle), 0, 360, 60)


def _hex_edge_support(edges: np.ndarray, box: list[int], angle: float) -> float:
    """Measure edge support along all six sides of a proposed body."""

    polygon = _hexagon_for_box(box, angle).reshape(-1, 2)
    supports: list[bool] = []
    for first, second in zip(polygon, np.roll(polygon, -1, axis=0)):
        steps = max(6, int(max(box[2], box[3])))
        for fraction in np.linspace(0.0, 1.0, steps):
            px, py = np.rint(first * (1.0 - fraction) + second * fraction).astype(int)
            if not (0 <= px < edges.shape[1] and 0 <= py < edges.shape[0]):
                continue
            y0, y1 = max(0, py - 1), min(edges.shape[0], py + 2)
            x0, x1 = max(0, px - 1), min(edges.shape[1], px + 2)
            supports.append(bool(np.any(edges[y0:y1, x0:x1] > 0)))
    return float(np.mean(supports)) if supports else 0.0


def _make_body_candidate(
    parent: dict[str, Any],
    inner: dict[str, Any],
    gray: np.ndarray,
    edges: np.ndarray,
    config: dict[str, Any],
    threshold_name: str,
) -> dict[str, Any] | None:
    """Create a body proposal from an outer component and inner shape evidence."""

    outer_x, outer_y, outer_width, outer_height = parent["box"]
    inner_x, inner_y, inner_width, inner_height = inner["box"]
    body_width = max(
        8,
        round(outer_width * float(config["bodyWidthRatio"])),
        round(inner_width * float(config["bodyInnerWidthExpansion"])),
    )
    inner_height_expansion = (
        float(config["bodySideFaceHeightExpansion"])
        if inner["kind"] == "side-face"
        else float(config["bodyInnerHeightExpansion"])
    )
    body_height = max(
        8,
        round(outer_height * float(config["bodyHeightRatio"])),
        round(inner_height * inner_height_expansion),
    )
    body_width = min(body_width, max(8, round(outer_width * 0.94)))
    body_height = min(body_height, max(8, round(outer_height * 0.94)))
    outer_center_x = outer_x + outer_width / 2.0
    outer_center_y = outer_y + outer_height / 2.0
    inner_center_x = inner_x + inner_width / 2.0
    inner_center_y = inner_y + inner_height / 2.0
    center_x = 0.35 * outer_center_x + 0.65 * inner_center_x
    center_y = 0.35 * outer_center_y + 0.65 * inner_center_y
    if inner["kind"] == "side-face":
        center_y += outer_height * float(config["bodyCenterYOffsetRatio"])
    x = round(center_x - body_width / 2.0)
    y = round(center_y - body_height / 2.0)
    # A recovered body must remain inside the component that supplied its
    # evidence; this prevents a slanted/background edge from pulling the
    # fitted hexagon outside the washer/nut assembly.
    x = max(outer_x, min(x, outer_x + outer_width - body_width))
    y = max(outer_y, min(y, outer_y + outer_height - body_height))
    x = max(0, min(x, gray.shape[1] - body_width))
    y = max(0, min(y, gray.shape[0] - body_height))
    box = [x, y, body_width, body_height]
    body_aspect = min(body_width / body_height, body_height / body_width)
    if body_aspect < float(config.get("minAspectRatio", 0.62)):
        return None

    angles = [float(value) for value in config.get("bodyHexAngleCandidates", [config["bodyHexAngle"]])]
    angles = [value for value in angles if math.isfinite(value)] or [float(config["bodyHexAngle"])]
    angle, hex_edge_score = max(
        ((_angle, _hex_edge_support(edges, box, _angle)) for _angle in angles),
        key=lambda item: item[1],
    )
    polygon = _hexagon_for_box(box, angle)
    polygon_area = float(cv2.contourArea(polygon))
    polygon_perimeter = float(cv2.arcLength(polygon, True))
    circularity = float(
        np.clip(4.0 * math.pi * polygon_area / max(polygon_perimeter * polygon_perimeter, 1.0), 0.0, 1.0)
    )
    hole_score, hole_radius, hole_edge, hole_contrast = _center_hole_evidence(gray, box, config)
    local_edges = edges[y : y + body_height, x : x + body_width]
    edge_density = float(np.mean(local_edges > 0)) if local_edges.size else 0.0
    geometry_score = float(
        np.clip(
            0.48 * inner["score"]
            + 0.30 * inner["polygonStability"]
            + 0.22 * hex_edge_score,
            0.0,
            1.0,
        )
    )
    score = float(
        np.clip(
            0.34 * geometry_score
            + 0.30 * hole_score
            + 0.22 * inner["score"]
            + 0.14 * hex_edge_score,
            0.0,
            1.0,
        )
    )
    return {
        "contour": polygon,
        "box": box,
        "area": polygon_area,
        "score": score,
        "geometryScore": geometry_score,
        "holeScore": hole_score,
        "holeRadius": hole_radius,
        "holeEdgeScore": hole_edge,
        "holeContrastScore": hole_contrast,
        "vertices": 6,
        "aspect": body_aspect,
        "fillRatio": polygon_area / max(body_width * body_height, 1),
        "solidity": 1.0,
        "circularity": circularity,
        "edgeDensity": edge_density,
        "thresholdVariant": threshold_name,
        "hexAngle": angle,
        "candidateSource": f"nested-{inner['kind']}:{threshold_name}",
        "candidateType": "hex-body",
        "bodyEvidenceScore": float(inner["score"]),
        "hexEdgeScore": hex_edge_score,
        "innerContourBox": inner["box"],
        "hullRecovery": 0.0,
    }


class NutPresenceDetector:
    algorithm = "NutPresenceDetector"

    def detect(
        self,
        image: np.ndarray,
        config: dict[str, Any] | None = None,
        debug_path: Path | None = None,
    ) -> DetectionResult:
        started = time.perf_counter()
        config = {**NUT_DEFAULTS, **(config or {})}
        invalid = validate_image(image)
        if invalid:
            return _result(self.algorithm, started, "ERROR", None, invalid)
        gray, mask, mask_metrics = masked_gray(image)
        height, width = gray.shape[:2]
        if min(height, width) < 24:
            return _result(self.algorithm, started, "ERROR", None, "ROI is too small for polygon evidence")
        enhanced = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(gray)
        edges = cv2.Canny(enhanced, float(config.get("cannyLow", 50.0)), float(config.get("cannyHigh", 150.0)))
        candidates: list[dict[str, Any]] = []
        contour_branches: list[tuple[str, list[np.ndarray], np.ndarray | None, dict[int, dict[str, Any]]]] = []
        otsu_value, otsu_threshold = cv2.threshold(
            enhanced, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU
        )
        threshold_variants = [(f"otsu:{otsu_value:.0f}", otsu_threshold)]
        for value in config["brightThresholds"]:
            threshold_variants.append((f"bright:{int(value)}", (enhanced >= int(value)).astype(np.uint8) * 255))

        def collect_contour(
            contour: np.ndarray,
            threshold_name: str,
            use_hull: bool,
            contour_index: int,
        ) -> dict[str, Any] | None:
            original_area = float(cv2.contourArea(contour))
            hull = cv2.convexHull(contour)
            hull_area = float(cv2.contourArea(hull))
            solidity = original_area / hull_area if hull_area else 0.0
            if use_hull:
                if solidity < float(config["hullRecoveryMinSolidity"]):
                    return None
                shape = hull
            else:
                shape = contour
            area = float(cv2.contourArea(shape))
            if area < max(20.0, height * width * float(config.get("minAreaRatio", 0.004))) or area > height * width * float(config.get("maxAreaRatio", 0.85)):
                return None
            perimeter = float(cv2.arcLength(shape, True))
            if perimeter <= 0:
                return None
            polygon = cv2.approxPolyDP(shape, float(config.get("epsilonRatio", 0.04)) * perimeter, True)
            vertices = len(polygon)
            if vertices < int(config.get("minVertices", 5)) or vertices > int(config.get("maxVertices", 7)):
                return None
            if not cv2.isContourConvex(polygon):
                return None
            # Use the accepted contour for the returned box.  The simplified
            # polygon is only the geometry test; its vertices can cut off a
            # sloped side face that is still part of the nut body.
            x, y, box_width, box_height = cv2.boundingRect(shape)
            if box_width <= 0 or box_height <= 0:
                return None
            aspect = min(box_width / box_height, box_height / box_width)
            if aspect < float(config.get("minAspectRatio", 0.62)):
                return None
            fill_ratio = area / float(box_width * box_height)
            if fill_ratio < float(config.get("minFillRatio", 0.45)) or fill_ratio > float(config.get("maxFillRatio", 0.98)):
                return None
            if solidity < float(config.get("minSolidity", 0.82)):
                return None
            circularity = float(np.clip(4.0 * math.pi * area / (perimeter * perimeter), 0.0, 1.0))
            hole_score, hole_radius, hole_edge, hole_contrast = _center_hole_evidence(gray, [x, y, box_width, box_height], config)
            if hole_score < float(config.get("holeReviewMin", 0.05)):
                return None
            local_edges = edges[y : y + box_height, x : x + box_width]
            edge_density = float(np.mean(local_edges > 0)) if local_edges.size else 0.0
            vertex_score = 1.0 - min(abs(vertices - 6) / 2.0, 1.0)
            fill_score = float(np.clip(1.0 - abs(fill_ratio - 0.72) / 0.35, 0.0, 1.0))
            aspect_score = float(np.clip((aspect - float(config.get("minAspectRatio", 0.62))) / 0.38, 0.0, 1.0))
            geometry_score = float(np.clip(0.40 * vertex_score + 0.25 * fill_score + 0.20 * solidity + 0.15 * aspect_score, 0.0, 1.0))
            score = float(np.clip(0.58 * geometry_score + 0.18 * hole_score + 0.14 * circularity + 0.10 * np.clip(edge_density * 3.0, 0.0, 1.0), 0.0, 1.0))
            candidate = {
                "area": area,
                "contour": shape,
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
                "thresholdVariant": threshold_name,
                "candidateSource": f"contour:{threshold_name}:{'hull' if use_hull else 'raw'}",
                "candidateType": "contour",
                "bodyEvidenceScore": 0.0,
                "hexEdgeScore": 0.0,
                "contourIndex": float(contour_index),
                "hullRecovery": 1.0 if use_hull else 0.0,
            }
            candidates.append(candidate)
            return candidate

        for threshold_name, threshold in threshold_variants:
            threshold = cv2.morphologyEx(threshold, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
            contours, hierarchy = cv2.findContours(threshold, cv2.RETR_TREE, cv2.CHAIN_APPROX_SIMPLE)
            raw_by_index: dict[int, dict[str, Any]] = {}
            for contour_index, contour in enumerate(contours):
                raw_candidate = collect_contour(contour, threshold_name, use_hull=False, contour_index=contour_index)
                if raw_candidate is not None:
                    raw_by_index[contour_index] = raw_candidate
                collect_contour(contour, threshold_name, use_hull=True, contour_index=contour_index)
            contour_branches.append((threshold_name, contours, hierarchy, raw_by_index))

        # Bright outer components contain a washer plus a smaller, often
        # concave side face or top core.  That nested shape is the structural
        # evidence used to replace the washer-sized contour with a body box.
        body_candidates: list[dict[str, Any]] = []
        for threshold_name, contours, hierarchy, raw_by_index in contour_branches:
            if hierarchy is None:
                continue
            for inner_index, inner_contour in enumerate(contours):
                inner_box = list(cv2.boundingRect(inner_contour))
                possible_parents = []
                inner_area = float(cv2.contourArea(inner_contour))
                ancestor_index = int(hierarchy[0][inner_index][3])
                while ancestor_index >= 0:
                    parent_index = ancestor_index
                    parent_contour = contours[parent_index]
                    parent_area = float(cv2.contourArea(parent_contour))
                    if parent_area <= 0.0 or inner_area <= 0.0:
                        ancestor_index = int(hierarchy[0][parent_index][3])
                        continue
                    if parent_area >= max(20.0, height * width * float(config.get("minAreaRatio", 0.004))) and parent_area <= height * width * float(config.get("maxAreaRatio", 0.85)) and inner_area < parent_area * float(config["bodyInnerMaxAreaRatio"]):
                        parent_box = list(cv2.boundingRect(parent_contour))
                        if _box_containment(inner_box, parent_box) >= 0.80:
                            inner = _inner_contour_metrics(inner_contour, parent_box)
                            if (
                                inner is not None
                                and inner["areaRatio"] >= float(config["bodyInnerMinAreaRatio"])
                                and inner["dimensionRatio"] >= float(config["bodyInnerMinDimensionRatio"])
                                and inner["score"] >= float(config["bodyInnerMinScore"])
                            ):
                                parent = {
                                    "contour": parent_contour,
                                    "box": parent_box,
                                    "area": parent_area,
                                    "candidateType": "component",
                                    "contourIndex": float(parent_index),
                                }
                                possible_parents.append((parent_area, parent, inner))
                    ancestor_index = int(hierarchy[0][parent_index][3])
                if not possible_parents:
                    continue
                _, parent, inner = max(possible_parents, key=lambda item: item[0])
                body = _make_body_candidate(parent, inner, gray, edges, config, threshold_name)
                if body is not None and body["holeScore"] >= float(config["bodyHoleMinScore"]):
                    body_candidates.append(body)

        # A bright threshold can split the same nut into sibling contours
        # rather than a RETR_TREE parent/child pair.  Re-associate an already
        # accepted near-hex contour with a larger component from another
        # threshold branch.  The larger component is still bounded by the
        # normal area limits, so image-border/background contours cannot be
        # promoted to a body box.
        components: list[dict[str, Any]] = []
        for component_threshold, component_contours, _, _ in contour_branches:
            for component_index, component_contour in enumerate(component_contours):
                component_area = float(cv2.contourArea(component_contour))
                if component_area < max(20.0, height * width * float(config.get("minAreaRatio", 0.004))):
                    continue
                if component_area > height * width * float(config.get("maxAreaRatio", 0.85)):
                    continue
                components.append(
                    {
                        "contour": component_contour,
                        "box": list(cv2.boundingRect(component_contour)),
                        "area": component_area,
                        "thresholdVariant": component_threshold,
                        "contourIndex": component_index,
                    }
                )
        for inner_threshold, _, _, raw_by_index in contour_branches:
            for raw in raw_by_index.values():
                inner_area = float(raw["area"])
                inner_box = raw["box"]
                for parent in components:
                    if parent["thresholdVariant"] == inner_threshold:
                        continue
                    if parent["area"] <= inner_area * 1.25:
                        continue
                    if _box_containment(inner_box, parent["box"]) < 0.80:
                        continue
                    inner = _inner_contour_metrics(raw["contour"], parent["box"])
                    if inner is None:
                        continue
                    if inner["areaRatio"] < float(config["bodyInnerMinAreaRatio"]):
                        continue
                    if inner["dimensionRatio"] < float(config["bodyInnerMinDimensionRatio"]):
                        continue
                    if inner["score"] < float(config["bodyInnerMinScore"]):
                        continue
                    body = _make_body_candidate(
                        parent,
                        inner,
                        gray,
                        edges,
                        config,
                        f"{inner_threshold}<-{parent['thresholdVariant']}",
                    )
                    if body is not None and body["holeScore"] >= float(config["bodyHoleMinScore"]):
                        body_candidates.append(body)
        candidates.extend(body_candidates)
        raw_candidates = list(candidates)
        candidates.sort(key=lambda item: item["score"], reverse=True)
        selected: list[dict[str, Any]] = []

        def nms_rank(candidate: dict[str, Any]) -> tuple[int, float]:
            # A body proposal is allowed to replace a washer-sized contour
            # only when nested shape evidence is strong enough.  This keeps
            # the replacement explainable and avoids selecting a larger
            # background/washer outline merely because it has more pixels.
            body_evidence = float(candidate.get("bodyEvidenceScore", 0.0))
            is_recovered_body = (
                candidate.get("candidateType") == "hex-body"
                and body_evidence >= float(config["bodyNmsMinEvidence"])
            )
            return (1 if is_recovered_body else 0, float(candidate["score"]))

        for candidate in candidates:
            duplicate_indices: list[int] = []
            for index, existing in enumerate(selected):
                iou_duplicate = _box_iou(candidate["box"], existing["box"]) > float(config.get("nmsIou", 0.45))
                containment_duplicate = _box_containment(candidate["box"], existing["box"]) > float(config["nmsContainment"])
                if iou_duplicate or containment_duplicate:
                    duplicate_indices.append(index)
            if not duplicate_indices:
                selected.append(candidate)
            elif all(nms_rank(candidate) > nms_rank(selected[index]) for index in duplicate_indices):
                selected[duplicate_indices[0]] = candidate
                for index in reversed(duplicate_indices[1:]):
                    del selected[index]
        boxes = [item["box"] for item in selected]
        average_score = float(np.mean([item["score"] for item in selected])) if selected else 0.0
        metrics = {
            **mask_metrics,
            "candidateCountBeforeNms": float(len(candidates)),
            "candidateCount": float(len(selected)),
            "nmsSuppressedCount": float(max(0, len(candidates) - len(selected))),
            "bodyCandidateCountBeforeNms": float(len(body_candidates)),
            "selectedBodyCount": float(sum(item.get("candidateType") == "hex-body" for item in selected)),
            "averageBodyEvidenceScore": float(np.mean([item.get("bodyEvidenceScore", 0.0) for item in selected])) if selected else 0.0,
            "averageGeometryScore": float(np.mean([item["geometryScore"] for item in selected])) if selected else 0.0,
            "averageCenterHoleScore": float(np.mean([item["holeScore"] for item in selected])) if selected else 0.0,
            "minCenterHoleScore": float(min((item["holeScore"] for item in selected), default=0.0)),
            "averageCircularity": float(np.mean([item["circularity"] for item in selected])) if selected else 0.0,
            "averageEdgeDensity": float(np.mean([item["edgeDensity"] for item in selected])) if selected else 0.0,
            "quality": _quality(gray),
        }
        for index, candidate in enumerate(selected, start=1):
            metrics[f"box{index}Score"] = float(candidate["score"])
            metrics[f"box{index}GeometryScore"] = float(candidate["geometryScore"])
            metrics[f"box{index}CenterHoleScore"] = float(candidate["holeScore"])
            metrics[f"box{index}BodyEvidenceScore"] = float(candidate.get("bodyEvidenceScore", 0.0))
            metrics[f"box{index}HexAngle"] = float(candidate.get("hexAngle", 0.0))
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
            self.write_debug(gray, mask, result, debug_path, selected, raw_candidates)
        return result

    @staticmethod
    def write_debug(
        gray: np.ndarray,
        mask: np.ndarray,
        result: DetectionResult,
        path: Path,
        candidates: list[dict[str, Any]],
        raw_candidates: list[dict[str, Any]] | None = None,
    ) -> None:
        """Render raw candidates, NMS survivors, and the score audit table."""

        canvas = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
        canvas[mask > 0] = (180, 0, 255)
        raw = raw_candidates if raw_candidates is not None else candidates
        for index, candidate in enumerate(raw, start=1):
            x, y, box_width, box_height = candidate["box"]
            cv2.rectangle(canvas, (x, y), (x + box_width, y + box_height), (0, 165, 255), 1)
            cv2.putText(canvas, f"R{index}", (x, max(11, y - 3)), cv2.FONT_HERSHEY_SIMPLEX, 0.34, (0, 165, 255), 1)
        for index, candidate in enumerate(candidates, start=1):
            cv2.drawContours(canvas, [candidate["contour"]], -1, (0, 220, 0), 2)
            x, y, box_width, box_height = candidate["box"]
            cv2.rectangle(canvas, (x, y), (x + box_width, y + box_height), (0, 220, 0), 2)
            cv2.putText(canvas, f"F{index}", (x + 2, min(gray.shape[0] - 3, y + 13)), cv2.FONT_HERSHEY_SIMPLEX, 0.40, (0, 220, 0), 1)

        row_height = 16
        panel_rows = len(raw) + len(candidates) + 4
        panel_height = max(72, row_height * panel_rows)
        panel = np.full((panel_height, canvas.shape[1], 3), 32, dtype=np.uint8)
        panel_width = panel.shape[1]
        scale = 0.38 if panel_width >= 700 else 0.30
        cv2.putText(
            panel,
            f"Nut {result.status} score={result.score or 0.0:.3f} raw={len(raw)} final={len(candidates)}",
            (6, 14),
            cv2.FONT_HERSHEY_SIMPLEX,
            scale,
            (255, 255, 255),
            1,
        )
        cv2.putText(panel, "orange=raw candidate  green=NMS final box  source includes threshold branch", (6, 30), cv2.FONT_HERSHEY_SIMPLEX, scale, (180, 220, 255), 1)

        def table_line(prefix: str, index: int, candidate: dict[str, Any]) -> str:
            x, y, box_width, box_height = candidate["box"]
            source = str(candidate.get("candidateSource", "unknown"))
            return (
                f"{prefix}{index:02d} C={float(candidate.get('score', 0.0)):.2f} "
                f"G={float(candidate.get('geometryScore', 0.0)):.2f} "
                f"H={float(candidate.get('holeScore', 0.0)):.2f} "
                f"A={float(candidate.get('hexAngle', 0.0)):.0f} "
                f"box=({x},{y},{box_width},{box_height}) {source}"
            )

        row = 3
        for index, candidate in enumerate(raw, start=1):
            cv2.putText(panel, table_line("R", index, candidate), (6, row * row_height + 11), cv2.FONT_HERSHEY_SIMPLEX, scale, (0, 165, 255), 1)
            row += 1
        for index, candidate in enumerate(candidates, start=1):
            cv2.putText(panel, table_line("F", index, candidate), (6, row * row_height + 11), cv2.FONT_HERSHEY_SIMPLEX, scale, (0, 220, 0), 1)
            row += 1
        cv2.putText(panel, "F rows are the boxes returned in DetectionResult.boxes; R rows are before IoU/containment NMS.", (6, row * row_height + 11), cv2.FONT_HERSHEY_SIMPLEX, scale, (220, 220, 220), 1)
        canvas = np.vstack([canvas, panel])
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
