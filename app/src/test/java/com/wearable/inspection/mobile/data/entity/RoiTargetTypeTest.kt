package com.wearable.inspection.mobile.data.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RoiTargetType 枚举测试
 *
 * 覆盖：枚举值、displayName、fromName 解析、边界值。
 */
class RoiTargetTypeTest {

    @Test
    fun `枚举值数量正确`() {
        assertEquals(3, RoiTargetType.entries.size)
    }

    @Test
    fun `THREAD 枚举值`() {
        val type = RoiTargetType.THREAD
        assertEquals("THREAD", type.name)
        assertEquals("螺纹", type.displayName)
    }

    @Test
    fun `NUT 枚举值`() {
        val type = RoiTargetType.NUT
        assertEquals("NUT", type.name)
        assertEquals("螺母", type.displayName)
    }

    @Test
    fun `FEATURE 枚举值`() {
        val type = RoiTargetType.FEATURE
        assertEquals("FEATURE", type.name)
        assertEquals("部件", type.displayName)
    }

    @Test
    fun `fromName 解析有效值`() {
        assertEquals(RoiTargetType.THREAD, RoiTargetType.fromName("THREAD"))
        assertEquals(RoiTargetType.NUT, RoiTargetType.fromName("NUT"))
        assertEquals(RoiTargetType.FEATURE, RoiTargetType.fromName("FEATURE"))
    }

    @Test
    fun `fromName 解析 null 返回 null`() {
        assertNull(RoiTargetType.fromName(null))
    }

    @Test
    fun `fromName 解析无效值返回 null`() {
        assertNull(RoiTargetType.fromName("INVALID"))
        assertNull(RoiTargetType.fromName(""))
        assertNull(RoiTargetType.fromName("thread")) // 大小写敏感
        assertNull(RoiTargetType.fromName("Thread")) // 大小写敏感
        assertNull(RoiTargetType.fromName(" VISUAL ")) // 不同的类型
    }

    @Test
    fun `displayName 不为空`() {
        RoiTargetType.entries.forEach { type ->
            assertNotNull(type.displayName)
            assert(type.displayName.isNotEmpty())
        }
    }

    @Test
    fun `name 与 displayName 一一对应`() {
        val nameToDisplayName = mapOf(
            "THREAD" to "螺纹",
            "NUT" to "螺母",
            "FEATURE" to "部件"
        )
        RoiTargetType.entries.forEach { type ->
            assertEquals(nameToDisplayName[type.name], type.displayName)
        }
    }

    @Test
    fun `fromName 往返一致`() {
        RoiTargetType.entries.forEach { type ->
            assertEquals(type, RoiTargetType.fromName(type.name))
        }
    }
}
