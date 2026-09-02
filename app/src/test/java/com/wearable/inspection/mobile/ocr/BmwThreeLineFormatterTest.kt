package com.wearable.inspection.mobile.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BmwThreeLineFormatterTest {

    private fun line(text: String, extraVotes: Map<Int, Map<Char, Float>> = emptyMap()): SteelStampLine {
        val chars = text.mapIndexed { index, ch ->
            val votes = extraVotes[index] ?: mapOf(ch to 1f)
            CharEvidence(ch, 1f, votes, uncertain = false)
        }
        return SteelStampLine(text, chars, sourceCount = 3, yCenter = 0f)
    }

    @Test
    fun `schema formats separators and normalizes letter digit confusions`() {
        val formatted = BmwThreeLineFormatter.format(
            listOf(
                line("BMN S355 6894228 02", extraVotes = mapOf(2 to mapOf('N' to 1f, 'W' to 0.8f))),
                line("230447 I0 CH"),
                line("16924AA0050"),
            ),
        )

        assertEquals("BMW 5355 6894228-02", formatted[0].text)
        assertEquals("230447 10 CH", formatted[1].text)
        assertEquals("16924 AA 0050", formatted[2].text)
        // 规则改写仍保留不确定标记，不能仅因格式看起来合法就自动 EXACT。
        assertTrue(formatted[0].chars.any { it.uncertain })
        assertTrue(formatted[1].chars.any { it.uncertain })
    }

    @Test
    fun `same type ambiguities are not guessed`() {
        val formatted = BmwThreeLineFormatter.format(
            listOf(
                line("BMW 3552 6894228 02"),
                line("230447 10 CH"),
                line("16924AA0050"),
            ),
        )
        assertEquals("BMW 3552 6894228-02", formatted[0].text)
        assertEquals("230447 10 CH", formatted[1].text)
    }

    @Test
    fun `missing glyphs are not padded from schema`() {
        val formatted = BmwThreeLineFormatter.format(
            listOf(
                line("BMW 3552 6894228 02"),
                line("230447 I0 CH"),
                line("16924AA0"),
            ),
        )
        assertEquals("16924AA0", formatted[2].text)
    }

    @Test
    fun `part separator is restored even when only first line is detected`() {
        val formatted = BmwThreeLineFormatter.format(
            listOf(line("BMW 3552 6894228 02")),
        )
        assertEquals("BMW 3552 6894228-02", formatted.single().text)
    }
}
