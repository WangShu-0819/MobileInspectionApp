package com.wearable.inspection.mobile.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钢印预处理纯函数 JVM 单测（自适应 CLAHE 参数；OpenCV Mat 操作不在 JVM 单测范围）。
 */
class OcrPreProcessorTest {

    // ---------- adaptiveClipLimit（灰度 σ → CLAHE 对比度限制，连续线性自适应） ----------

    @Test
    fun `clip limit lowers as contrast rises`() {
        // σ 低（金属凹槽阴影淡化）→ 最强局部均衡（规格上限 4.5）
        assertEquals(4.5, OcrPreProcessor.adaptiveClipLimit(0.0), 1e-6)
        assertEquals(4.5, OcrPreProcessor.adaptiveClipLimit(10.0), 1e-6)
        // σ=40（对比正常）→ 双级增强档位下限 3.5
        assertEquals(3.5, OcrPreProcessor.adaptiveClipLimit(40.0), 1e-6)
        // 高对比 → 保持下限 3.5（防金属纹理被过度放大）
        assertEquals(3.5, OcrPreProcessor.adaptiveClipLimit(80.0), 1e-6)
    }

    @Test
    fun `clip limit is continuous without branches`() {
        // 连续线性：σ 在 10..40 区间内单调不增、无跳变
        var prev = OcrPreProcessor.adaptiveClipLimit(10.0)
        for (sigma in 10..40) {
            val cur = OcrPreProcessor.adaptiveClipLimit(sigma.toDouble())
            assertTrue("σ=$sigma 应单调不增", cur <= prev + 1e-9)
            prev = cur
        }
        // 任意输入都钳制在 [3.5, 4.5]
        for (sigma in listOf(-10.0, 0.0, 25.0, 55.0, 200.0)) {
            val v = OcrPreProcessor.adaptiveClipLimit(sigma)
            assertTrue(v in 3.5..4.5)
        }
    }

    // ---------- fillRatioLimitFor（反极性填充率容差放宽） ----------

    @Test
    fun `fill ratio limit is looser for inverted polarity`() {
        // 正极性凹字笔画较粗 → 95%；反极性凸字笔画细、Otsu 后白像素天然偏高 → 98%
        assertEquals(0.95f, OcrPreProcessor.fillRatioLimitFor(OcrPolarity.POSITIVE), 1e-6f)
        assertEquals(0.98f, OcrPreProcessor.fillRatioLimitFor(OcrPolarity.INVERTED), 1e-6f)
    }

    @Test
    fun `inverted fill ratio 95pct passes while positive rejected`() {
        // 真机实测：反极性 Otsu 二值后 fill=95.23% —— 反极性放行、正极性丢弃
        val fill = 0.9523f
        assertTrue(fill <= OcrPreProcessor.fillRatioLimitFor(OcrPolarity.INVERTED))
        assertTrue(fill > OcrPreProcessor.fillRatioLimitFor(OcrPolarity.POSITIVE))
        // 极端空白（99.9%）两极性都拒绝；正常笔画密度（70%）两极性都放行
        for (polarity in OcrPolarity.entries) {
            assertTrue(0.999f > OcrPreProcessor.fillRatioLimitFor(polarity))
            assertTrue(0.70f <= OcrPreProcessor.fillRatioLimitFor(polarity))
        }
    }
}
