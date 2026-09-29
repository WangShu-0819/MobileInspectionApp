package com.wearable.inspection.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T1 零件创建校验 V2 测试
 *
 * 覆盖：件色必选、基础 ID 长度边界、最终 ID 生成、重复检测。
 */
class PartCreationValidatorV2Test {

    // ── 基础 ID 长度边界 ──

    @Test
    fun `base ID length 1 passes`() {
        val error = PartCreationValidator.validateBaseId("A", PartCreationValidator.PartColor.WHITE, "name", false)
        assertNull(error)
    }

    @Test
    fun `base ID length 58 passes`() {
        val baseId = "a".repeat(58)
        val error = PartCreationValidator.validateBaseId(baseId, PartCreationValidator.PartColor.BLACK, "name", false)
        assertNull(error)
    }

    @Test
    fun `base ID length 59 rejected`() {
        val baseId = "a".repeat(59)
        val error = PartCreationValidator.validateBaseId(baseId, PartCreationValidator.PartColor.WHITE, "name", false)
        assertEquals("基础 ID 仅支持字母、数字、下划线和连字符（1~58 位）", error)
    }

    @Test
    fun `blank base ID rejected`() {
        val error = PartCreationValidator.validateBaseId("", PartCreationValidator.PartColor.WHITE, "name", false)
        assertEquals("请输入基础 ID", error)
    }

    @Test
    fun `whitespace-only base ID rejected`() {
        val error = PartCreationValidator.validateBaseId("   ", PartCreationValidator.PartColor.BLACK, "name", false)
        assertEquals("请输入基础 ID", error)
    }

    // ── 件色必选 ──

    @Test
    fun `no color selected rejected`() {
        val error = PartCreationValidator.validateBaseId("abc", null, "name", false)
        assertEquals("请选择件色（白件/黑件）", error)
    }

    @Test
    fun `white color selected passes`() {
        val error = PartCreationValidator.validateBaseId("abc", PartCreationValidator.PartColor.WHITE, "name", false)
        assertNull(error)
    }

    @Test
    fun `black color selected passes`() {
        val error = PartCreationValidator.validateBaseId("abc", PartCreationValidator.PartColor.BLACK, "name", false)
        assertNull(error)
    }

    // ── 最终 ID 生成 ──

    @Test
    fun `white final ID format`() {
        val finalId = PartCreationValidator.generateFinalId("part01", PartCreationValidator.PartColor.WHITE)
        assertEquals("White_part01", finalId)
    }

    @Test
    fun `black final ID format`() {
        val finalId = PartCreationValidator.generateFinalId("part01", PartCreationValidator.PartColor.BLACK)
        assertEquals("Black_part01", finalId)
    }

    // ── 最终 ID 长度边界 ──

    @Test
    fun `final ID exactly 64 chars passes`() {
        // White_ = 6 chars, baseId = 58 chars → total 64
        val baseId = "a".repeat(58)
        val error = PartCreationValidator.validateBaseId(baseId, PartCreationValidator.PartColor.WHITE, "name", false)
        assertNull(error)
    }

    @Test
    fun `final ID 65 chars rejected`() {
        // White_ = 6 chars, baseId = 59 chars → total 65 (but baseId 59 also fails regex)
        val baseId = "a".repeat(59)
        val error = PartCreationValidator.validateBaseId(baseId, PartCreationValidator.PartColor.WHITE, "name", false)
        assertEquals("基础 ID 仅支持字母、数字、下划线和连字符（1~58 位）", error)
    }

    @Test
    fun `black final ID exactly 64 chars passes`() {
        // Black_ = 6 chars, baseId = 58 chars → total 64
        val baseId = "b".repeat(58)
        val error = PartCreationValidator.validateBaseId(baseId, PartCreationValidator.PartColor.BLACK, "name", false)
        assertNull(error)
    }

    // ── 字符校验 ──

    @Test
    fun `base ID with invalid characters rejected`() {
        val error = PartCreationValidator.validateBaseId("part@01", PartCreationValidator.PartColor.WHITE, "name", false)
        assertEquals("基础 ID 仅支持字母、数字、下划线和连字符（1~58 位）", error)
    }

    @Test
    fun `base ID with spaces rejected`() {
        val error = PartCreationValidator.validateBaseId("part 01", PartCreationValidator.PartColor.BLACK, "name", false)
        assertEquals("基础 ID 仅支持字母、数字、下划线和连字符（1~58 位）", error)
    }

    @Test
    fun `base ID with Chinese characters rejected`() {
        val error = PartCreationValidator.validateBaseId("零件01", PartCreationValidator.PartColor.WHITE, "name", false)
        assertEquals("基础 ID 仅支持字母、数字、下划线和连字符（1~58 位）", error)
    }

    @Test
    fun `valid base ID with mixed characters passes`() {
        val error = PartCreationValidator.validateBaseId("Part_001-ABC", PartCreationValidator.PartColor.BLACK, "name", false)
        assertNull(error)
    }

    // ── 名称校验 ──

    @Test
    fun `empty name rejected`() {
        val error = PartCreationValidator.validateBaseId("abc", PartCreationValidator.PartColor.WHITE, "", false)
        assertEquals("请输入零件名称", error)
    }

    @Test
    fun `blank name rejected`() {
        val error = PartCreationValidator.validateBaseId("abc", PartCreationValidator.PartColor.BLACK, "   ", false)
        assertEquals("请输入零件名称", error)
    }

    // ── 重复检测 ──

    @Test
    fun `duplicate final ID rejected`() {
        val error = PartCreationValidator.validateBaseId("abc", PartCreationValidator.PartColor.WHITE, "name", true)
        assertEquals("该零件 ID 已存在", error)
    }

    @Test
    fun `same base ID different colors both valid`() {
        val error1 = PartCreationValidator.validateBaseId("abc", PartCreationValidator.PartColor.WHITE, "name", false)
        val error2 = PartCreationValidator.validateBaseId("abc", PartCreationValidator.PartColor.BLACK, "name", false)
        assertNull(error1)
        assertNull(error2)
    }

    @Test
    fun `same base ID same color duplicate detected`() {
        // White_abc already exists
        val error = PartCreationValidator.validateBaseId("abc", PartCreationValidator.PartColor.WHITE, "name", true)
        assertEquals("该零件 ID 已存在", error)
        // Black_abc does not exist
        val error2 = PartCreationValidator.validateBaseId("abc", PartCreationValidator.PartColor.BLACK, "name", false)
        assertNull(error2)
    }

    // ── 旧接口向后兼容 ──

    @Test
    fun `old validate interface still works`() {
        assertNull(PartCreationValidator.validate("valid_id", "name", false))
        assertEquals("请输入零件 ID", PartCreationValidator.validate("", "name", false))
        assertEquals("该零件 ID 已存在", PartCreationValidator.validate("dup", "name", true))
    }
}