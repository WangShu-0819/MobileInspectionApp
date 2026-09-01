package com.wearable.inspection.mobile.dpm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * DpmFrameQualityGate 帧质量门控纯逻辑测试（无 OpenCV/Android 依赖）。
 * 门控只做保守拒绝（严重过曝/欠曝/无对比/极低纹理），指标与工件极性无关
 * （黑件/白件对称），低对比金属 DPM 不被误拒 —— 提交侧防止坏帧白白烧
 * 2500ms 重型预算。
 */
class DpmFrameQualityTest {

    private fun frame(w: Int, h: Int, fill: (Int, Int) -> Int): IntArray =
        IntArray(w * h) { i -> fill(i % w, i / w) }

    /** 严重过曝（大面积死白）：拒绝 */
    @Test
    fun severeOverexposureRejected() {
        val gray = frame(100, 100) { x, y -> if (x < 60) 255 else 160 }
        val q = DpmFrameQualityGate.check(gray, 100, 100)
        assertFalse("大面积过曝必须拒绝", q.passed)
        assertTrue(q.reason.contains("overexposure"))
        assertTrue(q.overexposedRatioPercent >= DpmFrameQualityGate.RATIO_REJECT_PERCENT)
    }

    /** 严重欠曝（大面积死黑）：拒绝 */
    @Test
    fun severeUnderexposureRejected() {
        val gray = frame(100, 100) { x, y -> if (x < 60) 0 else 140 }
        val q = DpmFrameQualityGate.check(gray, 100, 100)
        assertFalse("大面积欠曝必须拒绝", q.passed)
        assertTrue(q.reason.contains("underexposure"))
        assertTrue(q.underexposedRatioPercent >= DpmFrameQualityGate.RATIO_REJECT_PERCENT)
    }

    /** 动态范围过小（纯色面/白板/空桌面）：拒绝 */
    @Test
    fun flatNoContrastRejected() {
        val gray = frame(100, 100) { _, _ -> 150 }
        val q = DpmFrameQualityGate.check(gray, 100, 100)
        assertFalse("无对比纯色帧必须拒绝", q.passed)
        assertTrue(q.reason.contains("dynamic range"))
        assertTrue(q.p95 - q.p05 < DpmFrameQualityGate.MIN_DYNAMIC_RANGE)
    }

    /** 极低纹理/极度失焦（平滑渐变、几乎无高频）：拒绝 */
    @Test
    fun heavyBlurRejected() {
        // 纯水平慢渐变（每 8px 亮 1 级）：梯度均值 ≈0.125，远低于 1.5 门槛 ——
        // 模拟重度失焦/无纹理（对比：点阵码结构梯度 ≥3）。动态范围 ≈22 足够通过
        // 前几道检查，本帧由"低纹理"一档拒绝。
        val gray = frame(200, 200) { x, _ -> 120 + x / 8 }
        val q = DpmFrameQualityGate.check(gray, 200, 200)
        assertFalse("低纹理帧必须拒绝", q.passed)
        assertTrue(q.reason.contains("blurred"))
        assertTrue(q.meanAbsGradient < DpmFrameQualityGate.MIN_MEAN_ABS_GRADIENT)
    }

    /** 低对比金属 DPM（幅值 ~±16 + 高斯噪声 σ≈2）：不误拒 —— 工业码的典型对比度 */
    @Test
    fun lowContrastStructuredPasses() {
        val rnd = Random(42)
        val gray = frame(200, 200) { x, y ->
            val base = 142 + (if ((x / 12 + y / 12) % 2 == 0) 12 else -12)
            (base + (rnd.nextGaussian() * 2.0).toInt()).coerceIn(0, 255)
        }
        val q = DpmFrameQualityGate.check(gray, 200, 200)
        assertTrue("低对比结构帧必须通过：reason=${q.reason} metrics=${q.shortMetrics()}", q.passed)
    }

    /** 正常点阵码帧（明暗模块 + 渐变 + 噪声）：通过 */
    @Test
    fun normalStructuredPasses() {
        val rnd = Random(20260820)
        val gray = frame(200, 200) { x, y ->
            val base = 140 + x / 16 + (if ((x / 10 + y / 10) % 2 == 0) -80 else 40)
            (base + (rnd.nextGaussian() * 2.0).toInt()).coerceIn(0, 255)
        }
        val q = DpmFrameQualityGate.check(gray, 200, 200)
        assertTrue("正常结构帧必须通过：reason=${q.reason} metrics=${q.shortMetrics()}", q.passed)
    }

    /** 黑件（暗底亮码）与白件（亮底暗码）对称：同一帧的反相结果一致通过 */
    @Test
    fun polaritySymmetry() {
        val rnd = Random(7)
        val gray = frame(200, 200) { x, y ->
            val base = 120 + (if ((x / 10 + y / 10) % 2 == 0) 40 else -40)
            (base + (rnd.nextGaussian() * 2.0).toInt()).coerceIn(0, 255)
        }
        val inverted = IntArray(gray.size) { 255 - gray[it] }
        val q = DpmFrameQualityGate.check(gray, 200, 200)
        val qi = DpmFrameQualityGate.check(inverted, 200, 200)
        assertTrue(q.passed)
        assertTrue("极性对称：反相帧也必须通过", qi.passed)
        assertEquals(q.overexposedRatioPercent, qi.underexposedRatioPercent)
    }

    /** 输入长度与 w*h 不符：抛 IllegalArgumentException（调用方契约） */
    @Test(expected = IllegalArgumentException::class)
    fun sizeMismatchThrows() {
        DpmFrameQualityGate.check(IntArray(10), 4, 4)
    }
}
