package com.wearable.inspection.mobile.dpm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DpmDumpBudget 落盘配额纯逻辑测试（无 OpenCV/Android 依赖）。
 * 修复目标：Debug PNG dump 默认不写（0 配额），只有显式 request 后写，
 * 单进程累计硬上限约 30 组 —— 防 debug 包默认全量写盘膨胀缓存（历史 ~3.4GB）。
 */
class DpmDumpBudgetTest {

    /** 默认 0 配额：一次都不允许写 */
    @Test
    fun defaultGrantsNothing() {
        val budget = DpmDumpBudget()
        assertEquals(0, budget.grantedSets)
        assertFalse(budget.tryConsume())
        assertEquals(0, budget.usedSets)
    }

    /** 显式请求 1 组 → 恰好消费 1 组后耗尽 */
    @Test
    fun explicitRequestConsumesExactlyOnce() {
        val budget = DpmDumpBudget()
        budget.request(1)
        assertEquals(1, budget.grantedSets)
        assertTrue(budget.tryConsume())
        assertFalse("配额已耗尽", budget.tryConsume())
        assertEquals(1, budget.usedSets)
    }

    /** 多次请求累计授予，消费与授予匹配 */
    @Test
    fun repeatedRequestsAccumulate() {
        val budget = DpmDumpBudget()
        budget.request(2)
        budget.request(3)
        assertEquals(5, budget.grantedSets)
        var consumed = 0
        while (budget.tryConsume()) consumed++
        assertEquals(5, consumed)
        assertEquals(5, budget.usedSets)
    }

    /** 硬上限：请求超过 maxSets 被截断，消费最多 maxSets 组 */
    @Test
    fun cappedAtHardLimit() {
        val budget = DpmDumpBudget(maxSets = 10)
        budget.request(100)
        assertEquals(10, budget.grantedSets)
        var consumed = 0
        while (budget.tryConsume()) consumed++
        assertEquals(10, consumed)
    }

    /** 非正请求是 no-op（不减少也不增加） */
    @Test
    fun nonPositiveRequestIsNoOp() {
        val budget = DpmDumpBudget()
        budget.request(0)
        budget.request(-3)
        assertEquals(0, budget.grantedSets)
    }

    /** 自定义小上限生效 */
    @Test
    fun customMaxSetsRespected() {
        val budget = DpmDumpBudget(maxSets = 2)
        budget.request(5)
        assertTrue(budget.tryConsume())
        assertTrue(budget.tryConsume())
        assertFalse(budget.tryConsume())
    }
}
