package com.wearable.inspection.mobile.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 跨候选字符级加权融合 JVM 单测：对齐、投票、置信度、3/5 混淆、孤证 */
class SteelStampCharFusionTest {

    private fun input(name: String, text: String) = LineFusionInput(name, text, SteelStampCharFusion.candidateWeight(name, OcrPolarity.POSITIVE))

    @Test
    fun `consensus candidates produce certain chars`() {
        val fused = SteelStampCharFusion.fuseLine(
            listOf(
                input("pos-clahe", "230447"),
                input("pos-gamma", "230447"),
                input("pos-adaptive", "230447"),
            ),
        )!!
        assertEquals("230447", fused.text)
        assertEquals(3, fused.sourceCount)
        assertTrue(fused.chars.all { !it.uncertain })
    }

    @Test
    fun `3 vs 5 split marks uncertain and keeps raw winner`() {
        val fused = SteelStampCharFusion.fuseLine(
            listOf(
                input("pos-clahe", "230447"),
                input("pos-gamma", "230447"),
                input("pos-adaptive", "230S47"),
            ),
        )!!
        val ch = fused.chars[3]
        assertEquals('4', ch.char)
        assertTrue(ch.uncertain)
        assertTrue(ch.confidence < 1.0f)
    }

    @Test
    fun `0 vs 5 split marks uncertain`() {
        val fused = SteelStampCharFusion.fuseLine(
            listOf(
                input("pos-clahe", "0050"),
                input("pos-gamma", "0O50"),
            ),
        )!!
        assertTrue(fused.chars[1].uncertain)
    }

    @Test
    fun `single candidate is lone evidence and fully uncertain`() {
        val fused = SteelStampCharFusion.fuseLine(listOf(input("pos-clahe", "ABC123")))!!
        assertEquals("ABC123", fused.text)
        assertTrue(fused.chars.all { it.uncertain })
        assertEquals(1, fused.sourceCount)
    }

    @Test
    fun `char outside charset is marked uncertain not replaced`() {
        val fused = SteelStampCharFusion.fuseLine(
            listOf(
                input("pos-clahe", "1O 2"),
                input("pos-gamma", "10 2"),
            ),
        )!!
        assertEquals('1', fused.chars[0].char)
        assertTrue(fused.chars[1].uncertain)
    }

    @Test
    fun `tokens aligned by index across candidates`() {
        val fused = SteelStampCharFusion.fuseLine(
            listOf(
                input("pos-clahe", "BMW 3332 6894228-02"),
                input("pos-gamma", "BMW 6894228-02"),
                input("pos-adaptive", "BMW 3332 6894228-02"),
            ),
        )!!
        assertTrue(fused.text.startsWith("BMW "))
        assertEquals("6894228-02", fused.text.split(' ').last())
    }

    @Test
    fun `empty or blank inputs return null`() {
        assertNull(SteelStampCharFusion.fuseLine(emptyList()))
        assertNull(SteelStampCharFusion.fuseLine(listOf(input("pos-clahe", "   "))))
    }

    @Test
    fun `lowercase input is normalized to uppercase`() {
        val fused = SteelStampCharFusion.fuseLine(
            listOf(input("pos-clahe", "bmw"), input("pos-gamma", "bmw")),
        )!!
        assertEquals("BMW", fused.text)
    }

    @Test
    fun `confidence scales with candidate weights`() {
        val fused = SteelStampCharFusion.fuseLine(
            listOf(
                input("pos-clahe", "ABC"),
                input("pos-gamma", "ABC"),
                input("pos-adaptive", "ABC"),
            ),
        )!!
        fused.chars.forEach { assertEquals(1.0f, it.confidence, 1e-3f) }
    }

    @Test
    fun `space boundary survives without shifting later characters`() {
        val inputs = buildList {
            add(input("pos-clahe", "230447 10 CN"))
            repeat(7) { add(input("pos-gamma-$it", "230447 10CN")) }
        }
        val fused = SteelStampCharFusion.fuseLine(inputs)!!
        assertEquals("230447 10 CN", fused.text)
        assertEquals(fused.text.length, fused.chars.size)
        assertEquals("10", fused.chars.subList(7, 9).joinToString("") { it.char.toString() })
        assertTrue(fused.chars.subList(0, 6).all { !it.uncertain })
    }

    @Test
    fun `single voter column is marked uncertain not confident`() {
        val fused = SteelStampCharFusion.fuseLine(
            listOf(
                input("pos-clahe", "BMW 3332"),
                input("pos-gamma", "BMW 3332"),
                input("pos-adaptive", "BMW 3332 X"),
            ),
        )!!
        assertEquals("BMW 3332 X", fused.text)
        assertTrue(fused.chars.filter { it.char == 'X' }.any { it.uncertain })
        assertTrue(fused.chars.filter { it.char == 'B' }.all { !it.uncertain })
    }
}
