package com.wearable.inspection.mobile.dpm

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * DpmResultGate 单元测试
 *
 * 覆盖：
 * 1. 首次识别通过
 * 2. 同码抑制
 * 3. 换码立即响应
 * 4. miss 后同码重新响应
 * 5. reset 后可再次响应
 * 6. stop 后不响应
 * 7. null 输入计 miss
 * 8. 连续 miss 累积
 */
class DpmResultGateTest {

    private lateinit var clock: FakeClock
    private lateinit var gate: DpmResultGate

    @Before
    fun setUp() {
        clock = FakeClock(1000L)
        gate = DpmResultGate(missThreshold = 3, clock = clock)
    }

    @Test
    fun `首次识别通过`() {
        val result = gate.submit(makeResult("CODE_A"))

        assertNotNull("首次识别应通过", result)
        assertEquals("CODE_A", result!!.rawValue)
        assertTrue("应已接受过结果", gate.hasAccepted())
    }

    @Test
    fun `同码抑制`() {
        gate.submit(makeResult("CODE_A"))
        val second = gate.submit(makeResult("CODE_A"))

        assertNull("同码应被抑制", second)
    }

    @Test
    fun `换码立即响应`() {
        gate.submit(makeResult("CODE_A"))
        val result = gate.submit(makeResult("CODE_B"))

        assertNotNull("换码应立即响应", result)
        assertEquals("CODE_B", result!!.rawValue)
    }

    @Test
    fun `miss 后同码重新响应`() {
        gate.submit(makeResult("CODE_A"))

        // 连续 miss 达到阈值（3 次）
        gate.submit(null) // miss 1
        gate.submit(null) // miss 2
        gate.submit(null) // miss 3

        // 同码应重新响应
        val result = gate.submit(makeResult("CODE_A"))

        assertNotNull("miss 达到阈值后同码应重新响应", result)
        assertEquals("CODE_A", result!!.rawValue)
    }

    @Test
    fun `miss 未达阈值时同码仍被抑制`() {
        gate.submit(makeResult("CODE_A"))

        gate.submit(null) // miss 1
        gate.submit(null) // miss 2
        // 未达到阈值 3

        val result = gate.submit(makeResult("CODE_A"))
        assertNull("miss 未达阈值时同码应被抑制", result)
    }

    @Test
    fun `reset 后可再次响应同码`() {
        gate.submit(makeResult("CODE_A"))
        gate.submit(makeResult("CODE_A")) // 被抑制

        gate.reset()

        val result = gate.submit(makeResult("CODE_A"))
        assertNotNull("reset 后同码应重新响应", result)
        assertEquals("CODE_A", result!!.rawValue)
    }

    @Test
    fun `stop 后不响应`() {
        gate.submit(makeResult("CODE_A"))

        gate.stop()

        val result = gate.submit(makeResult("CODE_B"))
        assertNull("stop 后应不响应", result)
    }

    @Test
    fun `stop 后 reset 恢复响应`() {
        gate.stop()
        gate.reset()

        val result = gate.submit(makeResult("CODE_A"))
        assertNotNull("stop + reset 后应恢复响应", result)
    }

    @Test
    fun `null 输入计 miss 且累积`() {
        gate.submit(makeResult("CODE_A"))

        gate.submit(null)
        assertEquals(1, gate.getConsecutiveMisses())

        gate.submit(null)
        assertEquals(2, gate.getConsecutiveMisses())

        gate.submit(null)
        assertEquals(3, gate.getConsecutiveMisses())
    }

    @Test
    fun `成功识别重置 miss 计数`() {
        gate.submit(makeResult("CODE_A"))

        gate.submit(null) // miss 1
        gate.submit(null) // miss 2
        assertEquals(2, gate.getConsecutiveMisses())

        // 新码通过后 miss 计数应重置
        gate.submit(makeResult("CODE_B"))
        assertEquals(0, gate.getConsecutiveMisses())
    }

    @Test
    fun `同码抑制时 miss 计数重置为 0`() {
        gate.submit(makeResult("CODE_A"))

        gate.submit(null) // miss 1
        gate.submit(null) // miss 2

        // 同码被抑制但 miss 计数应重置
        gate.submit(makeResult("CODE_A"))
        assertEquals("同码抑制时 miss 计数应重置", 0, gate.getConsecutiveMisses())
    }

    // ─── 辅助 ───

    private fun makeResult(value: String) = DpmScanResult(
        rawValue = value,
        format = BarcodeFormat.DATA_MATRIX,
        timestampMs = clock.currentTimeMs(),
        source = DecodeSource.ML_KIT
    )

    // ─── 测试替身 ───

    private class FakeClock(var timeMs: Long) : DpmClock {
        override fun currentTimeMs(): Long = timeMs
    }
}
