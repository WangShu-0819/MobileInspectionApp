package com.wearable.inspection.mobile.ui.screens

import org.junit.Assert.*
import org.junit.Test

/**
 * 零件创建校验测试
 *
 * 覆盖：空 ID、空名称、非法字符、重复 ID、合法输入。
 */
class PartCreationValidatorTest {

    @Test
    fun `empty ID returns error`() {
        val error = PartCreationValidator.validate("", "ValidName", idExists = false)
        assertEquals("请输入零件 ID", error)
    }

    @Test
    fun `blank ID returns error`() {
        val error = PartCreationValidator.validate("   ", "ValidName", idExists = false)
        assertEquals("请输入零件 ID", error)
    }

    @Test
    fun `empty name returns error`() {
        val error = PartCreationValidator.validate("valid_id", "", idExists = false)
        assertEquals("请输入零件名称", error)
    }

    @Test
    fun `blank name returns error`() {
        val error = PartCreationValidator.validate("valid_id", "   ", idExists = false)
        assertEquals("请输入零件名称", error)
    }

    @Test
    fun `ID with Chinese characters returns error`() {
        val error = PartCreationValidator.validate("零件01", "ValidName", idExists = false)
        assertEquals("零件 ID 仅支持字母、数字、下划线和连字符（1~64 位）", error)
    }

    @Test
    fun `ID with spaces returns error`() {
        val error = PartCreationValidator.validate("part 01", "ValidName", idExists = false)
        assertEquals("零件 ID 仅支持字母、数字、下划线和连字符（1~64 位）", error)
    }

    @Test
    fun `ID with special characters returns error`() {
        val error = PartCreationValidator.validate("part@01", "ValidName", idExists = false)
        assertEquals("零件 ID 仅支持字母、数字、下划线和连字符（1~64 位）", error)
    }

    @Test
    fun `duplicate ID returns error`() {
        val error = PartCreationValidator.validate("existing_id", "ValidName", idExists = true)
        assertEquals("该零件 ID 已存在", error)
    }

    @Test
    fun `valid ID with letters passes`() {
        val error = PartCreationValidator.validate("partABC", "ValidName", idExists = false)
        assertNull(error)
    }

    @Test
    fun `valid ID with numbers passes`() {
        val error = PartCreationValidator.validate("12345", "ValidName", idExists = false)
        assertNull(error)
    }

    @Test
    fun `valid ID with underscores passes`() {
        val error = PartCreationValidator.validate("part_001", "ValidName", idExists = false)
        assertNull(error)
    }

    @Test
    fun `valid ID with hyphens passes`() {
        val error = PartCreationValidator.validate("part-001", "ValidName", idExists = false)
        assertNull(error)
    }

    @Test
    fun `valid ID with mixed characters passes`() {
        val error = PartCreationValidator.validate("Part_001-ABC", "ValidName", idExists = false)
        assertNull(error)
    }

    @Test
    fun `valid ID at max length 64 passes`() {
        val id = "a".repeat(64)
        val error = PartCreationValidator.validate(id, "ValidName", idExists = false)
        assertNull(error)
    }

    @Test
    fun `ID exceeding 64 characters returns error`() {
        val id = "a".repeat(65)
        val error = PartCreationValidator.validate(id, "ValidName", idExists = false)
        assertEquals("零件 ID 仅支持字母、数字、下划线和连字符（1~64 位）", error)
    }

    @Test
    fun `single character ID passes`() {
        val error = PartCreationValidator.validate("A", "ValidName", idExists = false)
        assertNull(error)
    }

    @Test
    fun `name with any characters passes`() {
        // Name has no character restrictions (Chinese, spaces, etc. are allowed)
        val error = PartCreationValidator.validate("valid_id", "零件 01 测试", idExists = false)
        assertNull(error)
    }

    @Test
    fun `duplicate check takes priority over no error`() {
        // When all local checks pass but ID exists, return duplicate error
        val error = PartCreationValidator.validate("valid_id", "ValidName", idExists = true)
        assertEquals("该零件 ID 已存在", error)
    }
}
