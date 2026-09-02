package com.wearable.inspection.mobile.ocr

import org.junit.Test

/**
 * 探针：用真机日志中的真实候选文本复现「融合 → BmwThreeLineFormatter」链路，
 * 确认格式化（BMW + 短横零件号）是否生效。非断言测试，输出到日志供阅读。
 */
class FormatterProbeTest {

    private fun input(name: String, text: String) = LineFusionInput(name, text, SteelStampCharFusion.candidateWeight(name, OcrPolarity.POSITIVE))

    @Test
    fun `fuse real device candidates then format`() {
        // 真机回放 file1 line1 的 4 个投票候选（pos-gamma 基底文本未知，用近似值）
        val fused = SteelStampCharFusion.fuseLine(
            listOf(
                input("pos-clahe", "BMN 355 6894228 02"),
                input("pos-clahe-row", "BNN S53) G894228 2"),
                input("inv-adaptive", "BMW"),
                input("pos-gamma", "BMN 355 6894228 02"),
            ),
        )!!
        println("FUSED line1 = [${fused.text}] chars=${fused.chars.size} len=${fused.text.length}")

        val line2 = SteelStampLine("23044/ IE CH", emptyList(), 9, 0.56f)
        val line3 = SteelStampLine("16924AA 0", emptyList(), 4, 0.76f)
        val formatted = BmwThreeLineFormatter.format(listOf(fused, line2, line3))
        formatted.forEach { println("FORMATTED = [${it.text}]") }

        // 关键断言：零件号短横应被插入（6894228 02 → 6894228-02）
        val first = formatted.first().text
        println("ASSERT dash present: ${first.contains("6894228-02")}")
        println("ASSERT BMW prefix: ${first.startsWith("BMW")}")
    }
}
