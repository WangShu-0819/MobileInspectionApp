import unittest

import cv2
import numpy as np

from extract_alignment_guides import build_guide_lines


class AlignmentGuideTest(unittest.TestCase):
    def test_lines_stay_inside_part_mask_and_keep_structure(self):
        image = np.full((480, 640, 3), 150, np.uint8)
        for x in range(0, 640, 24):
            cv2.line(image, (x, 0), (x, 479), (115, 125, 135), 5)
        mask = np.zeros((480, 640), np.uint8)
        cv2.rectangle(mask, (150, 110), (510, 370), 255, -1)
        image[mask > 0] = (30, 30, 30)
        cv2.circle(image, (250, 240), 42, (190, 190, 190), -1)
        cv2.circle(image, (410, 240), 28, (190, 190, 190), -1)

        guide, lines = build_guide_lines(image, mask)

        self.assertGreaterEqual(len(lines), 2)
        self.assertGreater(cv2.countNonZero(guide), 0)
        allowed = cv2.dilate(mask, np.ones((9, 9), np.uint8))
        self.assertEqual(np.count_nonzero((guide > 0) & (allowed == 0)), 0)
        inner = cv2.erode(mask, np.ones((31, 31), np.uint8))
        self.assertGreater(np.count_nonzero((guide > 0) & (inner > 0)), 0)
        self.assertGreater(np.count_nonzero(guide[105:116, 180:480]), 0)
        self.assertGreater(np.count_nonzero(guide[365:376, 180:480]), 0)
        self.assertGreater(np.count_nonzero(guide[140:340, 145:156]), 0)
        self.assertGreater(np.count_nonzero(guide[140:340, 505:516]), 0)


if __name__ == "__main__":
    unittest.main()
