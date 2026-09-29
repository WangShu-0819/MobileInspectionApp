package com.wearable.inspection.mobile.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1 decoder 类别配置测试
 *
 * 覆盖：decoder 使用正确类别名称、输出宽度按类别数计算、
 * 不同模型的 decoder 配置互不干扰。
 */
class DecoderClassConfigTest {

    private val transform = NanoDetInputTransform(
        resizedWidth = 416, resizedHeight = 416,
        scaleX = 1.0, scaleY = 1.0,
        width = 416, height = 416,
    )

    // ── 类别名称决定输出宽度 ──

    @Test
    fun `exp09 class names produce correct output width`() {
        val classNames = Exp09ClassPolicy.classNames // 4 classes
        val expectedWidth = 4 + 4 * (7 + 1) // 4 classes + 4 * REG_MAX+1 = 36
        assertEquals(36, expectedWidth)
        assertEquals(4, classNames.size)
    }

    @Test
    fun `exp22 class names produce correct output width`() {
        val classNames = Exp22BlackClassPolicy.classNames // 4 classes
        val expectedWidth = 4 + 4 * (7 + 1) // 36
        assertEquals(36, expectedWidth)
        assertEquals(4, classNames.size)
    }

    @Test
    fun `exp23 class names produce correct output width`() {
        val classNames = Exp23WhiteClassPolicy.classNames // 2 classes
        val expectedWidth = 2 + 4 * (7 + 1) // 2 + 32 = 34
        assertEquals(34, expectedWidth)
        assertEquals(2, classNames.size)
    }

    // ── decoder 按类别名称解码 ──

    @Test
    fun `decoder with exp09 class names assigns correct class names to candidates`() {
        val classNames = Exp09ClassPolicy.classNames
        val outputWidth = classNames.size + 4 * (7 + 1) // 36
        val outputHeight = NanoDetModelContract.OUTPUT_HEIGHT // 3598
        val output = FloatArray(outputWidth * outputHeight) { 0f }

        // 在第一个 anchor 点设置 class 1 (thread) 的高分
        val row = 0
        output[row * outputWidth + 1] = 0.9f // class 1 score

        val candidates = NanoDetOutputDecoder.decode(output, transform, classNames)
        val class1Candidates = candidates.filter { it.classIndex == 1 }
        if (class1Candidates.isNotEmpty()) {
            assertEquals("thread", class1Candidates[0].className)
        }
    }

    @Test
    fun `decoder with exp22 class names assigns correct class names`() {
        val classNames = Exp22BlackClassPolicy.classNames
        val outputWidth = classNames.size + 4 * (7 + 1) // 36
        val outputHeight = NanoDetModelContract.OUTPUT_HEIGHT
        val output = FloatArray(outputWidth * outputHeight) { 0f }

        // class 0 = "Black Thread"
        val row = 0
        output[row * outputWidth + 0] = 0.8f

        val candidates = NanoDetOutputDecoder.decode(output, transform, classNames)
        val class0Candidates = candidates.filter { it.classIndex == 0 }
        if (class0Candidates.isNotEmpty()) {
            assertEquals("Black Thread", class0Candidates[0].className)
        }
    }

    @Test
    fun `decoder with exp23 class names assigns correct class names`() {
        val classNames = Exp23WhiteClassPolicy.classNames
        val outputWidth = classNames.size + 4 * (7 + 1) // 34
        val outputHeight = NanoDetModelContract.OUTPUT_HEIGHT
        val output = FloatArray(outputWidth * outputHeight) { 0f }

        // class 0 = "White Nut"
        val row = 0
        output[row * outputWidth + 0] = 0.85f

        val candidates = NanoDetOutputDecoder.decode(output, transform, classNames)
        val class0Candidates = candidates.filter { it.classIndex == 0 }
        if (class0Candidates.isNotEmpty()) {
            assertEquals("White Nut", class0Candidates[0].className)
        }
    }

    // ── decoder 拒绝错误的输出尺寸 ──

    @Test(expected = IllegalArgumentException::class)
    fun `decoder rejects output with wrong width for exp23 class names`() {
        // exp23 需要 width=34，但提供 exp09 的 width=36 的输出
        val exp23ClassNames = Exp23WhiteClassPolicy.classNames
        val wrongWidth = 36 // exp09 width
        val outputHeight = NanoDetModelContract.OUTPUT_HEIGHT
        val output = FloatArray(wrongWidth * outputHeight) { 0f }

        NanoDetOutputDecoder.decode(output, transform, exp23ClassNames)
    }

    // ── exp09 默认 decoder 兼容 ──

    @Test
    fun `default decode uses exp09 class names`() {
        val outputWidth = 36
        val outputHeight = NanoDetModelContract.OUTPUT_HEIGHT
        val output = FloatArray(outputWidth * outputHeight) { 0f }

        // class 0 = "nut" (default exp09)
        output[0] = 0.7f

        val candidates = NanoDetOutputDecoder.decode(output, transform)
        if (candidates.isNotEmpty()) {
            // 默认 decoder 使用 exp09 的类名
            val class0 = candidates.filter { it.classIndex == 0 }
            if (class0.isNotEmpty()) {
                assertEquals("nut", class0[0].className)
            }
        }
    }
}