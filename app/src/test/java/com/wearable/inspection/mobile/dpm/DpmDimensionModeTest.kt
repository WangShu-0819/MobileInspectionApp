package com.wearable.inspection.mobile.dpm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DPM 网格尺寸模式纯逻辑验证（纯 JVM，无 OpenCV/Android 依赖）：
 * 1. 模式 → 尺寸映射：AUTO=[16,18,20]、固定=[自身]；
 * 2. SharedPreferences 原始值解析：非法/历史值安全回退 AUTO；
 * 3. 候选配额纯函数：多尺寸（AUTO 语义）每尺寸 8/4 条，单尺寸（固定）24/12 条；
 * 4. [capPerDimension]：单一 dimension 不能占满配额，固定模式不产生其他尺寸候选；
 * 5. [interleave]：按尺寸名次交错（各尺寸第 1 名 → 第 2 名 → ……），18×18 不独占顺序。
 */
class DpmDimensionModeTest {

    // ---------------------------------------------------------------- 尺寸映射

    @Test
    fun `AUTO maps to 16 18 20`() {
        assertTrue(DpmDimensionMode.AUTO.dimensions().contentEquals(intArrayOf(16, 18, 20)))
    }

    @Test
    fun `fixed modes map to their single dimension`() {
        assertTrue(DpmDimensionMode.DIM_16.dimensions().contentEquals(intArrayOf(16)))
        assertTrue(DpmDimensionMode.DIM_18.dimensions().contentEquals(intArrayOf(18)))
        assertTrue(DpmDimensionMode.DIM_20.dimensions().contentEquals(intArrayOf(20)))
    }

    // ---------------------------------------------------------------- 持久化值解析

    @Test
    fun `parse returns AUTO for null and invalid values`() {
        assertEquals(DpmDimensionMode.AUTO, DpmDimensionMode.parse(null))
        assertEquals(DpmDimensionMode.AUTO, DpmDimensionMode.parse(""))
        assertEquals(DpmDimensionMode.AUTO, DpmDimensionMode.parse("18"))
        assertEquals(DpmDimensionMode.AUTO, DpmDimensionMode.parse("DIM_32"))
        assertEquals(DpmDimensionMode.AUTO, DpmDimensionMode.parse("auto"))       // 大小写敏感，非法回退
        assertEquals(DpmDimensionMode.AUTO, DpmDimensionMode.parse("garbage"))
    }

    @Test
    fun `parse returns matching mode for valid names`() {
        assertEquals(DpmDimensionMode.AUTO, DpmDimensionMode.parse("AUTO"))
        assertEquals(DpmDimensionMode.DIM_16, DpmDimensionMode.parse("DIM_16"))
        assertEquals(DpmDimensionMode.DIM_18, DpmDimensionMode.parse("DIM_18"))
        assertEquals(DpmDimensionMode.DIM_20, DpmDimensionMode.parse("DIM_20"))
    }

    // ---------------------------------------------------------------- 候选配额纯函数

    @Test
    fun `quota functions follow AUTO vs fixed semantics`() {
        // AUTO：3 尺寸 → 普通网格每尺寸 8 条 / 旋转每尺寸 4 条
        assertEquals(8, gridQuotaPerDimension(3))
        assertEquals(4, rotatedQuotaPerDimension(3))
        // 固定：1 尺寸 → 普通网格 24 条 / 旋转 12 条
        assertEquals(24, gridQuotaPerDimension(1))
        assertEquals(12, rotatedQuotaPerDimension(1))
    }

    // ---------------------------------------------------------------- capPerDimension

    private class FakeCandidate(val dimension: Int, val name: String)

    @Test
    fun `capPerDimension prevents single dimension from filling quota`() {
        // 30 条 18×18 高分候选 + 各 2 条 16/20 低分候选（输入已按分数降序）
        val sorted = buildList {
            for (i in 0 until 30) add(FakeCandidate(18, "18-#$i"))
            for (i in 0 until 2) add(FakeCandidate(16, "16-#$i"))
            for (i in 0 until 2) add(FakeCandidate(20, "20-#$i"))
        }
        val groups = capPerDimension(sorted, { it.dimension }, perDimension = 8)
        // 18×18 被截到 8 条，16/20 各保留 2 条 —— 单一尺寸无法占满全部配额
        assertEquals(8, groups.first { it.all { c -> c.dimension == 18 } }.size)
        assertEquals(2, groups.first { it.all { c -> c.dimension == 16 } }.size)
        assertEquals(2, groups.first { it.all { c -> c.dimension == 20 } }.size)
        // 组内保持输入（分数降序）顺序：18 组第一条是 18-#0
        assertEquals("18-#0", groups.first { it.all { c -> c.dimension == 18 } }[0].name)
    }

