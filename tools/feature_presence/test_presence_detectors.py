"""Focused offline tests for the three independent detectors."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

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
    for radius in (25, 31, 37, 43, 49):
        cv2.circle(image, (center, center), radius, (215, 215, 215), 3)
    cv2.circle(image, (center, center), 58, (230, 230, 230), 4)
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

    def test_nut_count_is_configurable(self) -> None:
        detector = NutPresenceDetector()
        configured = detector.detect(nut_sample(), {"expectedCount": 2})
        self.assertIn(configured.status, {"PASS", "REVIEW"})
        mismatch = detector.detect(nut_sample(), {"expectedCount": 1})
        self.assertEqual(mismatch.status, "FAIL")
        not_configured = detector.detect(nut_sample())
        self.assertEqual(not_configured.status, "REVIEW")

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
