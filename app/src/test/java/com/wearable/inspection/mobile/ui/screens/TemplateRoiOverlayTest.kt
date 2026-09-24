package com.wearable.inspection.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 模板照片 ROI 叠加坐标映射测试
 *
 * 覆盖 computeTemplateImageRect（Fit/Crop）和 mapRoiToTemplateOverlay。
 */
class TemplateRoiOverlayTest {

    private val EPSILON = 0.5f

    // ══════════════════════════════════════════
    // computeTemplateImageRect — Fit 模式
    // ══════════════════════════════════════════

    @Test
    fun `Fit 横图在横容器中填满宽度`() {
        val rect = computeTemplateImageRect(
            bitmapWidth = 2000, bitmapHeight = 1000,
            containerWidth = 1000f, containerHeight = 500f,
            fillImage = false,
        )
        assertNotNull(rect)
        assertEquals(0f, rect!!.left, EPSILON)
        assertEquals(0f, rect.top, EPSILON)
        assertEquals(1000f, rect.right, EPSILON)
        assertEquals(500f, rect.bottom, EPSILON)
    }

    @Test
    fun `Fit 竖图在横容器中居中留白`() {
        // 竖图 1000x2000，横容器 1000x500
        // scale = min(1000/1000, 500/2000) = 0.25
        // scaledW = 250, scaledH = 500
        // offsetX = (1000-250)/2 = 375, offsetY = 0
        val rect = computeTemplateImageRect(
            bitmapWidth = 1000, bitmapHeight = 2000,
            containerWidth = 1000f, containerHeight = 500f,
            fillImage = false,
        )
        assertNotNull(rect)
        assertEquals(375f, rect!!.left, EPSILON)
        assertEquals(0f, rect.top, EPSILON)
        assertEquals(625f, rect.right, EPSILON)
        assertEquals(500f, rect.bottom, EPSILON)
    }

    @Test
    fun `Fit 横图在竖容器中上下留白`() {
        // 横图 2000x1000，竖容器 500x1000
        // scale = min(500/2000, 1000/1000) = 0.25
        // scaledW = 500, scaledH = 250
        // offsetX = 0, offsetY = (1000-250)/2 = 375
        val rect = computeTemplateImageRect(
            bitmapWidth = 2000, bitmapHeight = 1000,
            containerWidth = 500f, containerHeight = 1000f,
            fillImage = false,
        )
        assertNotNull(rect)
        assertEquals(0f, rect!!.left, EPSILON)
        assertEquals(375f, rect.top, EPSILON)
        assertEquals(500f, rect.right, EPSILON)
        assertEquals(625f, rect.bottom, EPSILON)
    }

    @Test
    fun `Fit 正方形图在正方形容器中填满`() {
        val rect = computeTemplateImageRect(
            bitmapWidth = 500, bitmapHeight = 500,
            containerWidth = 800f, containerHeight = 800f,
            fillImage = false,
        )
        assertNotNull(rect)
        assertEquals(0f, rect!!.left, EPSILON)
        assertEquals(0f, rect.top, EPSILON)
        assertEquals(800f, rect.right, EPSILON)
        assertEquals(800f, rect.bottom, EPSILON)
    }

    // ══════════════════════════════════════════
    // computeTemplateImageRect — Crop 模式
    // ══════════════════════════════════════════

    @Test
    fun `Crop 横图在横容器中填满`() {
        val rect = computeTemplateImageRect(
            bitmapWidth = 2000, bitmapHeight = 1000,
            containerWidth = 1000f, containerHeight = 500f,
            fillImage = true,
        )
        assertNotNull(rect)
        assertEquals(0f, rect!!.left, EPSILON)
        assertEquals(0f, rect.top, EPSILON)
        assertEquals(1000f, rect.right, EPSILON)
        assertEquals(500f, rect.bottom, EPSILON)
    }

    @Test
    fun `Crop 竖图在横容器中填满宽度裁剪高度`() {
        // 竖图 1000x2000，横容器 1000x500
        // imageAspect = 0.5, containerAspect = 2.0
        // imageAspect < containerAspect → scale = 1000/1000 = 1.0
        // scaledW = 1000, scaledH = 2000
        // offsetX = 0, offsetY = (500-2000)/2 = -750
        val rect = computeTemplateImageRect(
            bitmapWidth = 1000, bitmapHeight = 2000,
            containerWidth = 1000f, containerHeight = 500f,
            fillImage = true,
        )
        assertNotNull(rect)
        assertEquals(0f, rect!!.left, EPSILON)
        assertEquals(-750f, rect.top, EPSILON)
        assertEquals(1000f, rect.right, EPSILON)
        assertEquals(1250f, rect.bottom, EPSILON)
    }

