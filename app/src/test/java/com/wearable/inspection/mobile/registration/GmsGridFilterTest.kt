package com.wearable.inspection.mobile.registration

import org.junit.Assert.*
import org.junit.Test

/**
 * GmsGridFilter 纯逻辑测试（无 OpenCV 依赖）。
 */
class GmsGridFilterTest {

    @Test
    fun `empty input returns empty array`() {
        val keep = GmsGridFilter.filter(emptyList(), emptyList(), 100, 100, 100, 100)
        assertEquals(0, keep.size)
    }

    @Test
    fun `mismatched sizes returns zero array`() {
        val src = listOf(GmsGridFilter.Point2D(10.0, 10.0))
        val dst = listOf(
            GmsGridFilter.Point2D(10.0, 10.0),
            GmsGridFilter.Point2D(20.0, 20.0),
        )
        val keep = GmsGridFilter.filter(src, dst, 100, 100, 100, 100)
        assertEquals(1, keep.size)
        assertFalse(keep[0])
    }

    @Test
    fun `identity matches with high support are kept`() {
        // 30 个匹配在同一区域，互相支持
        val src = (0 until 30).map { GmsGridFilter.Point2D(50.0 + it, 50.0 + it) }
        val dst = src.toList()
        val keep = GmsGridFilter.filter(src, dst, 100, 100, 100, 100)
        val kept = keep.count { it }
        assertTrue("should keep many matches, kept=$kept", kept > 20)
    }

    @Test
    fun `scattered matches with no support are rejected`() {
        // 20 个匹配分散在不同格子，无邻域支持
        val src = (0 until 20).map { GmsGridFilter.Point2D(it * 5.0, it * 5.0) }
        val dst = (0 until 20).map { GmsGridFilter.Point2D(90.0 - it * 4.0, 90.0 - it * 4.0) }
        val keep = GmsGridFilter.filter(src, dst, 100, 100, 100, 100)
        val kept = keep.count { it }
        assertTrue("should reject scattered matches, kept=$kept", kept < 10)
    }

    @Test
    fun `single match returns false`() {
        val src = listOf(GmsGridFilter.Point2D(50.0, 50.0))
        val dst = listOf(GmsGridFilter.Point2D(50.0, 50.0))
        val keep = GmsGridFilter.filter(src, dst, 100, 100, 100, 100)
        // 单个匹配 support-1=0 < threshold
        assertFalse(keep[0])
    }

    @Test
    fun `zero grid size returns empty`() {
        val src = listOf(GmsGridFilter.Point2D(50.0, 50.0))
        val dst = listOf(GmsGridFilter.Point2D(50.0, 50.0))
        val keep = GmsGridFilter.filter(src, dst, 100, 100, 100, 100, gridSize = 0)
        assertEquals(1, keep.size)
        assertFalse(keep[0])
    }

    @Test
    fun `consistency - same input produces same output`() {
        val src = (0 until 50).map { GmsGridFilter.Point2D(it * 2.0, it * 3.0) }
        val dst = (0 until 50).map { GmsGridFilter.Point2D(it * 2.0 + 5.0, it * 3.0 + 5.0) }
        val keep1 = GmsGridFilter.filter(src, dst, 100, 100, 100, 100)
        val keep2 = GmsGridFilter.filter(src, dst, 100, 100, 100, 100)
        assertArrayEquals(keep1, keep2)
    }
}
