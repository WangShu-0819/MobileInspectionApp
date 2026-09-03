package com.wearable.inspection.mobile.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveInspectionOverlayTest {

    @Test
    fun parseNormalizedRect_acceptsValidRect() {
        assertEquals(
            NormalizedRect(0.1f, 0.2f, 0.8f, 0.9f),
            parseNormalizedRect("{\"left\":0.1,\"top\":0.2,\"right\":0.8,\"bottom\":0.9}"),
        )
    }

    @Test
    fun parseNormalizedRect_rejectsInvalidRectWithoutFallback() {
        assertNull(parseNormalizedRect("{\"left\":0.8,\"top\":0.2,\"right\":0.1,\"bottom\":0.9}"))
        assertNull(parseNormalizedRect("not-json"))
    }

    @Test
    fun mapNormalizedRectToContentRect_usesContentRectOriginAndSize() {
        val result = mapNormalizedRectToContentRect(
            rect = NormalizedRect(0.25f, 0.1f, 0.75f, 0.9f),
            contentRect = ContentRectBounds(100, 200, 900, 1200),
        )

        assertEquals(300f, result?.left)
        assertEquals(300f, result?.top)
        assertEquals(700f, result?.right)
        assertEquals(1100f, result?.bottom)
    }

    @Test
    fun mapNormalizedRectToContentRect_rejectsEmptyContentRect() {
        assertNull(
            mapNormalizedRectToContentRect(
                rect = NormalizedRect(0f, 0f, 1f, 1f),
                contentRect = ContentRectBounds(10, 10, 10, 100),
            )
        )
    }
}
