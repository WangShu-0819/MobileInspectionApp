#!/usr/bin/env python3
"""Generate Windows NCNN raw-output references for the pinned Android test image."""

from __future__ import annotations

import hashlib
import json
import math
import os
import platform
import sys
import sysconfig
from datetime import datetime, timezone
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/androidTest/assets/ncnn_parity"
IMAGE = ASSETS / "frame_00000_f0.jpg"
EXPECTED_IMAGE_SHA256 = "f9fcc75a2f4047db35fcd2b884e1bdf5bab3ce7addce8610c40a99469942ded6"
INPUT_SIZE = 416
OUTPUT_HEIGHT = 3598
STRIDES = (8, 16, 32, 64)
MEAN = np.array((103.53, 116.28, 123.675), dtype=np.float32)
STD = np.array((57.375, 57.12, 58.395), dtype=np.float32)
SCORE_THRESHOLD = 0.05
NMS_THRESHOLD = 0.6

MODELS = {
    "exp22": {
        "contractVersion": "nanodet-ncnn-exp22-black-4class",
        "directory": "exp22",
        "outputWidth": 36,
        "classNames": ["Black Thread", "Black Nutsert", "Black Nut", "Black Bolt"],
        "paramSha256": "b81b824fea9ff949f72f3715a8f20c6ebe96c2792370dd80b7a7a69a65335960",
        "modelSha256": "d3a16edb715050b376f40b5f396fc748b984d781590aa1bf07b3f42a3d92a3d7",
    },
    "exp23": {
        "contractVersion": "nanodet-ncnn-exp23-white-2class",
        "directory": "exp23",
        "outputWidth": 34,
        "classNames": ["White Nut", "White Thread"],
        "paramSha256": "ad45e2f3fcb6777a5e924aa9a3095c23b2c5f900632720c389d7816a1a390bdd",
        "modelSha256": "1b662094ce94f4a3a8c899f40e1cb449e01aa10ae7a1addabcb92bfef18da35d",
    },
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def preprocess(image_path: Path) -> tuple[np.ndarray, dict]:
    image = cv2.imread(
        str(image_path), cv2.IMREAD_COLOR | cv2.IMREAD_IGNORE_ORIENTATION
    )
    if image is None:
        raise RuntimeError(f"Cannot decode pinned image: {image_path}")
    height, width = image.shape[:2]
    scale = min(INPUT_SIZE / width, INPUT_SIZE / height)
    resized_width = max(1, int(width * scale))
    resized_height = max(1, int(height * scale))
    resized = cv2.resize(
        image, (resized_width, resized_height), interpolation=cv2.INTER_LINEAR
    )

    # Match NanoDetImagePreprocessor: BGR, top-left letterbox, normalized content,
    # and zero-valued NCHW padding.
    tensor = np.zeros((1, 3, INPUT_SIZE, INPUT_SIZE), dtype=np.float32)
    normalized = (resized.astype(np.float32) - MEAN) / STD
    tensor[0, :, :resized_height, :resized_width] = normalized.transpose(2, 0, 1)
    transform = {
        "width": width,
        "height": height,
        "resizedWidth": resized_width,
        "resizedHeight": resized_height,
        "scaleX": resized_width / width,
        "scaleY": resized_height / height,
        "padLeft": 0,
        "padTop": 0,
        "padRight": INPUT_SIZE - resized_width,
        "padBottom": INPUT_SIZE - resized_height,
    }
    return tensor, transform


def iou_inclusive(a: list[float], b: list[float]) -> float:
    x1, y1 = max(a[0], b[0]), max(a[1], b[1])
    x2, y2 = min(a[2], b[2]), min(a[3], b[3])
    intersection = max(0.0, x2 - x1 + 1.0) * max(0.0, y2 - y1 + 1.0)
    area_a = (a[2] - a[0] + 1.0) * (a[3] - a[1] + 1.0)
    area_b = (b[2] - b[0] + 1.0) * (b[3] - b[1] + 1.0)
    return intersection / max(area_a + area_b - intersection, 1e-12)


def decode(output: np.ndarray, transform: dict, class_names: list[str]) -> list[dict]:
    width = len(class_names) + 32
    rows = output.reshape(OUTPUT_HEIGHT, width)
    by_class: list[list[dict]] = [[] for _ in class_names]
    offset = 0
    for stride in STRIDES:
        feature_size = math.ceil(INPUT_SIZE / stride)
        for y in range(feature_size):
            for x in range(feature_size):
                point = offset + y * feature_size + x
                center_x, center_y = x * stride, y * stride
                if center_x >= transform["resizedWidth"] or center_y >= transform["resizedHeight"]:
                    continue

                class_index = int(np.argmax(rows[point, : len(class_names)]))
                score = float(rows[point, class_index])
                if score < SCORE_THRESHOLD:
                    continue

                distances = []
                for side in range(4):
                    logits = rows[point, len(class_names) + side * 8 : len(class_names) + (side + 1) * 8]
                    max_logit = float(np.max(logits))
                    probabilities = [math.exp(float(value) - max_logit) for value in logits]
                    total = sum(probabilities)
                    expectation = sum(index * value / total for index, value in enumerate(probabilities))
                    distances.append(expectation * stride)

                box = [
                    (center_x - distances[0]) / transform["scaleX"],
                    (center_y - distances[1]) / transform["scaleY"],
                    (center_x + distances[2]) / transform["scaleX"],
                    (center_y + distances[3]) / transform["scaleY"],
                ]
                box = [
                    max(0.0, min(value, float(transform["width"] if i % 2 == 0 else transform["height"])))
                    for i, value in enumerate(box)
                ]
                by_class[class_index].append(
                    {
                        "classIndex": class_index,
                        "className": class_names[class_index],
                        "score": score,
                        "box": box,
                        "point": point,
                    }
                )
        offset += feature_size * feature_size

    kept = []
    for class_candidates in by_class:
        class_kept = []
        for candidate in sorted(class_candidates, key=lambda item: item["score"], reverse=True):
            if not any(
                iou_inclusive(candidate["box"], previous["box"]) > NMS_THRESHOLD
                for previous in class_kept
            ):
                class_kept.append(candidate)
            if len(class_kept) == 100:
                break
        kept.extend(class_kept)
    return kept


def load_ncnn():
    toolchain = Path(
        os.environ.get(
            "NCNN_TOOLCHAIN_ROOT", r"D:\study\Textile_defects\_ncnn_toolchain_20260526"
        )
    )
    sys.path.insert(0, str(toolchain / "ncnn_py311"))
    import ncnn

    return ncnn, toolchain


def run_model(ncnn, name: str, config: dict, tensor: np.ndarray, transform: dict) -> tuple[dict, bytes]:
    model_dir = ROOT / "app/src/main/assets/nanodet" / config["directory"]
    param_path = model_dir / "nanodet.ncnn.param"
    bin_path = model_dir / "nanodet.ncnn.bin"
    param_hash = sha256(param_path.read_bytes())
    model_hash = sha256(bin_path.read_bytes())
    if param_hash != config["paramSha256"] or model_hash != config["modelSha256"]:
        raise RuntimeError(f"{name} asset SHA-256 differs from the reviewed contract")

    net = ncnn.Net()
    net.opt.use_vulkan_compute = False
    net.opt.use_fp16_storage = False
    net.opt.use_fp16_packed = False
    net.opt.use_fp16_arithmetic = False
    net.opt.num_threads = 1
    if net.load_param(str(param_path)) != 0:
        raise RuntimeError(f"{name} NCNN load_param failed")
    if net.load_model(str(bin_path)) != 0:
        raise RuntimeError(f"{name} NCNN load_model failed")

    extractor = net.create_extractor()
    ncnn_input = ncnn.Mat(np.ascontiguousarray(tensor[0])).clone()
    if extractor.input("in0", ncnn_input) != 0:
        raise RuntimeError(f"{name} NCNN input in0 failed")
    code, output_mat = extractor.extract("out0")
    if code != 0:
        raise RuntimeError(f"{name} NCNN extract out0 failed: {code}")

    mat = {
        "dims": int(output_mat.dims),
        "w": int(output_mat.w),
        "h": int(output_mat.h),
        "elemsize": int(output_mat.elemsize),
    }
    if mat != {"dims": 2, "w": config["outputWidth"], "h": OUTPUT_HEIGHT, "elemsize": 4}:
        raise RuntimeError(f"{name} actual NCNN output Mat does not match contract: {mat}")

    output = np.asarray(output_mat, dtype=np.float32).reshape(mat["h"], mat["w"]).copy(order="C")
    output_bytes = output.astype("<f4", copy=False).tobytes(order="C")
    candidates = decode(output, transform, config["classNames"])
    return (
        {
            "contractVersion": config["contractVersion"],
            "classNames": config["classNames"],
            "classCount": len(config["classNames"]),
            "paramSha256": param_hash,
            "modelSha256": model_hash,
            "inputBlob": "in0",
            "outputBlob": "out0",
            "inputShape": [1, 3, INPUT_SIZE, INPUT_SIZE],
            "inputTensorSha256": sha256(tensor.astype("<f4", copy=False).tobytes(order="C")),
            "outputMat": mat,
            "outputShape": [mat["h"], mat["w"]],
            "outputTensor": {
                "path": f"real_image_{name}_desktop_output.f32",
                "dtype": "float32-le",
                "layout": "row-major [Mat.h, Mat.w]",
                "elementCount": int(output.size),
                "sha256": sha256(output_bytes),
            },
            "candidateThreshold": SCORE_THRESHOLD,
            "nmsThreshold": NMS_THRESHOLD,
            "classwiseNmsMaxDetections": 100,
            "candidateCountAfterNms": len(candidates),
            "candidates": candidates,
        },
        output_bytes,
    )


def main() -> None:
    image_bytes = IMAGE.read_bytes()
    image_sha = sha256(image_bytes)
    if image_sha != EXPECTED_IMAGE_SHA256:
        raise RuntimeError(f"Pinned image SHA-256 mismatch: {image_sha}")

    ncnn, toolchain = load_ncnn()
    tensor, transform = preprocess(IMAGE)
    input_sha = sha256(tensor.astype("<f4", copy=False).tobytes(order="C"))
    input_bytes = tensor.astype("<f4", copy=False).tobytes(order="C")

    results = {}
    binary_outputs = {"real_image_desktop_input.f32": input_bytes}
    for name, config in MODELS.items():
        result, output_bytes = run_model(ncnn, name, config, tensor, transform)
        results[name] = result
        binary_outputs[result["outputTensor"]["path"]] = output_bytes

    for filename, data in binary_outputs.items():
        (ASSETS / filename).write_bytes(data)

    version = getattr(ncnn, "__version__", "unknown")
    reference = {
        "schemaVersion": 1,
        "status": "READY",
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "runtime": {
            "platform": platform.platform(),
            "machine": platform.machine(),
            "architecture": platform.architecture()[0],
            "pythonPlatform": sysconfig.get_platform(),
            "python": sys.version.split()[0],
            "ncnnVersion": str(version),
            "opencvVersion": cv2.__version__,
            "ncnnModulePath": str(Path(ncnn.__file__).resolve()),
            "toolchainRoot": str(toolchain.resolve()),
            "vulkan": False,
            "fp16Storage": False,
            "fp16Packed": False,
            "fp16Arithmetic": False,
            "threads": 1,
        },
        "image": {
            "path": "app/src/androidTest/assets/ncnn_parity/frame_00000_f0.jpg",
            "originalSourcePath": r"D:\study\Textile_defects\Wearable Inspection\DCIM\extracted_frames\frame_00000_f0.jpg",
            "sha256": image_sha,
            "width": transform["width"],
            "height": transform["height"],
            "exifOrientation": 1,
            "decoder": "OpenCV cv2.imread(IMREAD_COLOR | IMREAD_IGNORE_ORIENTATION)",
        },
        "preprocessing": {
            "inputSize": INPUT_SIZE,
            "color": "BGR",
            "resize": "OpenCV INTER_LINEAR",
            "mean": MEAN.tolist(),
            "std": STD.tolist(),
            "letterbox": "top-left; padding normalized tensor values are zero",
            "transform": transform,
            "inputTensorLayout": "float32 NCHW [1,3,416,416]",
            "inputTensorSha256": input_sha,
            "inputTensor": {
                "path": "real_image_desktop_input.f32",
                "dtype": "float32-le",
                "layout": "NCHW [1,3,416,416]",
                "elementCount": int(tensor.size),
                "sha256": input_sha,
            },
        },
        "comparisonPolicy": {
            "inputTensorMaxAbsoluteDifferenceTolerance": 1.0 / 57.12 + 1e-6,
            "inputTensorToleranceBasis": "one uint8 intensity step divided by the smallest declared normalization std (57.12), plus 1e-6 for FP32 rounding; compares every preprocessed NCHW element",
            "rawTensorMaxAbsoluteDifference": 1e-5,
            "scoreMaxAbsoluteDifference": 1e-5,
            "boxCoordinateMaxAbsoluteDifferencePx": 0.01,
            "candidateIdentity": "exact (classIndex, point) set after classwise NMS",
            "toleranceBasis": "raw FP32 tensor and score limits match the existing exp09 parity limits; 0.01 px bounds subpixel decoder rounding; all limits were fixed before this run and not tuned to these outputs",
        },
        "results": results,
    }
    target = ASSETS / "real_image_desktop_reference.json"
    target.write_text(json.dumps(reference, indent=2, allow_nan=False), encoding="utf-8")
    print(
        json.dumps(
            {
                "reference": str(target),
                "runtime": reference["runtime"],
                "image": reference["image"],
                "preprocessing": reference["preprocessing"],
                "models": {
                    name: {
                        "outputMat": result["outputMat"],
                        "outputTensor": result["outputTensor"],
                        "candidateCountAfterNms": result["candidateCountAfterNms"],
                    }
                    for name, result in results.items()
                },
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
