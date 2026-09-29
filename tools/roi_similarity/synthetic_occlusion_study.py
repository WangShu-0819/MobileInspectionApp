#!/usr/bin/env python3
"""Score response to synthetic occlusion on annotated thread ROIs."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
from typing import Any

import cv2
import numpy as np

import evaluate_roi_similarity as base


TRANSFORMS = ("identity", "perspective_moderate", "combined_moderate")
COVERAGE_LEVELS = (0.10, 0.25, 0.50, 0.75, 1.00)
POSITIONS = ("top", "center", "bottom")
TRIAL_CANDIDATE_SSIM = 0.95


def target_boxes(entry: dict[str, Any], classes: list[str]) -> list[tuple[int, list[float]]]:
    result = []
    for index, (class_id, box) in enumerate(zip(entry["class_ids"], entry["boxes"])):
        if class_id < len(classes) and "thread" in classes[class_id].casefold():
            result.append((index, box))
    return result


def project_box(box: list[float], homography: np.ndarray, width: int, height: int) -> tuple[int, int, int, int]:
    x0, y0, x1, y1 = box
    corners = np.float32([[x0, y0], [x1, y0], [x1, y1], [x0, y1]]).reshape(-1, 1, 2)
    points = cv2.perspectiveTransform(corners, homography).reshape(-1, 2)
    return (
        max(0, int(np.floor(points[:, 0].min()))),
        max(0, int(np.floor(points[:, 1].min()))),
        min(width, int(np.ceil(points[:, 0].max()))),
        min(height, int(np.ceil(points[:, 1].max()))),
    )


def background_median(image: np.ndarray, box: tuple[int, int, int, int]) -> np.ndarray:
    x0, y0, x1, y1 = box
    height, width = image.shape[:2]
    outer = np.zeros((height, width), dtype=np.uint8)
    outer[max(0, y0 - 12):min(height, y1 + 12), max(0, x0 - 12):min(width, x1 + 12)] = 255
    inner = np.zeros_like(outer)
    inner[y0:y1, x0:x1] = 255
    ring = image[(outer > 0) & (inner == 0)]
    return np.median(ring, axis=0).astype(np.uint8) if len(ring) else np.median(image.reshape(-1, 3), axis=0).astype(np.uint8)


def cover_band(image: np.ndarray, box: tuple[int, int, int, int], fraction: float, position: str) -> np.ndarray:
    x0, y0, x1, y1 = box
    result = image.copy()
    roi_height = y1 - y0
    cover_height = max(1, min(roi_height, round(roi_height * fraction)))
    starts = {
        "top": y0,
        "center": y0 + (roi_height - cover_height) // 2,
        "bottom": y1 - cover_height,
    }
    top = starts[position]
    result[top:top + cover_height, x0:x1] = background_median(image, box)
    return result


def score_roi(reference: np.ndarray, query: np.ndarray, valid_mask: np.ndarray, box: list[float]) -> dict[str, Any]:
    ref_gray = cv2.cvtColor(reference, cv2.COLOR_BGR2GRAY)
    query_gray = cv2.cvtColor(query, cv2.COLOR_BGR2GRAY)
    return base.roi_similarity(ref_gray, query_gray, valid_mask, [box])


def run(dataset: Path, output: Path, seed: int) -> None:
    entries, labels_dir, classes = base.inventory_dataset(dataset)
    if labels_dir is None:
        raise ValueError(f"thread ROI labels not found beside {dataset}")
    rows: list[dict[str, Any]] = []
    target_count = 0
    skipped: list[str] = []

    for entry in entries:
        targets = target_boxes(entry, classes)
        if not targets:
            continue
        target_count += len(targets)
        image = entry["image"]
        boxes = [box for _, box in targets]
        specs = {spec["key"]: spec for spec in base.transform_specs(entry["width"], entry["height"], entry["filename"], seed)}
        for transform_key in TRANSFORMS:
            spec = specs[transform_key]
            query, warp_mask = base.warp_case(image, spec["homography"])
            registration = base.registration(image, query, boxes)
            if not registration["quality_gates_pass"]:
                skipped.append(f"{entry['filename']}|{transform_key}|clean|{registration['failure_reason']}")
                continue
            aligned, valid_mask = base.align_query(query, warp_mask, registration["estimated_homography"], image.shape[:2])
            for roi_index, box in targets:
                score = score_roi(image, aligned, valid_mask, box)
                rows.append({
                    "case_kind": "clean_geometry", "filename": entry["filename"], "class_id": entry["class_ids"][roi_index],
                    "roi_index": roi_index, "transform": transform_key, "coverage": 0.0, "position": "none",
                    "registration_status": registration["registration_status"], "ssim": score["ssim_mean"],
                    "gradient_ssim": score["gradient_ssim_mean"], "valid_roi_ratio": score["valid_roi_ratio"],
                    "registration_failure": "",
                })

            for roi_index, box in targets:
                projected = project_box(box, spec["homography"], query.shape[1], query.shape[0])
                if projected[2] <= projected[0] or projected[3] <= projected[1]:
                    continue
                for fraction in COVERAGE_LEVELS:
                    for position in POSITIONS:
                        occluded = cover_band(query, projected, fraction, position)
                        masked_registration = base.registration(image, occluded, boxes)
                        if not masked_registration["quality_gates_pass"]:
                            skipped.append(f"{entry['filename']}|{transform_key}|{fraction:.2f}|{position}|{masked_registration['failure_reason']}")
                            rows.append({
                                "case_kind": "synthetic_occlusion", "filename": entry["filename"], "class_id": entry["class_ids"][roi_index],
                                "roi_index": roi_index, "transform": transform_key, "coverage": fraction, "position": position,
                                "registration_status": masked_registration["registration_status"], "ssim": "",
                                "gradient_ssim": "", "valid_roi_ratio": "", "registration_failure": masked_registration["failure_reason"],
                            })
                            continue
                        aligned_masked, masked_valid = base.align_query(occluded, warp_mask, masked_registration["estimated_homography"], image.shape[:2])
                        score = score_roi(image, aligned_masked, masked_valid, box)
                        rows.append({
                            "case_kind": "synthetic_occlusion", "filename": entry["filename"], "class_id": entry["class_ids"][roi_index],
                            "roi_index": roi_index, "transform": transform_key, "coverage": fraction, "position": position,
                            "registration_status": masked_registration["registration_status"], "ssim": score["ssim_mean"],
                            "gradient_ssim": score["gradient_ssim_mean"], "valid_roi_ratio": score["valid_roi_ratio"],
                            "registration_failure": "",
                        })

    output.mkdir(parents=True, exist_ok=True)
    fields = list(rows[0]) if rows else []
    with (output / "scores.csv").open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)

    clean_scores = [float(row["ssim"]) for row in rows if row["case_kind"] == "clean_geometry" and row["ssim"] != ""]
    severe = [row for row in rows if row["case_kind"] == "synthetic_occlusion" and float(row["coverage"]) >= 0.75 and row["ssim"] != ""]
    thresholds = []
    for step in range(160, 201):
        threshold = step / 200.0
        thresholds.append({
            "ssim_threshold": threshold,
            "clean_geometry_pass_count": sum(value >= threshold for value in clean_scores),
            "clean_geometry_count": len(clean_scores),
            "severe_synthetic_pass_count": sum(float(row["ssim"]) >= threshold for row in severe),
            "severe_synthetic_count": len(severe),
        })
    with (output / "threshold_sweep.csv").open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(thresholds[0]))
        writer.writeheader()
        writer.writerows(thresholds)

    coverage_summary = []
    for fraction in COVERAGE_LEVELS:
        group = [row for row in rows if row["case_kind"] == "synthetic_occlusion" and float(row["coverage"]) == fraction]
        values = [float(row["ssim"]) for row in group if row["ssim"] != ""]
        coverage_summary.append({
            "coverage": fraction, "case_count": len(group), "scored_count": len(values),
            "registration_rejected_count": len(group) - len(values),
            "ssim_median": float(np.median(values)) if values else None,
            "ssim_max": max(values) if values else None,
            "pass_at_trial_candidate_count": sum(value >= TRIAL_CANDIDATE_SSIM for value in values),
            "pass_at_0_90_count": sum(value >= 0.90 for value in values),
        })
    with (output / "coverage_summary.csv").open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(coverage_summary[0]))
        writer.writeheader()
        writer.writerows(coverage_summary)

    key_role = dataset.parent / "Key_role"
    key_role_files = sorted(key_role.glob("*.jpg")) if key_role.is_dir() else []
    key_role_dimensions: dict[str, int] = {}
    for path in key_role_files:
        image = cv2.imread(str(path), cv2.IMREAD_COLOR)
        if image is not None:
            key = f"{image.shape[1]}x{image.shape[0]}"
            key_role_dimensions[key] = key_role_dimensions.get(key, 0) + 1

    summary = {
        "dataset": str(dataset), "labels_directory": str(labels_dir), "seed": seed,
        "dataset_inventory": {
            "image_count": len(entries),
            "matched_label_count": sum(entry["label_path"] is not None and entry["label_path"].exists() for entry in entries),
            "roi_box_count": sum(len(entry["boxes"]) for entry in entries),
            "thread_roi_count": target_count, "thread_roi_classes": ["Black Thread"],
        },
        "images_with_thread_rois": len({row["filename"] for row in rows}), "thread_roi_count": target_count,
        "transforms": list(TRANSFORMS), "coverage_levels": list(COVERAGE_LEVELS), "positions": list(POSITIONS),
        "case_count": len(rows), "clean_geometry_scored_count": len(clean_scores),
        "synthetic_occlusion_scored_count": sum(row["case_kind"] == "synthetic_occlusion" and row["ssim"] != "" for row in rows),
        "registration_rejected_count": len(skipped), "clean_ssim_min": min(clean_scores) if clean_scores else None,
        "clean_ssim_p05": float(np.percentile(clean_scores, 5)) if clean_scores else None,
        "clean_ssim_median": float(np.median(clean_scores)) if clean_scores else None,
        "severe_synthetic_scored_count": len(severe),
        "severe_synthetic_ssim_max": max((float(row["ssim"]) for row in severe), default=None),
        "trial_candidate": {
            "metric": "mean grayscale SSIM over the valid ROI core",
            "threshold": TRIAL_CANDIDATE_SSIM,
            "clean_geometry_pass_count": sum(value >= TRIAL_CANDIDATE_SSIM for value in clean_scores),
            "clean_geometry_count": len(clean_scores),
            "severe_synthetic_pass_count": sum(float(row["ssim"]) >= TRIAL_CANDIDATE_SSIM for row in severe),
            "severe_synthetic_count": len(severe),
            "status": "supervised Black Thread field-trial candidate; not a validated business threshold",
        },
        "coverage_summary": coverage_summary,
        "key_role": {
            "directory": str(key_role), "image_count": len(key_role_files),
            "image_dimensions": key_role_dimensions,
            "labeled": (dataset.parent / "Key_role_label").is_dir(),
            "pairing_status": "not established", "expected_hole_semantics": "unknown",
            "used_as_ground_truth": False,
        },
        "limitations": [
            "All scores are same-image synthetic geometry/occlusion responses, not real absent-part or defect labels.",
            "Python registration omits the App custom GMS and does not establish Android V4 parity.",
            "The solid median-background band is an artificial occlusion, not a physically removed part.",
            "Key_role has no verified pairing map and its expected-hole semantics are uncertain, so it is excluded from threshold selection.",
        ],
    }
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, default=base.DEFAULT_DATASET)
    parser.add_argument("--output", type=Path, default=Path("docs/reports/b3/roi_similarity/synthetic_occlusion_20260927"))
    parser.add_argument("--seed", type=int, default=20260927)
    args = parser.parse_args()
    run(args.dataset, args.output, args.seed)


if __name__ == "__main__":
    main()
