"""Run offline Task 2-4 smoke evaluation without inventing DCIM labels."""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path
from typing import Any

import cv2

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from presence_detectors import ALGORITHM_VERSION, detector_for, load_bgr  # noqa: E402


def record(
    image_id: str,
    file_name: str,
    target_id: str,
    label: str,
    roi: list[float] | None,
    result: dict[str, Any],
    evaluation_set: str,
) -> dict[str, Any]:
    return {
        "imageId": image_id,
        "fileName": file_name,
        "targetId": target_id,
        "label": label,
        "roi": roi,
        "predictedStatus": result["status"],
        "score": result["score"],
        "metrics": result["metrics"],
        "algorithm": result["algorithm"],
        "algorithmVersion": result["algorithmVersion"],
        "durationMs": result["durationMs"],
        "message": result["message"],
        "debugPath": result.get("debugPath"),
        "evaluationSet": evaluation_set,
        "evaluableForMetrics": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default="docs/reports/b3/feature_presence/dataset_manifest.json")
    parser.add_argument("--ground-truth", default="docs/reports/b3/feature_presence/ground_truth.json")
    parser.add_argument("--output-dir", default="docs/reports/b3/feature_presence/evaluation")
    parser.add_argument("--nut-expected-count", type=int, default=None, help="Optional runtime calibration; not written into dataset manifest")
    args = parser.parse_args()
    manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
    ground_truth = json.loads(Path(args.ground_truth).read_text(encoding="utf-8"))
    output_dir = Path(args.output_dir)
    debug_dir = output_dir / "debug"
    output_dir.mkdir(parents=True, exist_ok=True)
    rows: list[dict[str, Any]] = []
    key_records = manifest["inventory"]["key"]["images"]
    key_by_target: dict[str, list[dict[str, Any]]] = {}
    for image in key_records:
        for target in image["targets"]:
            key_by_target.setdefault(target["targetId"], []).append({"image": image, "target": target})
    for target_id, items in key_by_target.items():
        profile = next(item for item in manifest["targetProfiles"] if item["targetId"] == target_id)
        for item in items:
            image = load_bgr(item["image"]["sourcePath"])
            detector = detector_for(profile["inspectionType"])
            config: dict[str, Any] = {}
            if target_id == "nut":
                # This is runtime self-check calibration from the observed Key sample;
                # the detector and dataset manifest keep expectedCount configurable.
                config["expectedCount"] = args.nut_expected_count if args.nut_expected_count is not None else profile["observedTemplateCount"]
            debug_path = debug_dir / "key" / f"{target_id}__{item['image']['fileName']}.jpg"
            if target_id == "feature_1" or target_id == "feature_2":
                result = detector.detect(image, template=image.copy(), config=config, debug_path=debug_path)
            else:
                result = detector.detect(image, config=config, debug_path=debug_path)
            rows.append(record(item["image"]["imageId"], item["image"]["fileName"], target_id, "unknown", item["target"]["roi"], result.as_dict(), "KEY_SELF_CHECK"))
    for image in manifest["inventory"]["dcim"]["images"]:
        gt_image = next(item for item in ground_truth["images"] if item["imageId"] == image["imageId"])
        for target in gt_image["targets"]:
            result = {
                "status": "SKIPPED",
                "score": None,
                "metrics": {},
                "algorithm": next(item for item in manifest["targetProfiles"] if item["targetId"] == target["targetId"])["inspectionType"],
                "algorithmVersion": "not_run_without_human_roi",
                "durationMs": 0,
                "message": "DCIM target ROI is unannotated; image is not searched and label remains unknown.",
                "debugPath": None,
            }
            rows.append(record(image["imageId"], image["fileName"], target["targetId"], "unknown", None, result, "DCIM_UNANNOTATED_SMOKE_ONLY"))
    json_path = output_dir / "offline_evaluation.json"
    csv_path = output_dir / "offline_evaluation.csv"
    json_path.write_text(
        json.dumps({"status": "INSUFFICIENT_DATA", "algorithmVersion": ALGORITHM_VERSION, "rows": rows}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    with csv_path.open("w", newline="", encoding="utf-8-sig") as stream:
        fieldnames = ["imageId", "fileName", "targetId", "label", "roi", "predictedStatus", "score", "metrics", "algorithm", "algorithmVersion", "durationMs", "message", "debugPath", "evaluationSet", "evaluableForMetrics"]
        writer = csv.DictWriter(stream, fieldnames=fieldnames)
        writer.writeheader()
        for item in rows:
            item = dict(item)
            item["metrics"] = json.dumps(item["metrics"], ensure_ascii=False, sort_keys=True)
            item["roi"] = json.dumps(item["roi"], ensure_ascii=False)
            writer.writerow(item)
    summary = {
        "status": "INSUFFICIENT_DATA",
        "algorithmVersion": ALGORITHM_VERSION,
        "totalRows": len(rows),
        "keySelfChecks": sum(1 for row in rows if row["evaluationSet"] == "KEY_SELF_CHECK"),
        "keyStatusCounts": {
            status: sum(1 for row in rows if row["evaluationSet"] == "KEY_SELF_CHECK" and row["predictedStatus"] == status)
            for status in ("PASS", "FAIL", "REVIEW", "ERROR")
        },
        "dcimSkippedUnknown": sum(1 for row in rows if row["evaluationSet"] == "DCIM_UNANNOTATED_SMOKE_ONLY" and row["label"] == "unknown"),
        "dcimImageCount": len(manifest["inventory"]["dcim"]["images"]),
        "dcimTargetCount": sum(len(image["targets"]) for image in ground_truth["images"]),
        "absentLabels": sum(1 for row in rows if row["label"] == "absent"),
        "accuracyMetricsComputed": False,
        "outputJson": str(json_path.resolve()),
        "outputCsv": str(csv_path.resolve()),
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
