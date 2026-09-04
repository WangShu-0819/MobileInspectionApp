package com.wearable.inspection.mobile.ui.screens

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RoiCoordinateMapper 单元测试
 *
 * 覆盖：
 * 1. normalizedRect 到拍摄图像 contentRect 的坐标映射
 * 2. 每张照片生成全部 ROI 子图（裁剪坐标计算）
 * 3. 边界情况处理
 */
class RoiCoordinateMapperTest {

    // ========== parseNormalizedRect ==========

    @Test
    fun `parseNormalizedRect accepts valid rect`() {
        val json = JSONObject().apply {
            put("left", 0.1)
            put("top", 0.2)
            put("right", 0.8)
            put("bottom", 0.9)
        }.toString()
        val rect = RoiCoordinateMapper.parseNormalizedRect(json)
        assertNotNull(rect)
        assertEquals(0.1f, rect.left)
        assertEquals(0.2f, rect.top)
        assertEquals(0.8f, rect.right)
        assertEquals(0.9f, rect.bottom)
    }

    @Test
    fun `parseNormalizedRect rejects left greater than right`() {
        val json = JSONObject().apply {
            put("left", 0.8)
            put("top", 0.2)
            put("right", 0.1)
            put("bottom", 0.9)
        }.toString()
        assertNull(RoiCoordinateMapper.parseNormalizedRect(json))
    }

    @Test
    fun `parseNormalizedRect rejects top greater than bottom`() {
        val json = JSONObject().apply {
            put("left", 0.1)
            put("top", 0.9)
            put("right", 0.8)
            put("bottom", 0.2)
        }.toString()
        assertNull(RoiCoordinateMapper.parseNormalizedRect(json))
    }

    @Test
    fun `parseNormalizedRect rejects out of range values`() {
        val json = JSONObject().apply {
            put("left", -0.1)
            put("top", 0.2)
            put("right", 0.8)
            put("bottom", 0.9)
        }.toString()
        assertNull(RoiCoordinateMapper.parseNormalizedRect(json))
    }

    @Test
    fun `parseNormalizedRect rejects invalid JSON`() {
        assertNull(RoiCoordinateMapper.parseNormalizedRect("not-json"))
    }

    @Test
    fun `parseNormalizedRect rejects NaN values`() {
        val json = JSONObject().apply {
            put("left", "NaN")
            put("top", 0.2)
            put("right", 0.8)
            put("bottom", 0.9)
        }.toString()
        assertNull(RoiCoordinateMapper.parseNormalizedRect(json))
    }

    // ========== mapToImagePixels ==========

    @Test
    fun `NormalizedRect constructor works correctly`() {
        // Debug: verify NormalizedRect properties
        val rect = NormalizedRect(0f, 0f, 1f, 1f)
        assertEquals(0f, rect.left)
        assertEquals(0f, rect.top)
        assertEquals(1f, rect.right)
        assertEquals(1f, rect.bottom)
    }

    @Test
    fun `mapToImagePixels maps full rect correctly`() {
        // Use parseNormalizedRect to ensure we get the right NormalizedRect type
        val rect = RoiCoordinateMapper.parseNormalizedRect(
            """{"left":0.0,"top":0.0,"right":1.0,"bottom":1.0}"""
        )!!
        assertEquals(1f, rect.right) // Debug check
        val pixelRect = RoiCoordinateMapper.mapToImagePixels(rect, 4032, 3024)
        assertEquals(0, pixelRect.left)
        assertEquals(0, pixelRect.top)
        assertEquals(4032, pixelRect.right)
        assertEquals(3024, pixelRect.bottom)
    }

    @Test
    fun `mapToImagePixels maps partial rect correctly`() {
        val rect = RoiCoordinateMapper.parseNormalizedRect(
            """{"left":0.5,"top":0.5,"right":0.75,"bottom":0.75}"""
        )!!
        val pixelRect = RoiCoordinateMapper.mapToImagePixels(rect, 4000, 3000)
        assertEquals(2000, pixelRect.left)
        assertEquals(1500, pixelRect.top)
        assertEquals(3000, pixelRect.right)
        assertEquals(2250, pixelRect.bottom)
    }

    @Test
    fun `mapToImagePixels clamps to image bounds`() {
        val rect = RoiCoordinateMapper.parseNormalizedRect(
            """{"left":0.0,"top":0.0,"right":1.0,"bottom":1.0}"""
        )!!
        val pixelRect = RoiCoordinateMapper.mapToImagePixels(rect, 100, 100)
        assertTrue(pixelRect.left >= 0)
        assertTrue(pixelRect.top >= 0)
        assertTrue(pixelRect.right <= 100)
        assertTrue(pixelRect.bottom <= 100)
    }

    @Test
    fun `mapToImagePixels handles small image`() {
        val rect = RoiCoordinateMapper.parseNormalizedRect(
            """{"left":0.5,"top":0.5,"right":1.0,"bottom":1.0}"""
        )!!
        val pixelRect = RoiCoordinateMapper.mapToImagePixels(rect, 10, 10)
        assertEquals(5, pixelRect.left)
        assertEquals(5, pixelRect.top)
        assertEquals(10, pixelRect.right)
        assertEquals(10, pixelRect.bottom)
    }

    @Test
    fun `mapToImagePixels matches contentRect mapping formula`() {
        // 验证与 LiveInspectionScreen 中的 mapNormalizedRectToContentRect 使用相同映射逻辑
        val rect = RoiCoordinateMapper.parseNormalizedRect(
            """{"left":0.5,"top":0.5,"right":0.75,"bottom":0.75}"""
        )!!
        val contentResult = mapNormalizedRectToContentRect(
            rect, ContentRectBounds(0, 0, 800, 600)
        )
        val imageResult = RoiCoordinateMapper.mapToImagePixels(rect, 800, 600)

        assertNotNull(contentResult)
        assertEquals(contentResult.left.toInt(), imageResult.left)
        assertEquals(contentResult.top.toInt(), imageResult.top)
        assertEquals(contentResult.right.toInt(), imageResult.right)
        assertEquals(contentResult.bottom.toInt(), imageResult.bottom)
    }

    // ========== getImageDimensions ==========

    @Test
    fun `getImageDimensions returns null for nonexistent file`() {
        assertNull(RoiCoordinateMapper.getImageDimensions("/nonexistent/file.jpg"))
    }

    // ========== cropRoiBitmap ==========

    @Test
    fun `cropRoiBitmap returns null for nonexistent file`() {
        val rect = ContentRectBounds(0, 0, 100, 100)
        assertNull(RoiCoordinateMapper.cropRoiBitmap("/nonexistent/file.jpg", rect))
    }

    @Test
    fun `cropRoiBitmap returns null for zero-size rect`() {
        val rect = ContentRectBounds(50, 50, 50, 50)
        assertNull(RoiCoordinateMapper.cropRoiBitmap("/some/file.jpg", rect))
    }

    @Test
    fun `cropRoiBitmap returns null for negative-size rect`() {
        val rect = ContentRectBounds(100, 100, 50, 50)
        assertNull(RoiCoordinateMapper.cropRoiBitmap("/some/file.jpg", rect))
    }
}
