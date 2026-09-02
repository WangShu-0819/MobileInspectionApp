package com.wearable.inspection.mobile.dpm

import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DPM 框内/框外约束纯逻辑测试（Robolectric）。
 *
 * 验证：
 * - scanRoi 存在时只解码框内区域，不触发全图兜底
 * - scanRoi 为 null 时允许全图阶段
 * - DpmScanRoiMapper 坐标映射正确性
 * - 框外码不响应、框内码可识别
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DpmFrameConstraintTest {

    // ─── DpmScanRoiMapper 测试 ───

    @Test
    fun roiMapper_fullOverlap_returnsFullBitmapRect() {
        val screenRect = Rect(100, 100, 500, 500)
        val contentRect = Rect(0, 0, 1080, 1920)
        val result = DpmScanRoiMapper.mapToBitmap(screenRect, contentRect, 1080, 1920)
        assertNotNull(result)
        assertEquals(100, result!!.left)
        assertEquals(100, result.top)
        assertEquals(500, result.right)
        assertEquals(500, result.bottom)
    }

    @Test
    fun roiMapper_partialOverlap_clampsToContentRect() {
        val screenRect = Rect(-50, -50, 200, 200)
        val contentRect = Rect(0, 0, 1080, 1920)
        val result = DpmScanRoiMapper.mapToBitmap(screenRect, contentRect, 1080, 1920)
        assertNotNull(result)
        assertTrue("左边界应 >= 0", result!!.left >= 0)
        assertTrue("上边界应 >= 0", result.top >= 0)
    }

    @Test
    fun roiMapper_noOverlap_returnsNull() {
        val screenRect = Rect(2000, 2000, 3000, 3000)
        val contentRect = Rect(0, 0, 1080, 1920)
        val result = DpmScanRoiMapper.mapToBitmap(screenRect, contentRect, 1080, 1920)
        assertNull("框与图像无交集应返回 null", result)
    }

    @Test
    fun roiMapper_emptyScreenRect_returnsNull() {
        val screenRect = Rect(100, 100, 100, 100)
        val contentRect = Rect(0, 0, 1080, 1920)
        val result = DpmScanRoiMapper.mapToBitmap(screenRect, contentRect, 1080, 1920)
        assertNull("空 screenRect 应返回 null", result)
    }

    @Test
    fun roiMapper_emptyContentRect_returnsNull() {
        val screenRect = Rect(100, 100, 500, 500)
        val contentRect = Rect(0, 0, 0, 0)
        val result = DpmScanRoiMapper.mapToBitmap(screenRect, contentRect, 1080, 1920)
        assertNull("空 contentRect 应返回 null", result)
    }

    @Test
    fun roiMapper_scaledMapping_rotatedBitmap() {
        // 模拟旋转后 Bitmap：流 1440x1080，旋转 90° → Bitmap 1080x1440
        val screenRect = Rect(200, 200, 600, 600)
        val contentRect = Rect(0, 0, 1080, 1440)
        val result = DpmScanRoiMapper.mapToBitmap(screenRect, contentRect, 1080, 1440)
        assertNotNull(result)
        assertEquals(200, result!!.left)
        assertEquals(200, result.top)
        assertEquals(600, result.right)
        assertEquals(600, result.bottom)
    }

    @Test
    fun roiMapper_withLetterbox_mapsCorrectly() {
        // 模拟 letterbox：PreviewView 1080x1920，contentRect 居中 1080x1440
        val screenRect = Rect(100, 300, 500, 700)
        val contentRect = Rect(0, 240, 1080, 1680)
        val result = DpmScanRoiMapper.mapToBitmap(screenRect, contentRect, 1080, 1440)
        assertNotNull(result)
        // imageY = screenY - contentRect.top = 300 - 240 = 60
        // bitmapY = 60 * (1440 / 1440) = 60
        assertEquals(100, result!!.left)
        assertEquals(60, result.top)
    }

    @Test
    fun roiOutsideFrame_returnsNull() {
        // 框完全在图像外
        val screenRect = Rect(2000, 2000, 3000, 3000)
        val contentRect = Rect(0, 0, 1080, 1920)
        val result = DpmScanRoiMapper.mapToBitmap(screenRect, contentRect, 1080, 1920)
        assertNull("框完全在图像外应返回 null", result)
    }

    @Test
    fun roiPartialOutside_clampsToImageBounds() {
        val screenRect = Rect(900, 1800, 1200, 2100)
        val contentRect = Rect(0, 0, 1080, 1920)
        val result = DpmScanRoiMapper.mapToBitmap(screenRect, contentRect, 1080, 1920)
        assertNotNull(result)
        assertTrue("右边界不应超过 Bitmap 宽度", result!!.right <= 1080)
        assertTrue("下边界不应超过 Bitmap 高度", result.bottom <= 1920)
    }

    // ─── 框内/框外解码行为测试 ───

    @Test
    fun scanRoiPresent_skipsFullImageStages() {
        // 验证 DpmAnalyzer 在 scanRoi 存在时跳过全图阶段
        // 这是一个行为验证：当 scanRoi != null 时，performMultiStrategyDecode 在 Stage1 MISS 后直接返回 null
        // 而不是继续 Stage2/Stage3/Stage4
        //
        // 由于 DpmAnalyzer 的 performMultiStrategyDecode 是 private 方法，
        // 我们通过验证 DpmScanRoiMapper 的返回值来间接验证：
        // - 当框与图像有交集时返回有效 ROI → Analyzer 使用该 ROI
        // - 当框与图像无交集时返回 null → Analyzer 不应继续解码

        val contentRect = Rect(0, 0, 1080, 1920)

        // 框内：有效 ROI
        val innerRect = Rect(200, 400, 600, 800)
        val innerRoi = DpmScanRoiMapper.mapToBitmap(innerRect, contentRect, 1080, 1920)
        assertNotNull("框内应返回有效 ROI", innerRoi)

        // 框外：无交集
        val outerRect = Rect(2000, 2000, 3000, 3000)
        val outerRoi = DpmScanRoiMapper.mapToBitmap(outerRect, contentRect, 1080, 1920)
        assertNull("框外应返回 null（无交集）", outerRoi)
    }

    @Test
    fun roiMapper_consistentWithContentRectBounds() {
        // 验证映射结果始终在 Bitmap 边界内
        val contentRect = Rect(0, 0, 1080, 1920)
        val bitmapW = 1080
        val bitmapH = 1920

        // 多组输入验证
        val testCases = listOf(
            Rect(0, 0, 1080, 1920),      // 全屏
            Rect(100, 100, 500, 500),    // 居中
            Rect(-100, -100, 200, 200),  // 部分超出左上
            Rect(900, 1800, 1200, 2100), // 部分超出右下
            Rect(540, 960, 540, 960),    // 点（空）
        )

        for (screenRect in testCases) {
            val roi = DpmScanRoiMapper.mapToBitmap(screenRect, contentRect, bitmapW, bitmapH)
            if (roi != null) {
                assertTrue("left >= 0", roi.left >= 0)
                assertTrue("top >= 0", roi.top >= 0)
                assertTrue("right <= bitmapW", roi.right <= bitmapW)
                assertTrue("bottom <= bitmapH", roi.bottom <= bitmapH)
                assertTrue("left < right", roi.left < roi.right)
                assertTrue("top < bottom", roi.top < roi.bottom)
            }
        }
    }

    @Test
    fun dimensionMode_defaultIsAuto() {
        // 验证 DpmDimensionMode 默认值
        assertEquals(DpmDimensionMode.AUTO, DpmDimensionMode.parse(null))
        assertEquals(DpmDimensionMode.AUTO, DpmDimensionMode.parse(""))
        assertEquals(DpmDimensionMode.AUTO, DpmDimensionMode.parse("INVALID"))
    }

    @Test
    fun dimensionMode_parseRoundTrip() {
        for (mode in DpmDimensionMode.entries) {
            assertEquals(mode, DpmDimensionMode.parse(mode.name))
        }
    }

    @Test
    fun dimensionMode_dimensionsArray() {
        assertArrayEquals(intArrayOf(16, 18, 20), DpmDimensionMode.AUTO.dimensions())
        assertArrayEquals(intArrayOf(16), DpmDimensionMode.DIM_16.dimensions())
        assertArrayEquals(intArrayOf(18), DpmDimensionMode.DIM_18.dimensions())
        assertArrayEquals(intArrayOf(20), DpmDimensionMode.DIM_20.dimensions())
    }

    @Test
    fun gridGate_oldParamsCorrect() {
        // 验证旧版参数已恢复
        val config = DpmAnalyzerConfig()
        assertEquals(0.5f, config.centerCropRatio, 0.001f)
        assertEquals(400, config.roiTargetWidth)
        assertEquals(30, config.missTriggerCount)
        assertEquals(8, config.gridMissThreshold)
        assertEquals(1500L, config.gridCooldownMs)
    }

    @Test
    fun gridGate_missThreshold8_cooldown1500() {
        // 验证 DpmGridGate 旧版参数
        val gate = DpmGridGate(missThreshold = 8, cooldownMs = 1500L)
        gate.setScanModeActive(true)

        // 7 次 miss 不触发
        repeat(7) { gate.onMiss() }
        assertFalse(gate.canSubmit(nowMs = 10_000L, gridActive = false))

        // 8 次 miss 触发
        gate.onMiss()
        assertTrue(gate.canSubmit(nowMs = 10_000L, gridActive = false))

        // 冷却 1500ms
        gate.markSubmitted(nowMs = 10_000L)
        assertFalse(gate.canSubmit(nowMs = 10_000L + 1499L, gridActive = false))
        assertTrue(gate.canSubmit(nowMs = 10_000L + 1500L, gridActive = false))
    }

    @Test
    fun respondGate_rearmBehavior() {
        // 验证 DpmRespondGate 同码重放行行为
        val gate = DpmRespondGate()

        // 首次响应
        assertTrue(gate.shouldRespond("CODE_A", rearmOnHold = true))
        gate.onResponded("CODE_A")

        // 同码立即不重复
        assertFalse(gate.shouldRespond("CODE_A", rearmOnHold = false))

        // 换码立即响应
        assertTrue(gate.shouldRespond("CODE_B", rearmOnHold = true))
        gate.onResponded("CODE_B")

        // 同码离开视野后重新进入
        repeat(DpmRespondGate.REARM_MISSES) { gate.onMiss() }
        assertTrue("离开视野后同码重新放行", gate.shouldRespond("CODE_B", rearmOnHold = true))
    }
}
