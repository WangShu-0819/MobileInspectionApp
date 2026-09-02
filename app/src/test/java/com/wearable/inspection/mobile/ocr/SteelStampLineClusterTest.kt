package com.wearable.inspection.mobile.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 几何行聚类 JVM 单测：Y-center / X-overlap / line-spacing 判据 */
class SteelStampLineClusterTest {

    private fun box(text: String, top: Float, bottom: Float, left: Float = 0.1f, right: Float = 0.9f) =
        OcrLineBox(text, left, top, right, bottom)

    @Test
    fun `three stamp rows cluster top-down`() {
        val rows = listOf(
            box("BMW 3332", 0.10f, 0.20f),
            box("230447 10 CN", 0.25f, 0.35f),
            box("16924 AA 0050", 0.40f, 0.50f),
        )
        val clusters = SteelStampLineCluster.cluster(rows)
        assertEquals(3, clusters.size)
        assertEquals(listOf("BMW 3332", "230447 10 CN", "16924 AA 0050"), clusters.map { it.first().text })
    }

    @Test
    fun `split fragments of same row merge into one cluster`() {
        val fragments = listOf(
            box("BMW 3332", 0.10f, 0.20f, left = 0.05f, right = 0.35f),
            box("6894228-02", 0.10f, 0.20f, left = 0.30f, right = 0.60f),
        )
        val clusters = SteelStampLineCluster.cluster(fragments)
        assertEquals(1, clusters.size)
        assertEquals(2, clusters[0].size)
        assertEquals(listOf("BMW 3332", "6894228-02"), clusters[0].map { it.text })
    }

    @Test
    fun `far apart fragments do not merge`() {
        val fragments = listOf(
            box("LEFT", 0.10f, 0.20f, left = 0.02f, right = 0.15f),
            box("RIGHT", 0.10f, 0.20f, left = 0.80f, right = 0.98f),
        )
        val clusters = SteelStampLineCluster.cluster(fragments)
        assertEquals(2, clusters.size)
    }

    @Test
    fun `closely spaced lines with small y gap still separate by spacing ratio`() {
        val rows = listOf(
            box("LINE A", 0.05f, 0.17f),
            box("LINE B", 0.27f, 0.39f),
        )
        assertEquals(2, SteelStampLineCluster.cluster(rows).size)
    }

    @Test
    fun `junk fragment at large y gap does not join row`() {
        val rows = listOf(
            box("REAL LINE", 0.10f, 0.20f),
            box("junk", 0.70f, 0.72f),
        )
        val clusters = SteelStampLineCluster.cluster(rows)
        assertEquals(2, clusters.size)
    }

    @Test
    fun `empty input yields empty clusters`() {
        assertTrue(SteelStampLineCluster.cluster(emptyList()).isEmpty())
    }

    @Test
    fun `nearestLine pairs by y center within gap`() {
        val lines = listOf(
            box("L1", 0.10f, 0.20f),
            box("L2", 0.25f, 0.35f),
        )
        assertEquals("L1", SteelStampLineCluster.nearestLine(lines, 0.15f)?.text)
        assertEquals(null, SteelStampLineCluster.nearestLine(lines, 0.80f))
    }
}
