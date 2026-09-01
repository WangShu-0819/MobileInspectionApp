package com.wearable.inspection.mobile.dpm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DpmScanControl 协作式截止纯逻辑测试（无 OpenCV/Android 依赖）。
 * 这是"grid task miss in 1669378ms"修复的核心结构：withTimeoutOrNull 无法打断
 * 同步 CPU/OpenCV 循环，ImportedDpmScanner 靠 shouldAbort 在各外层循环主动探测。
 */
class DpmScanControlTest {

    /** 已过 deadline：首次检查立即中止，原因 DEADLINE */
    @Test
    fun deadlineAlreadyPassedAbortsImmediately() {
        val control = DpmScanControl(deadlineNanos = 100L)
        assertTrue(control.shouldAbort(nowNanos = 100L))
        assertEquals(DpmAbortReason.DEADLINE, control.abortReason)
    }

    /** 未到 deadline：不中止，原因保持 NONE */
    @Test
    fun withinDeadlineDoesNotAbort() {
        val control = DpmScanControl(deadlineNanos = 200L)
        assertFalse(control.shouldAbort(nowNanos = 100L))
        assertEquals(DpmAbortReason.NONE, control.abortReason)
    }

    /** 取消探测：isCancelled 返回 true 即中止，原因 CANCELLED 并锁存 */
    @Test
    fun cancelLatchesCancelledReason() {
        var cancelled = false
        val control = DpmScanControl(
            deadlineNanos = Long.MAX_VALUE,
            isCancelled = { cancelled },
        )
        assertFalse(control.shouldAbort(nowNanos = 0L))
        cancelled = true
        assertTrue(control.shouldAbort(nowNanos = 0L))
        assertEquals(DpmAbortReason.CANCELLED, control.abortReason)
        // 锁存：取消标志复位后仍保持 CANCELLED（任务一旦取消即停止，不因
        // 扫码模式快速重开而复活 —— 旧任务永远不再继续）
        cancelled = false
        assertTrue(control.shouldAbort(nowNanos = 0L))
        assertEquals(DpmAbortReason.CANCELLED, control.abortReason)
    }

    /** 中止原因锁存：DEADLINE 先触发后，即使 deadline 放宽/取消复位也不回退 */
    @Test
    fun abortReasonIsLatchedOnceSet() {
        var cancelled = false
        val control = DpmScanControl(
            deadlineNanos = 100L,
            isCancelled = { cancelled },
        )
        assertTrue(control.shouldAbort(nowNanos = 100L))
        assertEquals(DpmAbortReason.DEADLINE, control.abortReason)
        cancelled = true
        assertTrue(control.shouldAbort(nowNanos = 50L))
        assertEquals(DpmAbortReason.DEADLINE, control.abortReason) // 不变 CANCELLED
    }

    /** 空安全扩展：null control（无预算批量验证/单测路径）恒不中止 */
    @Test
    fun nullControlNeverAborts() {
        assertFalse((null as DpmScanControl?).aborted())
    }
}
