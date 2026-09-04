"""Focused offline tests for the three independent detectors."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
import re
import json

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from presence_detectors import (  # noqa: E402
    FeaturePresenceDetector,
    NutPresenceDetector,
    ThreadPresenceDetector,
    crop_normalized,
    detector_for,
    load_bgr,
)


def thread_sample(size: int = 160) -> np.ndarray:
    image = np.full((size, size, 3), 32, dtype=np.uint8)
    center = size // 2
    for radius in (25, 31, 37, 43):
        cv2.circle(image, (center, center), radius, (215, 215, 215), 3)
    cv2.circle(image, (center, center), 17, (8, 8, 8), -1)
    cv2.line(image, (center - 54, center), (center + 54, center), (50, 50, 50), 2)
    return image


def nut_sample(size: int = 240) -> np.ndarray:
    image = np.full((size, size, 3), 24, dtype=np.uint8)
    for center in ((75, 120), (165, 120)):
        points = cv2.ellipse2Poly(center, (38, 38), 0, 0, 360, 60)
        cv2.fillPoly(image, [points], (220, 220, 220))
        cv2.circle(image, center, 13, (25, 25, 25), -1)
    return image


def feature_sample(size: int = 220) -> np.ndarray:
    image = np.full((size, size, 3), 25, dtype=np.uint8)
    for x in range(30, size - 30, 24):
        cv2.line(image, (x, 25), (x - 18, size - 25), (220, 220, 220), 3)
    cv2.rectangle(image, (45, 55), (size - 45, size - 55), (150, 150, 150), 2)
    cv2.putText(image, "F1", (70, 135), cv2.FONT_HERSHEY_SIMPLEX, 1.4, (240, 240, 240), 3)
    return image


def box_containment(first: list[int], second: list[int]) -> float:
    ax, ay, aw, ah = first
    bx, by, bw, bh = second
    x0, y0 = max(ax, bx), max(ay, by)
    x1, y1 = min(ax + aw, bx + bw), min(ay + ah, by + bh)
    overlap = max(0, x1 - x0) * max(0, y1 - y0)
    return overlap / max(1, min(aw * ah, bw * bh))


class DetectorTests(unittest.TestCase):
    def test_thread_positive_and_plain_hole_not_pass(self) -> None:
        detector = ThreadPresenceDetector()
        positive = detector.detect(thread_sample())
        self.assertIn(positive.status, {"PASS", "REVIEW"})
        plain_hole = np.full((160, 160, 3), 30, dtype=np.uint8)
        cv2.circle(plain_hole, (80, 80), 52, (210, 210, 210), 4)
        cv2.circle(plain_hole, (80, 80), 32, (30, 30, 30), -1)
        plain = detector.detect(plain_hole)
        self.assertNotEqual(plain.status, "PASS")

    def test_thread_blur_is_not_pass(self) -> None:
        detector = ThreadPresenceDetector()
        blurred = cv2.GaussianBlur(thread_sample(), (21, 21), 0)
        self.assertNotEqual(detector.detect(blurred).status, "PASS")

    def test_thread_refines_known_circle_and_boxes_match_debug_circle(self) -> None:
        detector = ThreadPresenceDetector()
        image = thread_sample()
        with tempfile.TemporaryDirectory() as temporary:
            debug_path = Path(temporary) / "thread_refined.png"
            result = detector.detect(image, debug_path=debug_path)
            self.assertIn(result.status, {"PASS", "REVIEW"})
            expected_center = np.array([80.0, 80.0])
            actual_center = np.array([result.metrics["circleCenterX"], result.metrics["circleCenterY"]])
            self.assertLessEqual(np.linalg.norm(actual_center - expected_center), min(image.shape[:2]) * 0.03)
            # Several concentric synthetic strokes are intentionally present;
            # verify the selected aperture scale without treating an outer
            # stroke as a pixel-level annotation of the threaded opening.
            self.assertLessEqual(abs(result.metrics["circleRadius"] - 43.0), 43.0 * 0.20)
            expected_box = [
                round(result.metrics["circleCenterX"] - result.metrics["circleRadius"]),
                round(result.metrics["circleCenterY"] - result.metrics["circleRadius"]),
                round(2.0 * result.metrics["circleRadius"]),
                round(2.0 * result.metrics["circleRadius"]),
            ]
            self.assertEqual(result.boxes, [expected_box])
            debug = cv2.imread(str(debug_path))
            self.assertIsNotNone(debug)
            self.assertTrue(np.any(np.all(debug == np.array([0, 220, 0]), axis=2)))
            self.assertGreater(result.metrics["refinementShiftPx"], 0.0)

    def test_thread_key_batch_is_reproducible_and_geometrically_valid(self) -> None:
        key_dir = Path(r"D:\study\Textile_defects\Wearable Inspection\Key")
        if not key_dir.is_dir():
            self.skipTest("offline Key directory is not available")
        files = sorted(
            (
                path
                for path in key_dir.iterdir()
                if path.is_file()
                and re.fullmatch(r"thread_\d+", path.stem, re.IGNORECASE)
                and path.suffix.lower() in {".png", ".jpg", ".jpeg"}
            ),
            key=lambda path: int(path.stem.split("_")[-1]),
        )
        self.assertGreaterEqual(len(files), 16)
        detector = ThreadPresenceDetector()
        for path in files:
            with self.subTest(file_name=path.name):
                image = load_bgr(path)
                result = detector.detect(image)
                repeat = detector.detect(image)
                self.assertIn(result.status, {"PASS", "REVIEW"})
                self.assertEqual(repeat.status, result.status)
                for key in (
                    "circleCenterX",
                    "circleCenterY",
                    "circleRadius",
                    "rawCircleCenterX",
                    "rawCircleCenterY",
                    "rawCircleRadius",
                ):
                    self.assertAlmostEqual(result.metrics[key], repeat.metrics[key], places=6)
                height, width = image.shape[:2]
                cx = result.metrics["circleCenterX"]
                cy = result.metrics["circleCenterY"]
                radius = result.metrics["circleRadius"]
                self.assertGreater(radius, 0.0)
                self.assertLess(radius, min(height, width) * 0.49)
                self.assertGreaterEqual(cx - radius, -1.0)
                self.assertGreaterEqual(cy - radius, -1.0)
                self.assertLessEqual(cx + radius, width + 1.0)
                self.assertLessEqual(cy + radius, height + 1.0)
                self.assertGreater(result.metrics["circleGeometryScore"], 0.0)
                self.assertGreaterEqual(result.metrics["innerOuterConcentricity"], 0.0)
                self.assertLessEqual(result.metrics["innerOuterConcentricity"], 1.0)
                self.assertGreater(result.metrics["texturePeriodicityScore"], 0.0)
                self.assertGreater(result.metrics["refinementEvaluations"], 0.0)
                self.assertEqual(
                    result.boxes,
                    [[round(cx - radius), round(cy - radius), round(2 * radius), round(2 * radius)]],
                )

    def test_thread_rejects_nonconcentric_ring_and_bright_circle(self) -> None:
        detector = ThreadPresenceDetector()
        nonconcentric = np.full((180, 180, 3), 30, dtype=np.uint8)
        cv2.circle(nonconcentric, (90, 90), 48, (210, 210, 210), 4)
        cv2.circle(nonconcentric, (117, 90), 20, (12, 12, 12), -1)
        self.assertNotEqual(detector.detect(nonconcentric).status, "PASS")
        bright_circle = np.full((180, 180, 3), 30, dtype=np.uint8)
        cv2.circle(bright_circle, (90, 90), 42, (235, 235, 235), -1)
        self.assertNotEqual(detector.detect(bright_circle).status, "PASS")

    def test_thread_generated_negative_samples_are_not_pass(self) -> None:
        negative_dir = (
            Path(__file__).resolve().parents[2]
            / "docs"
            / "reports"
            / "b3"
            / "feature_presence"
            / "evaluation"
            / "negative_thread"
        )
        if not negative_dir.is_dir():
            self.skipTest("generated Thread negative samples are not available")
        files = sorted(negative_dir.glob("thread_negative_*.png"))
        self.assertGreaterEqual(len(files), 12)
        detector = ThreadPresenceDetector()
        for path in files:
            with self.subTest(file_name=path.name):
                result = detector.detect(load_bgr(path))
                self.assertNotEqual(
                    result.status,
                    "PASS",
                    msg=f"negative sample false positive: {result.score:.3f} {result.message}",
                )

    def test_nut_count_is_configurable(self) -> None:
        detector = NutPresenceDetector()
        configured = detector.detect(nut_sample(), {"expectedCount": 2})
        self.assertIn(configured.status, {"PASS", "REVIEW"})
        mismatch = detector.detect(nut_sample(), {"expectedCount": 1})
        self.assertEqual(mismatch.status, "FAIL")
        not_configured = detector.detect(nut_sample())
        self.assertEqual(not_configured.status, "REVIEW")

    def test_nut_body_uses_stable_default_hex_angle(self) -> None:
        result = NutPresenceDetector().detect(nut_sample(), {"expectedCount": 2})
        self.assertEqual(result.metrics["candidateCount"], 2.0)
        self.assertEqual(
            [result.metrics[f"box{index}HexAngle"] for index in (1, 2)],
            [0.0, 0.0],
        )

    def test_nut_nms_removes_nested_candidates(self) -> None:
        result = NutPresenceDetector().detect(nut_sample(), {"expectedCount": 2})
        self.assertEqual(result.status, "PASS")
        self.assertEqual(result.metrics["candidateCount"], 2.0)
        self.assertGreater(result.metrics["candidateCountBeforeNms"], result.metrics["candidateCount"])
        for first_index, first in enumerate(result.boxes):
            for second in result.boxes[first_index + 1 :]:
                self.assertLess(box_containment(first, second), 0.70)

    def test_nut_rejects_circle_bright_spot_washer_nonhex_and_no_hole(self) -> None:
        detector = NutPresenceDetector()
        plain_circle = np.full((180, 180, 3), 30, dtype=np.uint8)
        cv2.circle(plain_circle, (90, 90), 55, (220, 220, 220), 5)
        bright_spot = np.full((180, 180, 3), 25, dtype=np.uint8)
        cv2.circle(bright_spot, (90, 90), 38, (250, 250, 250), -1)
        washer = np.full((180, 180, 3), 25, dtype=np.uint8)
        cv2.circle(washer, (90, 90), 60, (235, 235, 235), -1)
        cv2.circle(washer, (90, 90), 22, (30, 30, 30), -1)
        nonhex_background = np.full((180, 180, 3), 25, dtype=np.uint8)
        cv2.rectangle(nonhex_background, (25, 48), (155, 132), (225, 225, 225), -1)
        cv2.line(nonhex_background, (25, 48), (155, 132), (20, 20, 20), 3)
        no_hole = np.full((180, 180, 3), 25, dtype=np.uint8)
        polygon = cv2.ellipse2Poly((90, 90), (48, 48), 0, 0, 360, 60)
        cv2.fillPoly(no_hole, [polygon], (220, 220, 220))
        for name, image in (
            ("plain_circle", plain_circle),
            ("bright_spot", bright_spot),
            ("washer", washer),
            ("nonhex_background", nonhex_background),
            ("no_hole", no_hole),
        ):
            with self.subTest(name=name):
                result = detector.detect(image, {"expectedCount": 1})
                self.assertNotEqual(result.status, "PASS")
                self.assertEqual(result.metrics.get("candidateCount", 0.0), 0.0, result.message)

    def test_nut_key_batch_discovers_and_covers_all_samples(self) -> None:
        key_dir = Path(r"D:\study\Textile_defects\Wearable Inspection\Key")
        if not key_dir.is_dir():
            self.skipTest("offline Key directory is not available")
        files = sorted(
            path
            for path in key_dir.iterdir()
            if path.is_file() and path.stem.lower().startswith("nut_") and path.suffix.lower() in {".png", ".jpg", ".jpeg"}
        )
        self.assertTrue(files)
        self.assertIn("nut_1.png", {path.name.lower() for path in files})
        detector = NutPresenceDetector()
        for path in files:
            with self.subTest(file_name=path.name):
                image = load_bgr(path)
                # The user-confirmed Nut Key set contains two nuts in every discovered sample.
                config = {"expectedCount": 2}
                result = detector.detect(image, config)
                self.assertIn(result.status, {"PASS", "REVIEW"})
                self.assertEqual(result.metrics["candidateCount"], 2.0)
                self.assertEqual(result.metrics["candidateCount"], float(len(result.boxes)))
                for x, y, width, height in result.boxes:
                    self.assertGreaterEqual(x, 0)
                    self.assertGreaterEqual(y, 0)
                    self.assertGreater(width, 0)
                    self.assertGreater(height, 0)
                    self.assertLessEqual(x + width, image.shape[1])
                    self.assertLessEqual(y + height, image.shape[0])
                    self.assertGreaterEqual(min(width, height), 40)
                for first_index, first in enumerate(result.boxes):
                    for second in result.boxes[first_index + 1 :]:
                        self.assertLess(box_containment(first, second), 0.70)
                if path.name.lower() == "nut_1.png":
                    centers = sorted(x + width / 2.0 for x, _, width, _ in result.boxes)
                    self.assertLess(centers[0], image.shape[1] / 2.0)
                    self.assertGreater(centers[1], image.shape[1] / 2.0)

    def test_nut_debug_contains_audit_panel(self) -> None:
        image = nut_sample()
        with tempfile.TemporaryDirectory() as temporary:
            debug_path = Path(temporary) / "nut_debug.png"
            result = NutPresenceDetector().detect(image, {"expectedCount": 2}, debug_path=debug_path)
            debug = cv2.imread(str(debug_path))
            self.assertIsNotNone(debug)
            self.assertEqual(result.debug_path, str(debug_path))
            self.assertGreater(debug.shape[0], image.shape[0])
            self.assertTrue(np.any(np.all(debug == np.array([0, 220, 0]), axis=2)))

    def test_nut_generated_negative_samples_have_no_candidates(self) -> None:
        negative_dir = Path(__file__).resolve().parents[2] / "docs" / "reports" / "b3" / "feature_presence" / "evaluation" / "negative_nut"
        if not negative_dir.is_dir():
            self.skipTest("generated Nut negative samples are not available")
        files = sorted(negative_dir.glob("nut_negative_*.png"))
        self.assertTrue(files)
        detector = NutPresenceDetector()
        for path in files:
            with self.subTest(file_name=path.name):
                result = detector.detect(load_bgr(path), {"expectedCount": 0})
                self.assertNotEqual(result.status, "PASS")
                self.assertEqual(result.metrics.get("candidateCount", 0.0), 0.0, result.message)

    def test_nut_original_based_negatives_have_no_candidates(self) -> None:
        negative_dir = (
            Path(__file__).resolve().parents[2]
            / "docs"
            / "reports"
            / "b3"
            / "feature_presence"
            / "evaluation"
            / "negative_nut"
            / "original_based"
        )
        if not negative_dir.is_dir():
            self.skipTest("original-based Nut negative samples are not available")
        files = sorted(negative_dir.glob("nut_no_nuts__*.png"))
        self.assertGreaterEqual(len(files), 5)
        metadata_path = negative_dir / "nut_original_based_negative_results.json"
        self.assertTrue(metadata_path.is_file())
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        self.assertEqual(len(metadata), len(files))
        self.assertTrue(all(row.get("outsideRemovalMaskUnchanged") is True for row in metadata))
        detector = NutPresenceDetector()
        for path in files:
            with self.subTest(file_name=path.name):
                result = detector.detect(load_bgr(path), {"expectedCount": 0})
                self.assertNotEqual(result.status, "PASS")
                self.assertEqual(result.metrics.get("candidateCount", 0.0), 0.0, result.message)
                self.assertEqual(result.boxes, [])

    def test_feature_requires_geometry_and_template(self) -> None:
        detector = FeaturePresenceDetector()
        sample = feature_sample()
        same = detector.detect(sample, sample.copy())
        self.assertIn(same.status, {"PASS", "REVIEW"})
        self.assertGreater(same.metrics["templateMatchScore"], 0.95)
        self.assertEqual(same.boxes[0], [0, 0, sample.shape[1], sample.shape[0]])
        unrelated = detector.detect(np.full_like(sample, 30), sample)
        self.assertNotEqual(unrelated.status, "PASS")
        blank = np.full((80, 80, 3), 30, dtype=np.uint8)
        self.assertNotEqual(detector.detect(blank, blank.copy()).status, "PASS")
        missing = detector.detect(sample, None)
        self.assertEqual(missing.status, "ERROR")

    def test_feature_multiscale_match_reports_location(self) -> None:
        template = feature_sample()
        scaled = cv2.resize(template, None, fx=0.72, fy=0.72, interpolation=cv2.INTER_AREA)
        image = np.full((300, 330, 3), 30, dtype=np.uint8)
        image[40 : 40 + scaled.shape[0], 70 : 70 + scaled.shape[1]] = scaled
        result = FeaturePresenceDetector().detect(image, template)
        self.assertEqual(result.status, "PASS")
        self.assertGreater(result.metrics["templateMatchScore"], 0.70)
        self.assertAlmostEqual(result.metrics["templateMatchScale"], 0.70, delta=0.11)
        self.assertGreater(result.boxes[0][2], 120)

    def test_key_self_checks(self) -> None:
        key_dir = Path(r"D:\study\Textile_defects\Wearable Inspection\Key")
        if not key_dir.is_dir():
            self.skipTest("offline Key directory is not available")
        thread_detector = detector_for("THREAD_PRESENCE")
        self.assertEqual(thread_detector.detect(load_bgr(key_dir / "thread_1.png")).status, "PASS")
        self.assertEqual(thread_detector.detect(load_bgr(key_dir / "thread_2.png")).status, "PASS")
        nut = detector_for("NUT_PRESENCE").detect(load_bgr(key_dir / "nut_1.png"), {"expectedCount": 2})
        self.assertEqual(nut.status, "PASS")
        self.assertEqual(nut.metrics["candidateCount"], 2.0)
        for file_name in ("feature_1.png", "feature_2.png"):
            image = load_bgr(key_dir / file_name)
            self.assertEqual(detector_for("FEATURE_PRESENCE").detect(image, image.copy()).status, "PASS")

    def test_invalid_roi_is_not_accepted(self) -> None:
        image = feature_sample()
        cropped, error = crop_normalized(image, None)
        self.assertIsNone(cropped)
        self.assertEqual(error, "target ROI is not annotated")
        cropped, error = crop_normalized(image, [0.0, 0.0, 1.1, 1.0])
        self.assertIsNone(cropped)
        self.assertEqual(error, "ROI is outside normalized image bounds")


if __name__ == "__main__":
    unittest.main()