    @Test
    fun `capPerDimension fixed mode keeps only that dimension with full quota`() {
        val sorted = List(40) { FakeCandidate(18, "18-#$it") }
        val groups = capPerDimension(sorted, { it.dimension }, perDimension = 24)
        // 固定模式：只产生该尺寸候选，配额放大到 24
        assertEquals(1, groups.size)
        assertEquals(24, groups[0].size)
        assertTrue(groups[0].all { it.dimension == 18 })
    }

    @Test
    fun `capPerDimension group order follows first appearance in score order`() {
        val sorted = listOf(
            FakeCandidate(18, "18-1"),
            FakeCandidate(16, "16-1"),
            FakeCandidate(18, "18-2"),
            FakeCandidate(20, "20-1"),
        )
        val groups = capPerDimension(sorted, { it.dimension }, perDimension = 8)
        // 18 的最高分排第一 → 18 是第一个分组；随后 16、20
        assertEquals(listOf(18, 16, 20), groups.map { it[0].dimension })
    }

    // ---------------------------------------------------------------- interleave

    @Test
    fun `interleave orders by rank across dimensions`() {
        val g16 = List(8) { FakeCandidate(16, "16-#$it") }
        val g18 = List(8) { FakeCandidate(18, "18-#$it") }
        val g20 = List(8) { FakeCandidate(20, "20-#$it") }
        val ordered = interleave(listOf(g16, g18, g20))
        assertEquals(24, ordered.size)
        // 各尺寸第 1 名 → 各尺寸第 2 名 → ……；第 i 位 = 分组 i%3 的第 i/3 名
        for (i in ordered.indices) {
            assertEquals("${intArrayOf(16, 18, 20)[i % 3]}-#${i / 3}", ordered[i].name)
        }
    }

    @Test
    fun `interleave fairness keeps 18 from dominating the order`() {
        val g16 = List(8) { FakeCandidate(16, "16-#$it") }
        val g18 = List(8) { FakeCandidate(18, "18-#$it") }
        val g20 = List(8) { FakeCandidate(20, "20-#$it") }
        val ordered = interleave(listOf(g16, g18, g20))
        // 前 3 位必须来自三个不同尺寸（交错公平）；任意连续 2 位不同尺寸
        assertEquals(3, ordered.take(3).map { it.dimension }.distinct().size)
        for (i in 0 until ordered.size - 1) {
            assertTrue(ordered[i].dimension != ordered[i + 1].dimension)
        }
    }

    @Test
    fun `interleave handles unequal group sizes`() {
        val g16 = List(8) { FakeCandidate(16, "16-#$it") }
        val g18 = List(2) { FakeCandidate(18, "18-#$it") }
        val g20 = List(5) { FakeCandidate(20, "20-#$it") }
        val ordered = interleave(listOf(g16, g18, g20))
        // 16-#0, 18-#0, 20-#0, 16-#1, 18-#1, 20-#1, 16-#2, 20-#2, 16-#3, 20-#3,
        // 16-#4, 20-#4, 16-#5, 16-#6, 16-#7（18 组耗尽后 16/20 继续交错）
        assertEquals(15, ordered.size)
        assertEquals("16-#0", ordered[0].name)
        assertEquals("18-#0", ordered[1].name)
        assertEquals("20-#0", ordered[2].name)
        assertEquals("16-#1", ordered[3].name)
        assertEquals("18-#1", ordered[4].name)
        assertEquals("20-#1", ordered[5].name)
        assertEquals("20-#4", ordered[11].name)
        assertEquals("16-#6", ordered[13].name)
        assertEquals("16-#7", ordered[14].name)
    }
}