    @Test
    fun `Crop 横图在竖容器中填满高度裁剪宽度`() {
        // 横图 2000x1000，竖容器 500x1000
        // imageAspect = 2.0, containerAspect = 0.5
        // imageAspect > containerAspect → scale = 1000/1000 = 1.0
        // scaledW = 2000, scaledH = 1000
        // offsetX = (500-2000)/2 = -750, offsetY = 0
        val rect = computeTemplateImageRect(
            bitmapWidth = 2000, bitmapHeight = 1000,
            containerWidth = 500f, containerHeight = 1000f,
            fillImage = true,
        )
        assertNotNull(rect)
        assertEquals(-750f, rect!!.left, EPSILON)
        assertEquals(0f, rect.top, EPSILON)
        assertEquals(1250f, rect.right, EPSILON)
        assertEquals(1000f, rect.bottom, EPSILON)
    }

    // ══════════════════════════════════════════
    // computeTemplateImageRect — 边界条件
    // ══════════════════════════════════════════

    @Test
    fun `无效图片尺寸返回 null`() {
        assertNull(computeTemplateImageRect(0, 100, 500f, 500f, false))
        assertNull(computeTemplateImageRect(100, 0, 500f, 500f, false))
        assertNull(computeTemplateImageRect(-1, 100, 500f, 500f, false))
    }

    @Test
    fun `无效容器尺寸返回 null`() {
        assertNull(computeTemplateImageRect(100, 100, 0f, 500f, false))
        assertNull(computeTemplateImageRect(100, 100, 500f, 0f, false))
        assertNull(computeTemplateImageRect(100, 100, -1f, 500f, false))
    }

    // ══════════════════════════════════════════
    // mapRoiToTemplateOverlay
    // ══════════════════════════════════════════

    @Test
    fun `全图 ROI 映射到图片区域`() {
        val imageRect = OverlayRect(100f, 50f, 900f, 450f)
        val roiRect = NormalizedRect(0f, 0f, 1f, 1f)

        val result = mapRoiToTemplateOverlay(roiRect, imageRect)

        assertNotNull(result)
        assertEquals(100f, result!!.left, EPSILON)
        assertEquals(50f, result.top, EPSILON)
        assertEquals(900f, result.right, EPSILON)
        assertEquals(450f, result.bottom, EPSILON)
    }

    @Test
    fun `左上角四分之一 ROI 正确映射`() {
        val imageRect = OverlayRect(100f, 50f, 900f, 450f)
        val roiRect = NormalizedRect(0f, 0f, 0.5f, 0.5f)

        val result = mapRoiToTemplateOverlay(roiRect, imageRect)

        assertNotNull(result)
        assertEquals(100f, result!!.left, EPSILON)
        assertEquals(50f, result.top, EPSILON)
        assertEquals(500f, result.right, EPSILON)
        assertEquals(250f, result.bottom, EPSILON)
    }

    @Test
    fun `居中 ROI 正确映射`() {
        val imageRect = OverlayRect(0f, 0f, 1000f, 1000f)
        val roiRect = NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f)

        val result = mapRoiToTemplateOverlay(roiRect, imageRect)

