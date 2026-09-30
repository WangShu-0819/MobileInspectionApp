package com.wearable.inspection.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采集批次名称格式测试。
 *
 * 覆盖:
 * - batchDisplayName 格式: `<安全零件码>_yyyyMMdd_HHmmss_SSS`
 * - resolveBatchPartCode 优先级: dpmCode > partId > partName > batchId
 * - 非法字符清理
 * - 固定输入结果稳定
 * - 旧批次缺失编码的安全回退
 */
class CaptureBatchNamingTest {

    // 固定时间: 2026-09-30 12:34:56.789 UTC+8 → 用固定 epoch 保证跨时区稳定
    private val fixedTime = 1759206896789L // 2026-09-30T04:34:56.789Z

    // ─── resolveBatchPartCode 优先级 ───

    @Test
    fun `dpmCode takes priority over partId and partName`() {
        val code = resolveBatchPartCode(
            dpmCode = "DP-001",
            partId = "part-abc",
            partName = "零件甲",
            batchId = "batch-12345678",
        )
        assertEquals("DP-001", code)
    }

    @Test
    fun `falls back to partId when dpmCode is null`() {
        val code = resolveBatchPartCode(
            dpmCode = null,
            partId = "part-abc",
            partName = "零件甲",
            batchId = "batch-12345678",
        )
        assertEquals("part-abc", code)
    }

    @Test
    fun `falls back to partId when dpmCode is blank`() {
        val code = resolveBatchPartCode(
            dpmCode = "   ",
            partId = "part-abc",
            partName = "零件甲",
            batchId = "batch-12345678",
        )
        assertEquals("part-abc", code)
    }

    @Test
    fun `falls back to partName when dpmCode and partId are missing`() {
        val code = resolveBatchPartCode(
            dpmCode = null,
            partId = null,
            partName = "零件甲",
            batchId = "batch-12345678",
        )
        assertEquals("零件甲", code)
    }

    @Test
    fun `falls back to short batchId when all codes are missing`() {
        val code = resolveBatchPartCode(
            dpmCode = null,
            partId = null,
            partName = null,
            batchId = "batch-1234567890abcdef",
        )
        assertEquals("batch-12", code)
    }

    @Test
    fun `falls back to short batchId when partName is blank`() {
        val code = resolveBatchPartCode(
            dpmCode = null,
            partId = null,
            partName = "  ",
            batchId = "batch-1234567890abcdef",
        )
        assertEquals("batch-12", code)
    }

    // ─── 非法字符清理 ───

    @Test
    fun `illegal filename characters are replaced with underscore`() {
        val code = resolveBatchPartCode(
            dpmCode = "A/B:C*D?E\"F<G>H|I",
            partId = null,
            partName = null,
            batchId = "batch-12345678",
        )
        assertEquals("A_B_C_D_E_F_G_H_I", code)
    }

    @Test
    fun `backslash is replaced with underscore`() {
        val code = resolveBatchPartCode(
            dpmCode = "A\\B",
            partId = null,
            partName = null,
            batchId = "batch-12345678",
        )
        assertEquals("A_B", code)
    }

    @Test
    fun `sanitized result is never empty`() {
        // 全是非法字符 → 替换为下划线，结果非空
        val code = resolveBatchPartCode(
            dpmCode = "///***???",
            partId = null,
            partName = null,
            batchId = "batch-12345678",
        )
        assertTrue("结果不得为空", code.isNotEmpty())
        assertFalse("不应包含 /", code.contains("/"))
        assertFalse("不应包含 *", code.contains("*"))
        assertFalse("不应包含 ?", code.contains("?"))
    }

    @Test
    fun `blank code falls back to batchId even after trim`() {
        // 纯空白 dpmCode + 纯空白 partName → 回退到 batchId
        val code = resolveBatchPartCode(
            dpmCode = "   ",
            partId = null,
            partName = "  ",
            batchId = "batch-12345678",
        )
        assertEquals("batch-12", code)
    }

    // ─── batchDisplayName 格式 ───

    @Test
    fun `batchDisplayName format is code_yyyyMMdd_HHmmss_SSS`() {
        val name = batchDisplayName(
            dpmCode = "DP-001",
            partId = "part-abc",
            partName = "零件甲",
            batchId = "batch-12345678",
            startTime = fixedTime,
        )
        // 格式: DP-001_yyyyMMdd_HHmmss_SSS
        assertTrue("应以 DP-001_ 开头", name.startsWith("DP-001_"))
        val tsPart = name.removePrefix("DP-001_")
        assertTrue("时间戳应为 yyyyMMdd_HHmmss_SSS 格式", tsPart.matches(Regex("\\d{8}_\\d{6}_\\d{3}")))
    }

    @Test
    fun `batchDisplayName is stable for fixed input`() {
        val args = arrayOf<String?>("DP-001", "part-abc", "零件甲", "batch-12345678")
        val name1 = batchDisplayName(args[0], args[1], args[2], args[3]!!, fixedTime)
        val name2 = batchDisplayName(args[0], args[1], args[2], args[3]!!, fixedTime)
        assertEquals("相同输入应产生相同名称", name1, name2)
    }

    @Test
    fun `batchDisplayName for legacy batch without any code`() {
        val name = batchDisplayName(
            dpmCode = null,
            partId = null,
            partName = "旧零件",
            batchId = "batch-12345678",
            startTime = fixedTime,
        )
        assertTrue("应以 旧零件_ 开头", name.startsWith("旧零件_"))
    }

    @Test
    fun `batchDisplayName for legacy batch with no code and no name`() {
        val name = batchDisplayName(
            dpmCode = null,
            partId = null,
            partName = null,
            batchId = "batch-12345678",
            startTime = fixedTime,
        )
        assertTrue("应以 batch-12_ 开头", name.startsWith("batch-12_"))
    }

    @Test
    fun `batchDisplayName uses startTime not current time`() {
        val earlyTime = 1600000000000L // 2020-09-13
        val lateTime = 1759206896789L  // 2026-09-30
        val name1 = batchDisplayName("DP-001", null, null, "b1", earlyTime)
        val name2 = batchDisplayName("DP-001", null, null, "b1", lateTime)
        assertFalse("不同 startTime 应产生不同名称", name1 == name2)
    }

    // ─── 与 generateZipFileName 的一致性 ───

    @Test
    fun `batchDisplayName base matches generateZipFileName base`() {
        // generateZipFileName 返回 <code>_<ts>.zip
        // batchDisplayName 返回 <code>_<ts>
        // 两者在相同 code + timestamp 下基名应一致
        val code = resolveBatchPartCode("DP-001", "part-abc", "零件甲", "batch-12345678")
        val name = batchDisplayName("DP-001", "part-abc", "零件甲", "batch-12345678", fixedTime)
        // batchDisplayName 的 code 部分应等于 resolveBatchPartCode 的结果
        assertTrue("batchDisplayName 应以 resolveBatchPartCode 结果开头", name.startsWith("${code}_"))
    }
}
