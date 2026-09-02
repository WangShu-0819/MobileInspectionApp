package com.wearable.inspection.mobile.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可变行数钢印状态机 JVM 单测：版式/行数/状态流转 + 目录校验 + 零件号提取。
 */
class SteelStampResultTest {

    private fun line(text: String, uncertainAt: Set<Int> = emptySet()): SteelStampLine {
        val chars = text.mapIndexed { i, c ->
            CharEvidence(c, 1.0f, mapOf(c to 1.0f), uncertain = i in uncertainAt)
        }
        return SteelStampLine(text, chars, sourceCount = 3, yCenter = 0f)
    }

    private fun truthLines(): List<SteelStampLine> = listOf(
        line("BMW 3332 6894228-02"),
        line("230447 10 CN"),
        line("16924 AA 0050"),
    )

    private fun build(lines: List<SteelStampLine>, catalog: PartCatalogMatch? = null) =
        SteelStampResultMachine.build(lines, rawCandidates = emptyList(), catalog = catalog)

    // ---------- 版式与行数 ----------

    @Test
    fun `three truth lines match BMW schema and are EXACT`() {
        val r = build(truthLines())
        assertEquals(3, r.detectedLineCount)
        assertEquals(3, r.expectedLineCount)
        assertEquals(SteelStampLayout.THREE_LINE, r.layout)
        assertEquals("BMW_3L", r.matchedSchema?.name)
        assertEquals(OcrResultStatus.EXACT, r.status)
        assertEquals("6894228-02", r.partNumber)
    }

    @Test
    fun `single line stamp has ONE_LINE layout`() {
        val r = build(listOf(line("M968942")))
        assertEquals(SteelStampLayout.ONE_LINE, r.layout)
        assertEquals(1, r.detectedLineCount)
    }

    @Test
    fun `empty lines are FAILED`() {
        val r = build(emptyList())
        assertEquals(OcrResultStatus.FAILED, r.status)
        assertEquals(SteelStampLayout.UNKNOWN, r.layout)
    }

    @Test
    fun `line count beyond 3 is UNKNOWN layout and needs confirmation`() {
        val r = build(List(4) { line("AAA") })
        assertEquals(SteelStampLayout.UNKNOWN, r.layout)
        assertEquals(OcrResultStatus.NEED_CONFIRMATION, r.status)
    }

    // ---------- 不确定字符 ----------

    @Test
    fun `uncertain char forces NEED_CONFIRMATION`() {
        val lines = listOf(
            line("BMW 3332 6894228-02", uncertainAt = setOf(4)),
            line("230447 10 CN"),
            line("16924 AA 0050"),
        )
        val r = build(lines)
        assertEquals(OcrResultStatus.NEED_CONFIRMATION, r.status)
        assertEquals(listOf(0 to 4), r.uncertainPositions)
    }

    // ---------- 漏行判定 ----------

    @Test
    fun `BMW schema with missing third line needs confirmation`() {
        val lines = listOf(
            line("BMW 3332 6894228-02"),
            line("230447 10 CN"),
        )
        val r = build(lines)
        assertEquals(OcrResultStatus.NEED_CONFIRMATION, r.status)
        assertEquals(2, r.detectedLineCount)
        assertEquals(3, r.expectedLineCount)
    }

    @Test
    fun `no known schema and no catalog forces confirmation`() {
        val r = build(listOf(line("ABC 123")))
        assertEquals(OcrResultStatus.NEED_CONFIRMATION, r.status)
        assertNull(r.matchedSchema)
    }

    // ---------- 目录校验 ----------

    @Test
    fun `unique catalog match does not hide unknown layout`() {
        val base = build(listOf(line("ABC 123")))
        val merged = SteelStampResultMachine.applyCatalogValidation(
            base,
            PartCatalogMatch("123", listOf("某零件 12345")),
        )
        assertEquals(OcrResultStatus.NEED_CONFIRMATION, merged.status)
    }

    @Test
    fun `unique catalog match does not hide uncertain three line chars`() {
        val base = build(
            listOf(
                line("BMW 3332 6894228-02", uncertainAt = setOf(4)),
                line("230447 10 CN"),
                line("16924 AA 0050"),
            ),
        )
        val merged = SteelStampResultMachine.applyCatalogValidation(
            base,
            PartCatalogMatch("6894228-02", listOf("唯一零件")),
        )
        assertEquals(OcrResultStatus.NEED_CONFIRMATION, merged.status)
    }

    @Test
    fun `catalog conflict downgrades to NEED_CONFIRMATION`() {
        val base = build(truthLines())
        val merged = SteelStampResultMachine.applyCatalogValidation(
            base,
            PartCatalogMatch("6894228-02", listOf("零件A", "零件B")),
        )
        assertEquals(OcrResultStatus.NEED_CONFIRMATION, merged.status)
    }

    @Test
    fun `no catalog match keeps algorithm status`() {
        val base = build(truthLines())
        val merged = SteelStampResultMachine.applyCatalogValidation(
            base, PartCatalogMatch("6894228-02", emptyList()),
        )
        assertEquals(OcrResultStatus.EXACT, merged.status)
    }

    @Test
    fun `FAILED result is not upgraded by catalog`() {
        val base = build(emptyList())
        val merged = SteelStampResultMachine.applyCatalogValidation(
            base, PartCatalogMatch("x", listOf("某零件")),
        )
        assertEquals(OcrResultStatus.FAILED, merged.status)
    }

    // ---------- 零件号与字段 ----------

    @Test
    fun `part number extracted from BMW line`() {
        assertEquals("6894228-02", SteelStampResultMachine.extractPartNumber(listOf("BMW 3332 6894228-02")))
    }

    @Test
    fun `non-BMW line falls back to first stamp token`() {
        assertEquals("M968942", SteelStampResultMachine.extractPartNumber(listOf("M968942 230447")))
    }

    @Test
    fun `fields parsed from BMW three line schema`() {
        val fields = SteelStampResultMachine.parseFields(
            listOf("BMW 3332 6894228-02", "230447 10 CN", "16924 AA 0050"),
            SteelStampResultMachine.BMW_THREE_LINE,
        )
        assertEquals("BMW", fields["brand"])
        assertEquals("3332", fields["modelCode"])
        assertEquals("6894228-02", fields["partNo"])
        assertEquals("230447 10 CN", fields["batch"])
        assertEquals("16924 AA 0050", fields["dateCode"])
    }

    @Test
    fun `damaged BMW line does not extract part number from later batch line`() {
        assertNull(SteelStampResultMachine.extractPartNumber(listOf("BMW 3332 68942", "230447 10 CN")))
    }

    @Test
    fun `uncertain chars are never silently replaced`() {
        val line = SteelStampCharFusion.fuseLine(
            listOf(
                LineFusionInput("pos-clahe", "230447", 1.0f),
                LineFusionInput("pos-gamma", "230447", 0.95f),
                LineFusionInput("pos-adaptive", "230S47", 0.9f),
            ),
        )!!
        assertTrue(line.chars[3].uncertain || line.chars[3].char == '4')
    }
}