        assertNotNull(result)
        assertEquals(250f, result!!.left, EPSILON)
        assertEquals(250f, result.top, EPSILON)
        assertEquals(750f, result.right, EPSILON)
        assertEquals(750f, result.bottom, EPSILON)
    }

    @Test
    fun `留白偏移正确传递`() {
        // 图片居中在容器中，左侧偏移 200
        val imageRect = OverlayRect(200f, 0f, 800f, 600f)
        val roiRect = NormalizedRect(0f, 0f, 0.5f, 1f)

        val result = mapRoiToTemplateOverlay(roiRect, imageRect)

        assertNotNull(result)
        assertEquals(200f, result!!.left, EPSILON)
        assertEquals(0f, result.top, EPSILON)
        assertEquals(500f, result.right, EPSILON)
        assertEquals(600f, result.bottom, EPSILON)
    }

    @Test
    fun `零尺寸图片区域返回 null`() {
        val imageRect = OverlayRect(100f, 100f, 100f, 200f) // width = 0
        val roiRect = NormalizedRect(0f, 0f, 1f, 1f)

        assertNull(mapRoiToTemplateOverlay(roiRect, imageRect))
    }

    // ══════════════════════════════════════════
    // 端到端：Fit 模式 ROI 映射
    // ══════════════════════════════════════════

    @Test
    fun `Fit 竖图居中后 ROI 左半正确映射`() {
        // 竖图 1000x2000 在横容器 1000x500 中
        // imageRect = (375, 0, 625, 500)
        val imageRect = computeTemplateImageRect(1000, 2000, 1000f, 500f, false)!!
        val roiRect = NormalizedRect(0f, 0f, 0.5f, 1f)

        val result = mapRoiToTemplateOverlay(roiRect, imageRect)

        assertNotNull(result)
        assertEquals(375f, result!!.left, EPSILON)
        assertEquals(0f, result.top, EPSILON)
        assertEquals(500f, result.right, EPSILON)
        assertEquals(500f, result.bottom, EPSILON)
    }

    @Test
    fun `Fit 横图上下留白后 ROI 中间区域正确映射`() {
        // 横图 2000x1000 在竖容器 500x1000 中
        // imageRect = (0, 375, 500, 625)
        val imageRect = computeTemplateImageRect(2000, 1000, 500f, 1000f, false)!!
        val roiRect = NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f)

        val result = mapRoiToTemplateOverlay(roiRect, imageRect)

        assertNotNull(result)
        assertEquals(125f, result!!.left, EPSILON)
        assertEquals(437.5f, result.top, EPSILON)
        assertEquals(375f, result.right, EPSILON)
        assertEquals(562.5f, result.bottom, EPSILON)
    }

    // ══════════════════════════════════════════
    // 端到端：Crop 模式 ROI 映射
    // ══════════════════════════════════════════

    @Test
    fun `Crop 裁剪后可见 ROI 仍在容器范围内`() {
        // 竖图 1000x2000 在横容器 1000x500 中
        // imageRect = (0, -750, 1000, 1250)
        val imageRect = computeTemplateImageRect(1000, 2000, 1000f, 500f, true)!!
        // ROI 在图片顶部 25%
        val roiRect = NormalizedRect(0.1f, 0f, 0.9f, 0.25f)

        val result = mapRoiToTemplateOverlay(roiRect, imageRect)

        assertNotNull(result)
        // 映射后 top = -750 + 0*2000 = -750（超出容器顶部）
        assertEquals(-750f, result!!.top, EPSILON)
        // 映射后 bottom = -750 + 0.25*2000 = -250（仍超出容器顶部）
        assertEquals(-250f, result.bottom, EPSILON)
    }

    @Test
    fun `Crop 裁剪后中间 ROI 完全在容器内`() {
        // 竖图 1000x2000 在横容器 1000x500 中
        // imageRect = (0, -750, 1000, 1250)
        val imageRect = computeTemplateImageRect(1000, 2000, 1000f, 500f, true)!!
        // ROI 在图片中间 50%
        val roiRect = NormalizedRect(0.1f, 0.375f, 0.9f, 0.625f)

        val result = mapRoiToTemplateOverlay(roiRect, imageRect)

        assertNotNull(result)
        // top = -750 + 0.375*2000 = 0
        assertEquals(0f, result!!.top, EPSILON)
        // bottom = -750 + 0.625*2000 = 500
        assertEquals(500f, result.bottom, EPSILON)
    }

    // ══════════════════════════════════════════
    // parseNormalizedRect 与 mapRoiToTemplateOverlay 联合
    // ══════════════════════════════════════════

    @Test
    fun `JSON 解析后映射到模板图片区域`() {
        val json = """{"left":0.1,"top":0.2,"right":0.9,"bottom":0.8}"""
        val roiRect = parseNormalizedRect(json)
        assertNotNull(roiRect)

        val imageRect = OverlayRect(100f, 50f, 500f, 350f)
        val result = mapRoiToTemplateOverlay(roiRect!!, imageRect)

        assertNotNull(result)
        assertEquals(100f + 0.1f * 400f, result!!.left, EPSILON)
        assertEquals(50f + 0.2f * 300f, result.top, EPSILON)
        assertEquals(100f + 0.9f * 400f, result.right, EPSILON)
        assertEquals(50f + 0.8f * 300f, result.bottom, EPSILON)
    }

    @Test
    fun `非法 JSON 不产生映射`() {
        val imageRect = OverlayRect(0f, 0f, 100f, 100f)
        assertNull(parseNormalizedRect("not json")?.let { mapRoiToTemplateOverlay(it, imageRect) })
        assertNull(parseNormalizedRect("{}")?.let { mapRoiToTemplateOverlay(it, imageRect) })
    }
}
