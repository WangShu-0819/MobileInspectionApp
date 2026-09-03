import unittest

import cv2
import numpy as np

from extract_contours_traditional import (
    _expand_compact_mount,
    _quality_metrics,
    segment_assembly,
)


class TraditionalSegmentationTest(unittest.TestCase):
    @staticmethod
    def _striped_background(height, width):
        image = np.full((height, width, 3), 175, np.uint8)
        for x in range(0, width, 28):
            cv2.line(image, (x, 0), (x, height - 1), (115, 135, 145), 8)
        rng = np.random.default_rng(7)
        fiber_noise = rng.normal(0, 12, image.shape[:2]).astype(np.int16)
        return np.clip(image.astype(np.int16) + fiber_noise[:, :, None], 0, 255).astype(np.uint8)

    def test_dark_assembly_on_striped_background(self):
        image = self._striped_background(480, 640)
        self._assert_centered_ellipse(image)

    def test_dark_assembly_on_colored_checkerboard(self):
        image = np.empty((480, 640, 3), np.uint8)
        colors = ((70, 155, 190), (145, 195, 80))
        for y in range(0, 480, 32):
            for x in range(0, 640, 32):
                image[y:y + 32, x:x + 32] = colors[(x // 32 + y // 32) % 2]
        self._assert_centered_ellipse(image)

    def test_dark_assembly_on_noisy_background(self):
        rng = np.random.default_rng(19)
        image = rng.normal((130, 165, 180), 24, (480, 640, 3))
        image = np.clip(image, 0, 255).astype(np.uint8)
        self._assert_centered_ellipse(image)

    def test_cyan_mark_selects_key_part_over_center_distractor(self):
        image = np.full((480, 640, 3), 175, np.uint8)
        cv2.rectangle(image, (375, 105), (605, 385), (28, 28, 28), -1)
        cv2.ellipse(image, (255, 240), (95, 125), 0, 0, 360, (28, 28, 28), -1)
        cv2.line(image, (430, 180), (550, 205), (190, 205, 5), 18)

        mask, _ = segment_assembly(image, max_edge=640)

        self.assertEqual(mask[250, 490], 255)
        self.assertEqual(mask[240, 255], 0)

    def test_marker_cut_by_image_edge_is_ignored(self):
        image = np.full((480, 640, 3), 175, np.uint8)
        cv2.rectangle(image, (0, 40), (150, 180), (28, 28, 28), -1)
        cv2.line(image, (0, 90), (95, 110), (190, 205, 5), 14)
        cv2.ellipse(image, (350, 270), (150, 95), 0, 0, 360, (28, 28, 28), -1)

        mask, _ = segment_assembly(image, max_edge=640)

        self.assertEqual(mask[270, 350], 255)
        self.assertEqual(mask[100, 40], 0)

    def test_compact_insert_expands_only_to_enclosing_mount(self):
        image = self._striped_background(480, 640)
        cv2.circle(image, (320, 240), 115, (35, 35, 35), -1)
        cv2.circle(image, (320, 240), 70, (210, 210, 210), -1)
        cv2.line(image, (310, 170), (330, 170), (190, 205, 5), 8)

        mask, _ = segment_assembly(image, max_edge=640)

        self.assertEqual(mask[240, 320], 255)
        self.assertEqual(mask[240, 415], 255)
        self.assertEqual(mask[240, 450], 0)

    def test_compact_insert_without_marker_never_expands(self):
        image = self._striped_background(480, 640)
        cv2.circle(image, (320, 240), 115, (35, 35, 35), -1)
        cv2.circle(image, (320, 240), 70, (210, 210, 210), -1)
        insert = np.zeros((480, 640), np.uint8)
        cv2.circle(insert, (320, 240), 70, 255, -1)

        self.assertIsNone(_expand_compact_mount(image, insert))

    def test_thin_highlight_candidate_is_not_projection_ready(self):
        mask = np.zeros((480, 640), np.uint8)
        cv2.rectangle(mask, (140, 225), (500, 240), 255, -1)

        metrics = _quality_metrics(mask, "OTSU_KEY_REGION")

        self.assertEqual(metrics["status"], "NEEDS_RECAPTURE")
        self.assertLess(metrics["coverage"], 0.04)

    def _assert_centered_ellipse(self, image):
        expected = np.zeros((480, 640), np.uint8)
        cv2.ellipse(expected, (320, 240), (190, 125), 0, 0, 360, 255, -1)
        image[expected > 0] = (24, 24, 24)
        cv2.circle(image, (320, 240), 38, (190, 190, 190), -1)
        mask, _ = segment_assembly(image, max_edge=640)
        intersection = np.count_nonzero((mask > 0) & (expected > 0))
        union = np.count_nonzero((mask > 0) | (expected > 0))
        self.assertGreater(intersection / union, 0.8)
        self.assertEqual(mask[240, 320], 255)


if __name__ == "__main__":
    unittest.main()
