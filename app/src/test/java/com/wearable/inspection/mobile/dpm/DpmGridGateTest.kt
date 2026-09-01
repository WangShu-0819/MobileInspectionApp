package com.wearable.inspection.mobile.dpm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DpmGridGate 会话门控纯逻辑测试（无 OpenCV/Android 依赖）。
 * 修复目标：普通非扫码画面绝不启动 ImportedDpmScanner 重型重建；
 * 进入扫码模式历史 miss 不生效；退出/重开后旧任务结果过期。
 */
class DpmGridGateTest {

    private val gate = DpmGridGate(missThreshold = 8, cooldownMs = 1500L)

    /** 未开扫码模式：无论 miss 多少都不可提交 */
    @Test
    fun inactiveAlwaysBlocks() {
        assertFalse(gate.scanModeActive)
        repeat(100) { gate.onMiss() }
        assertFalse(gate.canSubmit(nowMs = 10_000L, gridActive = false))
    }

    /** 进入扫码模式：miss 连击与冷却清零 —— 旧画面累积的 miss 不立即触发提交 */
    @Test
    fun enteringResetsHistory() {
        repeat(20) { gate.onMiss() }
        gate.markSubmitted(nowMs = 0L)
        gate.setScanModeActive(true)
        assertEquals(0, gate.missStreak)
        assertEquals(0L, gate.lastAttemptAt)
        // 重新累计 8 次 miss 后才能提交（冷却也从 0 开始：now=10ms 时冷却未过）
        assertFalse(gate.canSubmit(nowMs = 10L, gridActive = false))
        repeat(8) { gate.onMiss() }
        assertFalse("冷却从 0 开始，10ms 未过冷却", gate.canSubmit(nowMs = 10L, gridActive = false))
        assertTrue("miss 达门槛且冷却已过", gate.canSubmit(nowMs = 10_000L, gridActive = false))
    }

    /** canSubmit 条件：miss 达门槛 + 冷却已过 + 无进行中任务，缺一不可 */
    @Test
    fun canSubmitRequiresAllConditions() {
        gate.setScanModeActive(true)
        repeat(7) { gate.onMiss() }
        assertFalse("miss 未达门槛", gate.canSubmit(nowMs = 10_000L, gridActive = false))
        gate.onMiss()
        assertTrue("8 次 miss 且无冷却记录", gate.canSubmit(nowMs = 10_000L, gridActive = false))
        assertFalse("有进行中任务", gate.canSubmit(nowMs = 10_000L, gridActive = true))
        gate.markSubmitted(nowMs = 10_000L)
        assertFalse("冷却未过", gate.canSubmit(nowMs = 10_000L + 1499L, gridActive = false))
        assertTrue("冷却已过", gate.canSubmit(nowMs = 10_000L + 1500L, gridActive = false))
    }

    /** 命中清零 miss 连击 */
    @Test
    fun hitResetsStreak() {
        gate.setScanModeActive(true)
        repeat(8) { gate.onMiss() }
        gate.onHit()
        assertEquals(0, gate.missStreak)
        assertFalse(gate.canSubmit(nowMs = 0L, gridActive = false))
    }

    /** 退出扫码模式：立即不可提交，进行中任务结果过期 */
    @Test
    fun exitingInvalidatesSession() {
        gate.setScanModeActive(true)
        repeat(8) { gate.onMiss() }
        val genAtSubmit = gate.generation
        assertTrue(gate.belongsToCurrentSession(genAtSubmit))
        gate.setScanModeActive(false)
        assertFalse(gate.canSubmit(nowMs = 10_000L, gridActive = false))
        assertFalse("退出后旧代数结果过期", gate.belongsToCurrentSession(genAtSubmit))
    }

    /** 退出再重开：每次开关代数 +1（退出 +1、重开再 +1），旧代数结果即使扫码模式又开也不再回灌 */
    @Test
    fun reopenBumpsGenerationAndInvalidatesOldResults() {
        gate.setScanModeActive(true)
        repeat(8) { gate.onMiss() }
        val gen1 = gate.generation
        gate.setScanModeActive(false)
        gate.setScanModeActive(true)
        assertEquals("退出+重开各 +1", gen1 + 2, gate.generation)
        assertFalse("旧代数结果永久过期", gate.belongsToCurrentSession(gen1))
        assertTrue("新代数有效", gate.belongsToCurrentSession(gate.generation))
    }

    /** 幂等：重复设置同一状态不消耗代数、不清状态 */
    @Test
    fun idempotentToggle() {
        gate.setScanModeActive(true)
        repeat(8) { gate.onMiss() }
        val gen = gate.generation
        gate.setScanModeActive(true)
        assertEquals(gen, gate.generation)
        assertEquals(8, gate.missStreak)
    }
}
