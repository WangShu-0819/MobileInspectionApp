package com.wearable.inspection.mobile.ui.screens

import com.wearable.inspection.mobile.registration.ProjectedPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ROI 安全边距与裁剪一致性测试。
 *
 * 所有投影矩形计算测试直接调用生产函数 [RoiCoordinateMapper.computeProjectedRect]，
 * 不复制生产公式。
 *
 * 覆盖：
 * 1. expandNormalizedRect 纯函数
 * 2. identity homography
 * 3. 平移
 * 4. 透视
 * 5. 旋转
 * 6. EXIF ROTATE_90
 * 7. 小 ROI
 * 8. 安全边距扩展
 * 9. floor/ceil 像素取整
 * 10. 目标出框 → 返回 null（fallback）
 * 11. 对齐显示框、pixelRect、crop 和推理输入一致
 * 12. MIN_SESSION_ROI_SIZE 小 ROI 行为
 */
class RoiSafetyMarginTest {

    private val defaultMargin = CaptureComparisonViewModel.ROI_SAFETY_MARGIN_RATIO
    private val defaultMinSize = 0.02f

    // ═══════════════════════════════════════════════════════════════════
    // 1. expandNormalizedRect 纯函数
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `expandNormalizedRect with zero margin returns same rect`() {
        val rect = NormalizedRect(0.2f, 0.3f, 0.6f, 0.8f)
        val expanded = RoiCoordinateMapper.expandNormalizedRect(rect, 0f)
        assertEquals(rect.left, expanded.left, 1e-6f)
        assertEquals(rect.top, expanded.top, 1e-6f)
        assertEquals(rect.right, expanded.right, 1e-6f)
        assertEquals(rect.bottom, expanded.bottom, 1e-6f)
    }

    @Test
    fun `expandNormalizedRect expands symmetrically`() {
        val rect = NormalizedRect(0.2f, 0.3f, 0.6f, 0.8f)
        val expanded = RoiCoordinateMapper.expandNormalizedRect(rect, 0.10f)
        // width = 0.4, dw = 0.4 * 0.10 / 2 = 0.02
        // height = 0.5, dh = 0.5 * 0.10 / 2 = 0.025
        assertEquals(0.18f, expanded.left, 1e-5f)
        assertEquals(0.275f, expanded.top, 1e-5f)
        assertEquals(0.62f, expanded.right, 1e-5f)
        assertEquals(0.825f, expanded.bottom, 1e-5f)
    }

    @Test
    fun `expandNormalizedRect clamps to 0 and 1`() {
        val rect = NormalizedRect(0.01f, 0.02f, 0.98f, 0.99f)
        val expanded = RoiCoordinateMapper.expandNormalizedRect(rect, 0.50f)
        assertTrue(expanded.left >= 0f)
        assertTrue(expanded.top >= 0f)
        assertTrue(expanded.right <= 1f)
        assertTrue(expanded.bottom <= 1f)
    }

    @Test
    fun `expandNormalizedRect never shrinks`() {
        val rect = NormalizedRect(0.1f, 0.2f, 0.5f, 0.7f)
        val expanded = RoiCoordinateMapper.expandNormalizedRect(rect, 0.10f)
        assertTrue(expanded.left <= rect.left)
        assertTrue(expanded.top <= rect.top)
        assertTrue(expanded.right >= rect.right)
        assertTrue(expanded.bottom >= rect.bottom)
    }

