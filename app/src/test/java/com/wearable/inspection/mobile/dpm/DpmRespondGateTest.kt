package com.wearable.inspection.mobile.dpm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DpmRespondGate 单测 —— 「防连扫」+「支持连续扫同一码」的响应门语义：
 * - 换码立即响应（产线 A 件扫完马上扫 B 件）；
 * - 同码持续在视野内 → 拦截（防连扫）；但超过 MAX_HOLD_RESPOND_MS 兜底放行；
 * - 同码离开视野 ≥ REARM_MISSES 帧后重新进入 → 立即响应（同一码反复扫）；
 * - 同码短暂离开（< REARM_MISSES）后回来 → 仍拦截；
 * - onResponded 更新同码基准、复位缺席计数。
 */
class DpmRespondGateTest {

    private var clock = 0L

    private fun newGate() = DpmRespondGate(now = { clock })

    @Test
    fun `new code responds immediately`() {
        val g = newGate()
        assertTrue(g.shouldRespond("DPM-A"))
        g.onResponded("DPM-A")
        assertEquals("DPM-A", g.lastCode)
    }

    @Test
    fun `same code held in view is blocked and released by hold timeout`() {
        val g = newGate()
        g.onResponded("DPM-A") // clock = 0
        // 码持续停在镜头里：每帧命中复位缺席计数 → 一直拦截
        repeat(30) {
            g.onHit()
            assertFalse("held-in-view same code must stay blocked", g.shouldRespond("DPM-A"))
        }
        // 未到兜底超时仍拦截
        clock = 4_999
        assertFalse(g.shouldRespond("DPM-A"))
        // 达到兜底超时放行（重开扫码页时码未移开也能扫到）
        clock = 5_000
        assertTrue(g.shouldRespond("DPM-A"))
    }

    @Test
    fun `same code re-entering after leaving view responds immediately`() {
        val g = newGate()
        g.onResponded("DPM-A")
        // 码离开视野 ≥ REARM_MISSES 帧后重新进入 → 无需等定时器。
        // 调用顺序与 DpmAnalyzer 一致：重新进入帧先响应门判断（读到未复位的
        // absentMisses），帧末才 onHit() 记录命中。
        repeat(DpmRespondGate.REARM_MISSES) { g.onMiss() }
        assertTrue(g.shouldRespond("DPM-A"))
    }

    @Test
    fun `same code with brief absence stays blocked`() {
        val g = newGate()
        g.onResponded("DPM-A")
        // 短暂离开（< REARM_MISSES）即回来：视为误判/未真正移开 → 仍拦截
        repeat(DpmRespondGate.REARM_MISSES - 1) { g.onMiss() }
        assertFalse(g.shouldRespond("DPM-A"))
    }

    @Test
    fun `different code responds immediately during same-code hold`() {
        val g = newGate()
        g.onResponded("DPM-A")
        repeat(10) { g.onHit() }
        assertTrue("换码必须立即响应", g.shouldRespond("DPM-B"))
    }

    @Test
    fun `onResponded updates code and resets absence`() {
        val g = newGate()
        g.onResponded("DPM-A")
        repeat(DpmRespondGate.REARM_MISSES) { g.onMiss() }
        assertEquals(DpmRespondGate.REARM_MISSES, g.absentMisses)
        g.onResponded("DPM-A")
        assertEquals("DPM-A", g.lastCode)
        assertEquals(0, g.absentMisses)
        // 响应后缺席计数已复位：码持续在视野内 → 拦截（防连扫）
        g.onHit()
        assertFalse(g.shouldRespond("DPM-A"))
    }

    @Test
    fun `absent misses accumulate only on misses`() {
        val g = newGate()
        repeat(3) { g.onMiss() }
        assertEquals(3, g.absentMisses)
        g.onHit()
        assertEquals(0, g.absentMisses)
        g.onMiss()
        assertEquals(1, g.absentMisses)
    }

    @Test
    fun `reset clears all state`() {
        val g = newGate()
        g.onResponded("DPM-A")
        repeat(5) { g.onMiss() }
        g.reset()
        assertEquals(null, g.lastCode)
        assertEquals(0L, g.lastRespondedAt)
        assertEquals(0, g.absentMisses)
        // After reset, any code is treated as new
        assertTrue(g.shouldRespond("DPM-A"))
    }

    @Test
    fun `rearmOnHold false blocks timeout rearm`() {
        val g = newGate()
        g.onResponded("DPM-A")
        // 码持续在视野内
        repeat(30) { g.onHit() }
        clock = 10_000 // 远超 MAX_HOLD_RESPOND_MS
        // rearmOnHold=false（巡检常驻模式）→ 超时也不放行
        assertFalse(g.shouldRespond("DPM-A", rearmOnHold = false))
        // rearmOnHold=true（扫码弹窗模式）→ 超时放行
        assertTrue(g.shouldRespond("DPM-A", rearmOnHold = true))
    }
}
