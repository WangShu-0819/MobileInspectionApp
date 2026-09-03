import unittest

import cv2
import numpy as np

from extract_contours import contour_payload, contours_from_mask, segment_part


class ContourExtractionTest(unittest.TestCase):
    def test_extracts_centered_dark_part(self):
        image = np.full((480, 640, 3), 210, np.uint8)
        cv2.rectangle(image, (130, 100), (510, 390), (25, 25, 25), -1)
        cv2.circle(image, (320, 245), 65, (210, 210, 210), -1)
        mask, quality = segment_part(image, max_edge=640)
        contours = contours_from_mask(mask)
        self.assertEqual("NEEDS_CONFIRMATION", quality["status"])
        self.assertGreater(quality["coverage"], 0.25)
        self.assertEqual(1, len(contours))

    def test_normalized_payload_is_bounded(self):
        mask = np.zeros((100, 200), np.uint8)
        cv2.ellipse(mask, (100, 50), (60, 30), 0, 0, 360, 255, -1)
        payload = contour_payload(contours_from_mask(mask), 200, 100)
        self.assertTrue(payload)
        self.assertTrue(all(0 <= point[axis] <= 1 for contour in payload for point in contour for axis in ("x", "y")))


if __name__ == "__main__":
    unittest.main()