    @Test
    fun `expandNormalizedRect rejects negative margin`() {
        try {
            RoiCoordinateMapper.expandNormalizedRect(NormalizedRect(0.1f, 0.2f, 0.5f, 0.7f), -0.1f)
            assertTrue(false, "Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 2. Identity homography — computeProjectedRect
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `identity homography - all corners inside scene - returns expanded rect`() {
        // ROI at [200, 300, 600, 700] on 1000x1000 scene
        val corners = listOf(
            ProjectedPoint(200.0, 300.0),
            ProjectedPoint(600.0, 300.0),
            ProjectedPoint(600.0, 700.0),
            ProjectedPoint(200.0, 700.0),
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, 1000, 1000, defaultMargin, defaultMinSize,
        )
        assertNotNull(result)
        // Tight AABB = [0.2, 0.3, 0.6, 0.7], expanded by 10%
        assertTrue(result.left <= 0.2f, "left=${result.left} must be <= 0.2")
        assertTrue(result.top <= 0.3f, "top=${result.top} must be <= 0.3")
        assertTrue(result.right >= 0.6f, "right=${result.right} must be >= 0.6")
        assertTrue(result.bottom >= 0.7f, "bottom=${result.bottom} must be >= 0.7")
        // Must be within [0,1]
        assertTrue(result.left >= 0f)
        assertTrue(result.top >= 0f)
        assertTrue(result.right <= 1f)
        assertTrue(result.bottom <= 1f)
    }

    // ═══════════════════════════════════════════════════════════════════
    // 3. 平移 — translated corners
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `translated homography - corners shifted right-down - returns expanded rect`() {
        val corners = listOf(
            ProjectedPoint(300.0, 400.0),
            ProjectedPoint(700.0, 400.0),
            ProjectedPoint(700.0, 800.0),
            ProjectedPoint(300.0, 800.0),
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, 1000, 1000, defaultMargin, defaultMinSize,
        )
        assertNotNull(result)
        // Tight = [0.3, 0.4, 0.7, 0.8]
        assertTrue(result.left <= 0.3f)
        assertTrue(result.top <= 0.4f)
        assertTrue(result.right >= 0.7f)
        assertTrue(result.bottom >= 0.8f)
    }

    // ═══════════════════════════════════════════════════════════════════
    // 4. 透视 — perspective-distorted quad
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `perspective distorted quad - all corners inside - returns expanded AABB`() {
        val corners = listOf(
            ProjectedPoint(100.0, 150.0),
            ProjectedPoint(500.0, 120.0),  // shifted up
            ProjectedPoint(520.0, 480.0),  // shifted right
            ProjectedPoint(80.0, 450.0),   // shifted left
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, 1000, 1000, defaultMargin, defaultMinSize,
        )
        assertNotNull(result)
        // All four corners must be inside the expanded rect
        assertTrue(corners.all { pt ->
            val nx = (pt.x / 1000).toFloat()
            val ny = (pt.y / 1000).toFloat()
            nx >= result.left && nx <= result.right && ny >= result.top && ny <= result.bottom
        }, "All projected corners must be inside expanded rect")
    }

    // ═══════════════════════════════════════════════════════════════════
    // 5. 旋转 — rotated rect AABB
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `rotated rect corners inside scene - returns expanded rect`() {
        val cx = 500.0; val cy = 500.0; val hw = 100.0; val hh = 50.0
        val angle = Math.PI / 4; val cos = Math.cos(angle); val sin = Math.sin(angle)
        val corners = listOf(
            ProjectedPoint(cx - hw * cos + hh * sin, cy - hw * sin - hh * cos),
            ProjectedPoint(cx + hw * cos + hh * sin, cy + hw * sin - hh * cos),
            ProjectedPoint(cx + hw * cos - hh * sin, cy + hw * sin + hh * cos),
            ProjectedPoint(cx - hw * cos - hh * sin, cy - hw * sin + hh * cos),
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, 1000, 1000, defaultMargin, defaultMinSize,
        )
        assertNotNull(result)
        assertTrue(corners.all { pt ->
            val nx = (pt.x / 1000).toFloat()
            val ny = (pt.y / 1000).toFloat()
            nx >= result.left && nx <= result.right && ny >= result.top && ny <= result.bottom
        })
    }

    // ═══════════════════════════════════════════════════════════════════
    // 6. EXIF ROTATE_90 — transformNormalizedRect + computeProjectedRect
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `EXIF ROTATE_90 template ROI - identity homography - returns expanded rect`() {
        // Template ROI [0.1, 0.2, 0.5, 0.7] with EXIF ROTATE_90
        val templateRoi = NormalizedRect(0.1f, 0.2f, 0.5f, 0.7f)
        val uprightRoi = RoiCoordinateMapper.transformNormalizedRect(templateRoi, 6)
        // After ROTATE_90: [1-0.7, 0.1, 1-0.2, 0.5] = [0.3, 0.1, 0.8, 0.5]
        // Scene: 3024x4032 (portrait after rotation)
        val sceneW = 3024; val sceneH = 4032
        // Simulate identity homography: projected corners = upright ROI in pixels
        val corners = listOf(
            ProjectedPoint(uprightRoi.left * sceneW.toDouble(), uprightRoi.top * sceneH.toDouble()),
            ProjectedPoint(uprightRoi.right * sceneW.toDouble(), uprightRoi.top * sceneH.toDouble()),
            ProjectedPoint(uprightRoi.right * sceneW.toDouble(), uprightRoi.bottom * sceneH.toDouble()),
            ProjectedPoint(uprightRoi.left * sceneW.toDouble(), uprightRoi.bottom * sceneH.toDouble()),
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, sceneW, sceneH, defaultMargin, defaultMinSize,
        )
        assertNotNull(result)
        assertTrue(result.left <= uprightRoi.left)
        assertTrue(result.right >= uprightRoi.right)

        // Verify pixel consistency with mapToImagePixels
        val displayPixels = RoiCoordinateMapper.mapToImagePixels(result, sceneW, sceneH)
        val inferencePixels = RoiCoordinateMapper.mapTemplateRoiToPhotoPixels(
            result,
            android.media.ExifInterface.ORIENTATION_NORMAL,
            RoiCoordinateMapper.PhotoGeometry(sceneW, sceneH, android.media.ExifInterface.ORIENTATION_NORMAL),
        )
        assertEquals(displayPixels.left, inferencePixels.left)
        assertEquals(displayPixels.top, inferencePixels.top)
        assertEquals(displayPixels.right, inferencePixels.right)
        assertEquals(displayPixels.bottom, inferencePixels.bottom)
    }

    // ═══════════════════════════════════════════════════════════════════
    // 7. 小 ROI
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `small ROI at 5 percent - passes minSize check`() {
        // 5% of scene: well above MIN_SESSION_ROI_SIZE=0.02
        val corners = listOf(
            ProjectedPoint(475.0, 475.0),
            ProjectedPoint(525.0, 475.0),
            ProjectedPoint(525.0, 525.0),
            ProjectedPoint(475.0, 525.0),
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, 1000, 1000, defaultMargin, defaultMinSize,
        )
        assertNotNull(result, "5% ROI must pass minSize check")
    }

    @Test
    fun `small ROI at 1 percent - rejected by MIN_SESSION_ROI_SIZE`() {
        // 1% of scene: below MIN_SESSION_ROI_SIZE=0.02
        val corners = listOf(
            ProjectedPoint(495.0, 495.0),
            ProjectedPoint(505.0, 495.0),
            ProjectedPoint(505.0, 505.0),
            ProjectedPoint(495.0, 505.0),
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, 1000, 1000, defaultMargin, defaultMinSize,
        )
        assertNull(result, "1% ROI must be rejected by MIN_SESSION_ROI_SIZE → fallback")
    }

    @Test
    fun `small ROI at 2 percent - passes minSize check at boundary`() {
        // Exactly 2% of scene: at MIN_SESSION_ROI_SIZE=0.02
        // With 10% margin expansion, width becomes 0.02 * 1.1 = 0.022 > 0.02
        val corners = listOf(
            ProjectedPoint(490.0, 490.0),
            ProjectedPoint(510.0, 490.0),
            ProjectedPoint(510.0, 510.0),
            ProjectedPoint(490.0, 510.0),
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, 1000, 1000, defaultMargin, defaultMinSize,
        )
        // After margin: 0.02 * 1.1 = 0.022 >= 0.02 → passes
        assertNotNull(result, "2% ROI with 10% margin (0.022) must pass minSize check")
    }

    @Test
    fun `small ROI at 1_5 percent with zero margin - rejected by minSize`() {
        // 1.5% with 0% margin: 0.015 < 0.02 → rejected
        val corners = listOf(
            ProjectedPoint(492.5, 492.5),
            ProjectedPoint(507.5, 492.5),
            ProjectedPoint(507.5, 507.5),
            ProjectedPoint(492.5, 507.5),
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, 1000, 1000, 0f, defaultMinSize,
        )
        assertNull(result, "1.5% ROI with 0% margin must be rejected")
    }

    // ═══════════════════════════════════════════════════════════════════
    // 8. 安全边距扩展
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `safety margin ratio 0_10 expands tight AABB by 10 percent`() {
        // Tight AABB [0.2, 0.3, 0.6, 0.7] → width=0.4, expanded to 0.44
        val corners = listOf(
            ProjectedPoint(200.0, 300.0),
            ProjectedPoint(600.0, 300.0),
            ProjectedPoint(600.0, 700.0),
            ProjectedPoint(200.0, 700.0),
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, 1000, 1000, 0.10f, 0f,
        )
        assertNotNull(result)
        val width = result.right - result.left
        val height = result.bottom - result.top
        assertEquals(0.44f, width, 1e-4f, "width must be 0.4 * 1.1 = 0.44")
        assertEquals(0.44f, height, 1e-4f, "height must be 0.4 * 1.1 = 0.44")
    }

    @Test
    fun `safety margin 0 produces tight AABB`() {
        val corners = listOf(
            ProjectedPoint(200.0, 300.0),
            ProjectedPoint(600.0, 300.0),
            ProjectedPoint(600.0, 700.0),
            ProjectedPoint(200.0, 700.0),
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, 1000, 1000, 0f, 0f,
        )
        assertNotNull(result)
        assertEquals(0.2f, result.left, 1e-4f)
        assertEquals(0.3f, result.top, 1e-4f)
        assertEquals(0.6f, result.right, 1e-4f)
        assertEquals(0.7f, result.bottom, 1e-4f)
    }

    // ═══════════════════════════════════════════════════════════════════
    // 9. floor/ceil 像素取整
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `expanded rect maps to pixels covering tight AABB pixels`() {
        val tight = NormalizedRect(0.2f, 0.3f, 0.6f, 0.7f)
        val expanded = RoiCoordinateMapper.expandNormalizedRect(tight, 0.10f)
        val tightPixels = RoiCoordinateMapper.mapToImagePixels(tight, 1000, 1000)
        val expandedPixels = RoiCoordinateMapper.mapToImagePixels(expanded, 1000, 1000)
        assertTrue(expandedPixels.left <= tightPixels.left)
        assertTrue(expandedPixels.top <= tightPixels.top)
        assertTrue(expandedPixels.right >= tightPixels.right)
        assertTrue(expandedPixels.bottom >= tightPixels.bottom)
    }

    @Test
    fun `very small expanded rect preserves at least one pixel`() {
        val rect = NormalizedRect(0.499f, 0.499f, 0.501f, 0.501f)
        val expanded = RoiCoordinateMapper.expandNormalizedRect(rect, 0.10f)
        val pixels = RoiCoordinateMapper.mapToImagePixels(expanded, 1000, 1000)
        assertTrue(pixels.width > 0)
        assertTrue(pixels.height > 0)
    }

    // ═══════════════════════════════════════════════════════════════════
    // 10. 目标出框 → null (fallback)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `one corner outside scene - returns null for fallback`() {
        val corners = listOf(
            ProjectedPoint(200.0, 300.0),
            ProjectedPoint(600.0, 300.0),
            ProjectedPoint(600.0, 700.0),
            ProjectedPoint(-10.0, 700.0), // outside left
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, 1000, 1000, defaultMargin, defaultMinSize,
        )
        assertNull(result, "Corner outside scene must trigger fallback")
    }

    @Test
    fun `all corners outside scene - returns null`() {
        val corners = listOf(
            ProjectedPoint(-100.0, -100.0),
            ProjectedPoint(-50.0, -100.0),
            ProjectedPoint(-50.0, -50.0),
            ProjectedPoint(-100.0, -50.0),
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, 1000, 1000, defaultMargin, defaultMinSize,
        )
        assertNull(result, "All corners outside must trigger fallback")
    }

    @Test
    fun `corner slightly outside due to floating point - returns null`() {
        // Corner at -0.5 on a 1000-wide scene: -0.0005 normalized, beyond tolerance
        val corners = listOf(
            ProjectedPoint(200.0, 300.0),
            ProjectedPoint(600.0, 300.0),
            ProjectedPoint(600.0, 700.0),
            ProjectedPoint(-0.5, 700.0),
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, 1000, 1000, defaultMargin, defaultMinSize,
        )
        assertNull(result, "Even slightly outside corner must trigger fallback")
    }

    @Test
    fun `corner at exact scene boundary - accepted with tolerance`() {
        // Corner at exactly 0.0 — within tolerance
        val corners = listOf(
            ProjectedPoint(0.0, 0.0),
            ProjectedPoint(400.0, 0.0),
            ProjectedPoint(400.0, 400.0),
            ProjectedPoint(0.0, 400.0),
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, 1000, 1000, defaultMargin, defaultMinSize,
        )
        assertNotNull(result, "Corner at exact boundary must be accepted")
    }

    @Test
    fun `corner with tiny floating point noise at boundary - accepted`() {
        // Corner at -0.00001 on 1000 scene = -1e-8 normalized, well within tolerance
        val corners = listOf(
            ProjectedPoint(-0.01, -0.01),  // within 1e-4 * 1000 = 0.1 tolerance
            ProjectedPoint(400.0, -0.01),
            ProjectedPoint(400.0, 400.0),
            ProjectedPoint(-0.01, 400.0),
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, 1000, 1000, defaultMargin, defaultMinSize,
        )
        assertNotNull(result, "Floating-point noise within tolerance must be accepted")
    }

    @Test
    fun `corner outside right edge - returns null`() {
        val corners = listOf(
            ProjectedPoint(800.0, 400.0),
            ProjectedPoint(1050.0, 400.0), // outside right
            ProjectedPoint(1050.0, 800.0),
            ProjectedPoint(800.0, 800.0),
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, 1000, 1000, defaultMargin, defaultMinSize,
        )
        assertNull(result, "Corner outside right edge must trigger fallback")
    }

    @Test
    fun `corner outside bottom edge - returns null`() {
        val corners = listOf(
            ProjectedPoint(400.0, 800.0),
            ProjectedPoint(800.0, 800.0),
            ProjectedPoint(800.0, 1020.0), // outside bottom
            ProjectedPoint(400.0, 1020.0),
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, 1000, 1000, defaultMargin, defaultMinSize,
        )
        assertNull(result, "Corner outside bottom edge must trigger fallback")
    }

    // ═══════════════════════════════════════════════════════════════════
    // 11. 对齐显示框、pixelRect、crop 和推理输入一致
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `expanded rect maps identically via mapToImagePixels and mapTemplateRoiToPhotoPixels`() {
        val expanded = RoiCoordinateMapper.expandNormalizedRect(
            NormalizedRect(0.2f, 0.3f, 0.6f, 0.7f), 0.10f,
        )
        val imageW = 4032; val imageH = 3024
        val direct = RoiCoordinateMapper.mapToImagePixels(expanded, imageW, imageH)
        val viaTemplate = RoiCoordinateMapper.mapTemplateRoiToPhotoPixels(
            expanded,
            android.media.ExifInterface.ORIENTATION_NORMAL,
            RoiCoordinateMapper.PhotoGeometry(imageW, imageH, android.media.ExifInterface.ORIENTATION_NORMAL),
        )
        assertEquals(direct.left, viaTemplate.left)
        assertEquals(direct.top, viaTemplate.top)
        assertEquals(direct.right, viaTemplate.right)
        assertEquals(direct.bottom, viaTemplate.bottom)
    }

    // ═══════════════════════════════════════════════════════════════════
    // 12. ROI_SAFETY_MARGIN_RATIO constant
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `ROI safety margin ratio is 10 percent`() {
        assertEquals(0.10f, CaptureComparisonViewModel.ROI_SAFETY_MARGIN_RATIO)
    }

    // ═══════════════════════════════════════════════════════════════════
    // 13. computeProjectedRect with NaN corners - returns null
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `computeProjectedRect with NaN corner - returns null`() {
        val corners = listOf(
            ProjectedPoint(200.0, 300.0),
            ProjectedPoint(Double.NaN, 300.0),
            ProjectedPoint(600.0, 700.0),
            ProjectedPoint(200.0, 700.0),
        )
        // NaN corners should fail the boundary check
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, 1000, 1000, defaultMargin, defaultMinSize,
        )
        assertNull(result, "NaN corner must trigger fallback")
    }

    // ═══════════════════════════════════════════════════════════════════
    // 14. MIN_SESSION_ROI_SIZE small ROI behavior
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `MIN_SESSION_ROI_SIZE 0_02 on 4032x3024 scene - thread ROI at 80px width passes`() {
        // 80px / 4032 = 0.01985 ≈ 2% — at boundary
        // With 10% margin: 0.01985 * 1.1 = 0.0218 >= 0.02 → passes
        val corners = listOf(
            ProjectedPoint(1976.0, 1462.0),
            ProjectedPoint(2056.0, 1462.0),  // 80px wide
            ProjectedPoint(2056.0, 1562.0),  // 100px tall
            ProjectedPoint(1976.0, 1562.0),
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, 4032, 3024, defaultMargin, defaultMinSize,
        )
        assertNotNull(result, "80px-wide ROI on 4032 scene with margin must pass")
    }

    @Test
    fun `MIN_SESSION_ROI_SIZE 0_02 on 4032x3024 scene - tiny feature at 40px rejected`() {
        // 40px / 4032 = 0.00992 < 0.02
        // With 10% margin: 0.00992 * 1.1 = 0.01091 < 0.02 → rejected
        val corners = listOf(
            ProjectedPoint(1996.0, 1492.0),
            ProjectedPoint(2036.0, 1492.0),  // 40px wide
            ProjectedPoint(2036.0, 1532.0),  // 40px tall
            ProjectedPoint(1996.0, 1532.0),
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, 4032, 3024, defaultMargin, defaultMinSize,
        )
        assertNull(result, "40px feature on 4032 scene must be rejected → fallback")
    }

    @Test
    fun `MIN_SESSION_ROI_SIZE check is on expanded rect not tight AABB`() {
        // Tight AABB is 1.5% but with 10% margin it becomes 1.65% < 2% → rejected
        val corners = listOf(
            ProjectedPoint(492.5, 492.5),
            ProjectedPoint(507.5, 492.5),   // 15px on 1000 scene = 1.5%
            ProjectedPoint(507.5, 507.5),
            ProjectedPoint(492.5, 507.5),
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, 1000, 1000, 0.10f, defaultMinSize,
        )
        assertNull(result, "1.5% tight with 10% margin = 1.65% < 2% must be rejected")
    }

    // ═══════════════════════════════════════════════════════════════════
    // 15. end-to-end: pixel consistency through full pipeline
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `end-to-end identity homography - display and inference pixels match`() {
        val sceneW = 4032; val sceneH = 3024
        val corners = listOf(
            ProjectedPoint(806.4, 907.2),   // 0.2 * 4032, 0.3 * 3024
            ProjectedPoint(2419.2, 907.2),  // 0.6 * 4032
            ProjectedPoint(2419.2, 2116.8), // 0.7 * 3024
            ProjectedPoint(806.4, 2116.8),
        )
        val result = RoiCoordinateMapper.computeProjectedRect(
            corners, sceneW, sceneH, defaultMargin, defaultMinSize,
        )
        assertNotNull(result)

        val displayPixels = RoiCoordinateMapper.mapToImagePixels(result, sceneW, sceneH)
        val inferencePixels = RoiCoordinateMapper.mapTemplateRoiToPhotoPixels(
            result,
            android.media.ExifInterface.ORIENTATION_NORMAL,
            RoiCoordinateMapper.PhotoGeometry(sceneW, sceneH, android.media.ExifInterface.ORIENTATION_NORMAL),
        )
        assertEquals(displayPixels.left, inferencePixels.left)
        assertEquals(displayPixels.top, inferencePixels.top)
        assertEquals(displayPixels.right, inferencePixels.right)
        assertEquals(displayPixels.bottom, inferencePixels.bottom)
    }
}
