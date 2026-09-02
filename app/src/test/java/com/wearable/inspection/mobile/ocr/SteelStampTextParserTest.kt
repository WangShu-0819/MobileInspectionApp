package com.wearable.inspection.mobile.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteelStampTextParserTest {
    @Test
    fun `extracts part number from first real photo OCR output`() {
        assertEquals(
            "6894228-02",
            extractSteelStamp("BMR 3552 6894228 02\n23044/ i0 CH\n16924AA00"),
        )
    }

    @Test
    fun `damaged BMW line does not select a later batch code`() {
        assertNull(extractSteelStamp("BNN 355) G894228 02\n230447 10 CH\n16924AA000"))
    }

    @Test
    fun `extracts part number from second real photo OCR output`() {
        assertEquals(
            "6894228-02",
            extractSteelStamp("BMR 3552 6894228 02\n16924AA000"),
        )
    }
}
