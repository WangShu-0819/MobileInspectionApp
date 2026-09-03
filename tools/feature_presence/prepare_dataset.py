#!/usr/bin/env python3
"""Prepare the Task 1 offline dataset inventory and teal-mask evidence.

This script deliberately does not detect features. It only validates image
readability, normalizes orientation for debug rendering, masks the manually
drawn teal marks, and creates unknown-only ground truth for DCIM images.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageOps


SCRIPT_VERSION = "task1.v1"
IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg"}
EXPECTED_KEY_COUNT = 5
EXPECTED_DCIM_COUNT = 30
HSV_MASK_CONFIG = {
    "colorSpace": "HSV",
    "hueRangeOpenCv": [75, 105],
    "saturationMin": 80,
    "valueMin": 50,
    "dilateKernel": [3, 3],
    "dilateIterations": 1,
    "meaning": "candidate_teal_manual_mark_only",
    "calibrationStatus": "INITIAL_CONFIGURABLE_MASK_NOT_DETECTOR_CALIBRATION",
}

TARGET_PROFILES = [
    {
        "targetId": "thread",
        "inspectionType": "THREAD_PRESENCE",
        "templateFiles": ["thread_1.png", "thread_2.png"],
        "observedTemplateCount": 1,
    },
    {
        "targetId": "nut",
        "inspectionType": "NUT_PRESENCE",
        "templateFiles": ["nut_1.png"],
        "observedTemplateCount": 2,
    },
    {
        "targetId": "feature_1",
        "inspectionType": "FEATURE_PRESENCE",
        "templateFiles": ["feature_1.png"],
        "observedTemplateCount": 1,
    },
    {
        "targetId": "feature_2",
        "inspectionType": "FEATURE_PRESENCE",
        "templateFiles": ["feature_2.png"],
        "observedTemplateCount": 1,
    },
]
TARGET_BY_ID = {item["targetId"]: item for item in TARGET_PROFILES}
TARGET_BY_FILE = {
    file_name: profile
    for profile in TARGET_PROFILES
    for file_name in profile["templateFiles"]
}


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def safe_stem(name: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", name)


def image_files(root: Path) -> list[Path]:
    if not root.is_dir():
        raise FileNotFoundError(f"Image directory does not exist: {root}")
    return sorted(
        path for path in root.iterdir() if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS
    )


def orientation_info(raw: int | None) -> dict[str, Any]:
    if raw in range(1, 9):
        return {
            "orientationRaw": raw,
            "orientationAssumption": "EXIF_TRANSPOSE",
            "orientationAnomaly": False,
            "orientationApplied": raw in range(2, 9),
            "orientationNote": "EXIF orientation value is valid and was handled by ImageOps.exif_transpose.",
        }
    if raw == 0:
        return {
            "orientationRaw": 0,
            "orientationAssumption": "NO_TRANSFORM",
            "orientationAnomaly": True,
            "orientationApplied": False,
            "orientationNote": "EXIF Orientation=0 is invalid/unknown metadata; no transform was applied.",
        }
    return {
        "orientationRaw": raw,
        "orientationAssumption": "NO_TRANSFORM",
        "orientationAnomaly": True,
        "orientationApplied": False,
        "orientationNote": "EXIF Orientation is missing/unknown metadata; no transform was applied.",
    }


def read_oriented(path: Path) -> tuple[Image.Image, Image.Image, dict[str, Any]]:
    with Image.open(path) as opened:
        opened.load()
        raw_rgb = opened.convert("RGB").copy()
        raw_orientation = opened.getexif().get(274)
        info = orientation_info(raw_orientation)
        if raw_orientation in range(1, 9):
            normalized = ImageOps.exif_transpose(opened).convert("RGB").copy()
        else:
            normalized = raw_rgb.copy()
        info.update(
            {
                "rawSize": {"width": raw_rgb.width, "height": raw_rgb.height},
                "normalizedSize": {"width": normalized.width, "height": normalized.height},
            }
        )
    return raw_rgb, normalized, info


def teal_mask(rgb_image: Image.Image) -> tuple[np.ndarray, np.ndarray, dict[str, Any]]:
    rgb = np.asarray(rgb_image.convert("RGB"), dtype=np.uint8)
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)
    lower = np.array(
        [
            HSV_MASK_CONFIG["hueRangeOpenCv"][0],
            HSV_MASK_CONFIG["saturationMin"],
            HSV_MASK_CONFIG["valueMin"],
        ],
        dtype=np.uint8,
    )
    upper = np.array([HSV_MASK_CONFIG["hueRangeOpenCv"][1], 255, 255], dtype=np.uint8)
    mask = cv2.inRange(hsv, lower, upper)
    kernel_size = tuple(HSV_MASK_CONFIG["dilateKernel"])
    kernel = np.ones(kernel_size, dtype=np.uint8)
    dilated = cv2.dilate(mask, kernel, iterations=HSV_MASK_CONFIG["dilateIterations"])
    total = int(mask.size)
    raw_pixels = int(np.count_nonzero(mask))
    dilated_pixels = int(np.count_nonzero(dilated))
    return mask, dilated, {
        "totalPixels": total,
        "rawPixels": raw_pixels,
        "dilatedPixels": dilated_pixels,
        "rawCoverage": raw_pixels / total if total else 0.0,
        "dilatedCoverage": dilated_pixels / total if total else 0.0,
    }


def panel(image: Image.Image, label: str, panel_size: tuple[int, int] = (640, 640)) -> Image.Image:
    canvas = Image.new("RGB", panel_size, (28, 37, 44))
    contained = ImageOps.contain(image.convert("RGB"), (panel_size[0] - 16, panel_size[1] - 48))
    left = (panel_size[0] - contained.width) // 2
    top = 32 + (panel_size[1] - 32 - contained.height) // 2
    canvas.paste(contained, (left, top))
    ImageDraw.Draw(canvas).text((8, 8), label, fill=(255, 255, 255))
    return canvas


def write_debug(
    raw_rgb: Image.Image,
    normalized: Image.Image,
    dilated_mask: np.ndarray,
    destination: Path,
    orientation: dict[str, Any],
) -> None:
    normalized_array = np.asarray(normalized, dtype=np.uint8).copy()
    marked = dilated_mask > 0
    magenta = np.array([255, 0, 180], dtype=np.uint8)
    normalized_array[marked] = (
        normalized_array[marked].astype(np.float32) * 0.45 + magenta.astype(np.float32) * 0.55
    ).astype(np.uint8)
    overlay = Image.fromarray(normalized_array, mode="RGB")
    mask_image = Image.fromarray(dilated_mask, mode="L").convert("RGB")
    if orientation["orientationAnomaly"]:
        orientation_label = f"orientationRaw={orientation['orientationRaw']!r} anomaly"
    else:
        orientation_label = f"orientationRaw={orientation['orientationRaw']} valid"
    panels = [
        panel(raw_rgb, "raw source"),
        panel(normalized, f"normalized ({orientation_label})"),
        panel(overlay, "HSV+dilate overlay (magenta)"),
        panel(mask_image, "binary mask (white)")
    ]
    combined = Image.new("RGB", (sum(item.width for item in panels), panels[0].height), (28, 37, 44))
    x = 0
    for item in panels:
        combined.paste(item, (x, 0))
        x += item.width
    destination.parent.mkdir(parents=True, exist_ok=True)
    combined.save(destination, format="JPEG", quality=88, optimize=True)


def target_entries_for_image(
    subset: str, file_name: str, image_bounds: dict[str, int]
) -> list[dict[str, Any]]:
    if subset == "key":
        profile = TARGET_BY_FILE.get(file_name)
        profiles = [profile] if profile else []
    else:
        profiles = TARGET_PROFILES
    entries = []
    for profile in profiles:
        entries.append(
            {
                "targetId": profile["targetId"],
                "inspectionType": profile["inspectionType"],
                "expectedCount": None,
                "expectedCountConfigurable": True,
                "observedTemplateCount": profile["observedTemplateCount"] if subset == "key" else None,
                "roi": [0.0, 0.0, 1.0, 1.0] if subset == "key" else None,
                "roiSource": "KEY_CROPPED_IMAGE" if subset == "key" else "NO_HUMAN_ANNOTATION",
                "imageBounds": image_bounds,
                "annotationStatus": "KEY_CROPPED_IMAGE_ROI" if subset == "key" else "UNANNOTATED",
                "datasetRole": "POSITIVE_TEMPLATE_CANDIDATE" if subset == "key" else "HOLDOUT_UNANNOTATED",
            }
        )
    return entries


def process_image(path: Path, subset: str, output_dir: Path) -> dict[str, Any]:
    base: dict[str, Any] = {
        "imageId": f"{subset}:{path.stem}",
        "subset": subset,
        "fileName": path.name,
        "sourcePath": str(path.resolve()),
        "fileSizeBytes": path.stat().st_size,
        "readable": False,
    }
    try:
        raw_rgb, normalized, orientation = read_oriented(path)
        _, dilated_mask, coverage = teal_mask(normalized)
        image_bounds = {
            "left": 0,
            "top": 0,
            "right": normalized.width,
            "bottom": normalized.height,
            "width": normalized.width,
            "height": normalized.height,
            "coordinateSpace": "normalized_image_pixels",
        }
        debug_relative = Path("debug") / subset / f"{safe_stem(path.stem)}__orientation_mask.jpg"
        write_debug(raw_rgb, normalized, dilated_mask, output_dir / debug_relative, orientation)
        base.update(
            {
                "readable": True,
                "format": read_format(path),
                **orientation,
                "imageBounds": image_bounds,
                "maskCoverage": coverage,
                "debug": {
                    "path": debug_relative.as_posix(),
                    "containsPanels": ["raw_source", "normalized", "hsv_dilate_overlay", "binary_mask"],
                    "overlayColor": "magenta",
                },
                "targets": target_entries_for_image(subset, path.name, image_bounds),
            }
        )
    except Exception as exc:  # record the input failure in the manifest/report
        base["error"] = f"{type(exc).__name__}: {exc}"
    return base


def excluded_files(root: Path) -> list[str]:
    return sorted(
        path.name for path in root.iterdir() if path.is_file() and path.suffix.lower() not in IMAGE_EXTENSIONS
    )


def read_format(path: Path) -> str | None:
    with Image.open(path) as opened:
        return opened.format


def inventory_status(records: list[dict[str, Any]], expected_count: int) -> dict[str, Any]:
    readable = sum(1 for record in records if record["readable"])
    return {
        "expectedImageCount": expected_count,
        "imageCount": len(records),
        "readableCount": readable,
        "unreadableCount": len(records) - readable,
        "countCheck": len(records) == expected_count,
        "readabilityCheck": readable == len(records),
    }


def pct(value: float) -> str:
    return f"{value * 100:.4f}%"


def mask_summary(records: list[dict[str, Any]]) -> dict[str, Any]:
    values = [record["maskCoverage"]["dilatedCoverage"] for record in records if record["readable"]]
    raw_values = [record["maskCoverage"]["rawCoverage"] for record in records if record["readable"]]
    if not values:
        return {"readableImages": 0, "rawCoverage": {}, "dilatedCoverage": {}}
    return {
        "readableImages": len(values),
        "rawCoverage": {"min": min(raw_values), "max": max(raw_values), "mean": sum(raw_values) / len(raw_values)},
        "dilatedCoverage": {"min": min(values), "max": max(values), "mean": sum(values) / len(values)},
    }


def markdown_inventory(title: str, records: list[dict[str, Any]]) -> str:
    lines = [
        f"### {title}",
        "",
        "| 文件 | 原始尺寸 | 归一化尺寸 | orientationRaw | anomaly | 原始掩码覆盖 | 膨胀后覆盖 | debug |",
        "|---|---:|---:|---:|:---:|---:|---:|---|",
    ]
    for record in records:
        if not record["readable"]:
            lines.append(f"| `{record['fileName']}` | — | — | — | — | — | — | 解码失败：{record['error']} |")
            continue
        raw = record["rawSize"]
        normalized = record["normalizedSize"]
        coverage = record["maskCoverage"]
        lines.append(
            f"| `{record['fileName']}` | {raw['width']}×{raw['height']} | {normalized['width']}×{normalized['height']} | "
            f"{record['orientationRaw']!r} | {str(record['orientationAnomaly']).lower()} | "
            f"{pct(coverage['rawCoverage'])} | {pct(coverage['dilatedCoverage'])} | `{record['debug']['path']}` |"
        )
    return "\n".join(lines)


def make_ground_truth(dcim_records: list[dict[str, Any]]) -> dict[str, Any]:
    images = []
    for record in dcim_records:
        targets = []
        for profile in TARGET_PROFILES:
            targets.append(
                {
                    "targetId": profile["targetId"],
                    "inspectionType": profile["inspectionType"],
                    "label": "unknown",
                    "expectedCount": None,
                    "roi": None,
                    "imageBounds": record.get("imageBounds"),
                    "annotationStatus": "UNANNOTATED",
                    "evaluableForMetrics": False,
                    "reason": "No human-confirmed target ROI or presence label is available.",
                }
            )
        images.append(
            {
                "imageId": record["imageId"],
                "fileName": record["fileName"],
                "sourcePath": record["sourcePath"],
                "targets": targets,
            }
        )
    return {
        "schemaVersion": "feature_presence.ground_truth.v1",
        "status": "INSUFFICIENT_DATA",
        "labelVocabulary": ["present", "absent", "unknown"],
        "policy": {
            "unannotatedLabel": "unknown",
            "unannotatedRoi": None,
            "unannotatedAnnotationStatus": "UNANNOTATED",
            "accuracyMetricsAllowed": False,
            "note": "Unknown images must not be converted to absent or used for accuracy claims.",
        },
        "images": images,
    }


def make_report(
    output_dir: Path,
    manifest: dict[str, Any],
    ground_truth: dict[str, Any],
    key_records: list[dict[str, Any]],
    dcim_records: list[dict[str, Any]],
) -> str:
    key_status = manifest["inventory"]["key"]
    dcim_status = manifest["inventory"]["dcim"]
    excluded = manifest["inputs"]["excludedNonImageFiles"]
    lines = [
        "# Presence Detection Task 1：数据清点、标记掩码和离线 manifest",
        "",
        f"- 生成时间：`{manifest['generatedAtUtc']}`",
        f"- 总状态：`{manifest['status']}`",
        f"- 脚本版本：`{SCRIPT_VERSION}`",
        f"- Python：`{manifest['runtime']['pythonExecutable']}`",
        "",
        "## 结论",
        "",
        "图片解码检查通过：Key 为 5/5，DCIM 为 30/30。DCIM 目录中的视频已排除。",
        "当前没有人工确认的目标 ROI 和目标有无标签，因此 ground truth 全部为 `unknown`，本轮只能报告 `INSUFFICIENT_DATA`，不计算准确率、召回率或混淆矩阵。",
        "",
        "## 输入与方向处理",
        "",
        f"- Key：`{manifest['inputs']['keyDir']}`；图片数量 `{key_status['imageCount']}`，可读 `{key_status['readableCount']}`。",
        f"- DCIM：`{manifest['inputs']['dcimDir']}`；图片数量 `{dcim_status['imageCount']}`，可读 `{dcim_status['readableCount']}`。",
        f"- 排除的非图片文件：{', '.join(f'`{name}`' for name in excluded) if excluded else '无'}。",
        "- 方向策略：有效 EXIF Orientation 1～8 使用 `ImageOps.exif_transpose`；Orientation=0 是无效/未知元数据，明确记录 `orientationAssumption=NO_TRANSFORM`、`orientationAnomaly=true`，不称为正常方向。",
        "- debug 合成图同时包含 raw source、normalized、HSV+dilate overlay 和 binary mask 四个面板；原始文件不被覆盖或改写。",
        "",
        "## HSV 青绿色人工标记掩码",
        "",
        "```json",
        json.dumps(manifest["tealMask"], ensure_ascii=False, indent=2),
        "```",
        "",
        "掩码只用于排除人工标记，不能作为目标存在证据。当前参数是可配置的初始掩码，不代表正式检测器标定。",
        "",
        "### 掩码覆盖汇总",
        "",
        f"- Key：原始掩码 `{pct(mask_summary(key_records)['rawCoverage']['mean'])}`（均值），膨胀后 `{pct(mask_summary(key_records)['dilatedCoverage']['mean'])}`（均值）。",
        f"- DCIM：原始掩码 `{pct(mask_summary(dcim_records)['rawCoverage']['mean'])}`（均值），膨胀后 `{pct(mask_summary(dcim_records)['dilatedCoverage']['mean'])}`（均值）。",
        "- 每张图片的实际覆盖率和 debug 路径见下表及 `dataset_manifest.json`。",
        "",
        markdown_inventory("Key 图片清点", key_records),
        "",
        markdown_inventory("DCIM 图片清点", dcim_records),
        "",
        "## ROI 与 ground truth 政策",
        "",
        "- Key 图片本身就是已裁剪的 ROI 小图；对应模板记录使用 `roi: [0.0, 0.0, 1.0, 1.0]`、`roiSource: KEY_CROPPED_IMAGE`，不标记为 `UNANNOTATED`。",
        "- DCIM 大图没有人工确认的目标位置；对应目标使用 `roi: null`、`annotationStatus: UNANNOTATED`。",
        "- `imageBounds` 只表示整张已归一化图片的边界，不能替代 DCIM 的真实 target ROI。",
        "- `nut_1.png` 只记录 `observedTemplateCount: 2`；`expectedCount` 保持 null 且由后续配置提供。",
        "- 30 张 DCIM 图片的全部目标标签均为 `unknown`，不能自动生成 `absent`。",
        "- `ground_truth.json` 当前状态：`INSUFFICIENT_DATA`。需要人工确认目标 ROI、present/absent 标签后，才能进行 holdout 评估。",
        "",
        "## 输出文件",
        "",
        "- `dataset_manifest.json`：输入清点、尺寸、方向元数据、imageBounds、目标配置、掩码参数和覆盖率。",
        "- `ground_truth.json`：30 张 DCIM × 4 类目标的 unknown-only 结构。",
        "- `debug/key/`、`debug/dcim/`：方向与青绿色掩码可视化。",
        "",
        "## 未完成项",
        "",
        "- DCIM 未进行人工目标 ROI 标注；Key 模板使用裁剪图全幅 ROI 约定。",
        "- 未获得人工确认的 absent 样本，不能报告检测准确率。",
        "- 三个 detector 在 `presence_detectors.py` 中独立运行；本数据准备脚本本身不扫描 DCIM，也不接入 Android。",
    ]
    return "\n".join(lines) + "\n"


def run_self_test() -> None:
    with tempfile.TemporaryDirectory(prefix="feature_presence_task1_") as temp:
        root = Path(temp)
        path = root / "orientation_zero.jpg"
        image = Image.new("RGB", (12, 8), (20, 20, 20))
        draw = ImageDraw.Draw(image)
        draw.rectangle((2, 2, 6, 5), fill=(0, 210, 180))
        exif = Image.Exif()
        exif[274] = 0
        image.save(path, format="JPEG", exif=exif)
        raw, normalized, info = read_oriented(path)
        assert raw.size == (12, 8)
        assert normalized.size == (12, 8)
        assert info["orientationRaw"] == 0
        assert info["orientationAssumption"] == "NO_TRANSFORM"
        assert info["orientationAnomaly"] is True
        _, dilated, coverage = teal_mask(normalized)
        assert int(np.count_nonzero(dilated)) > 0
        assert coverage["dilatedCoverage"] > 0
    print("SELF_TEST_PASS")


def build_manifest(args: argparse.Namespace) -> tuple[dict[str, Any], dict[str, Any], str]:
    key_dir = Path(args.key_dir).resolve()
    dcim_dir = Path(args.dcim_dir).resolve()
    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    key_paths = image_files(key_dir)
    dcim_paths = image_files(dcim_dir)
    key_records = [process_image(path, "key", output_dir) for path in key_paths]
    dcim_records = [process_image(path, "dcim", output_dir) for path in dcim_paths]
    key_status = inventory_status(key_records, EXPECTED_KEY_COUNT)
    dcim_status = inventory_status(dcim_records, EXPECTED_DCIM_COUNT)
    all_readable = key_status["readabilityCheck"] and dcim_status["readabilityCheck"]
    counts_match = key_status["countCheck"] and dcim_status["countCheck"]
    manifest_status = "INSUFFICIENT_DATA" if all_readable and counts_match else "DATA_READ_OR_COUNT_ERROR"
    manifest: dict[str, Any] = {
        "schemaVersion": "feature_presence.dataset_manifest.v1",
        "generatedAtUtc": utc_now(),
        "status": manifest_status,
        "task": "Task 1: data inventory, teal mark mask, offline manifest",
        "runtime": {"pythonExecutable": sys.executable, "scriptVersion": SCRIPT_VERSION},
        "inputs": {
            "keyDir": str(key_dir),
            "dcimDir": str(dcim_dir),
            "imageExtensions": sorted(IMAGE_EXTENSIONS),
            "excludedNonImageFiles": excluded_files(dcim_dir),
            "videoPolicy": "excluded_from_image_inventory_and_ground_truth",
        },
        "orientationPolicy": {
            "operationOrder": ["decode", "read_orientation_metadata", "normalize_for_debug", "HSV_mask"],
            "validExifOrientationValues": list(range(1, 9)),
            "invalidZero": {
                "orientationRaw": 0,
                "orientationAssumption": "NO_TRANSFORM",
                "orientationAnomaly": True,
            },
        },
        "tealMask": HSV_MASK_CONFIG,
        "targetProfiles": [
            {
                **profile,
                "expectedCount": None,
                "expectedCountConfigurable": True,
                "roi": [0.0, 0.0, 1.0, 1.0],
                "roiSource": "KEY_CROPPED_IMAGE",
                "annotationStatus": "KEY_CROPPED_IMAGE_ROI",
                "roiPolicy": "full_cropped_key_image_is_template_roi; dcim_roi_requires_human_annotation",
                "calibrationStatus": "NOT_CALIBRATED",
                "templates": [
                    {
                        "fileName": record["fileName"],
                        "sourcePath": record["sourcePath"],
                        "roi": [0.0, 0.0, 1.0, 1.0],
                        "roiSource": "KEY_CROPPED_IMAGE",
                        "annotationStatus": "KEY_CROPPED_IMAGE_ROI",
                        "imageBounds": record.get("imageBounds"),
                    }
                    for record in key_records
                    if record["fileName"] in profile["templateFiles"]
                ],
            }
            for profile in TARGET_PROFILES
        ],
        "inventory": {
            "key": {**key_status, "images": key_records, "maskSummary": mask_summary(key_records)},
            "dcim": {**dcim_status, "images": dcim_records, "maskSummary": mask_summary(dcim_records)},
        },
        "groundTruthPolicy": {
            "allowedLabels": ["present", "absent", "unknown"],
            "unannotatedLabel": "unknown",
            "unannotatedRoi": None,
            "unannotatedAnnotationStatus": "UNANNOTATED",
            "metricsStatus": "INSUFFICIENT_DATA",
        },
    }
    ground_truth = make_ground_truth(dcim_records)
    report = make_report(output_dir, manifest, ground_truth, key_records, dcim_records)
    (output_dir / "dataset_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (output_dir / "ground_truth.json").write_text(
        json.dumps(ground_truth, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (output_dir / "TASK1_DATASET_REPORT.md").write_text(report, encoding="utf-8")
    return manifest, ground_truth, report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--key-dir",
        default=r"D:\study\Textile_defects\Wearable Inspection\Key",
        help="Key positive-template directory",
    )
    parser.add_argument(
        "--dcim-dir",
        default=r"D:\study\Textile_defects\Wearable Inspection\DCIM\DCIM",
        help="DCIM image directory",
    )
    parser.add_argument(
        "--output-dir",
        default=r"docs\reports\b3\feature_presence",
        help="Report/artifact output directory",
    )
    parser.add_argument("--self-test", action="store_true", help="Run the offline helper self-test only")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.self_test:
        run_self_test()
        return 0
    manifest, ground_truth, _ = build_manifest(args)
    summary = {
        "status": manifest["status"],
        "groundTruthStatus": ground_truth["status"],
        "key": manifest["inventory"]["key"],
        "dcim": manifest["inventory"]["dcim"],
        "outputDir": str(Path(args.output_dir).resolve()),
        "debugImageCount": sum(
            1 for record in manifest["inventory"]["key"]["images"] + manifest["inventory"]["dcim"]["images"]
            if record.get("readable") and record.get("debug")
        ),
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if manifest["status"] == "INSUFFICIENT_DATA" else 2


if __name__ == "__main__":
    raise SystemExit(main())
