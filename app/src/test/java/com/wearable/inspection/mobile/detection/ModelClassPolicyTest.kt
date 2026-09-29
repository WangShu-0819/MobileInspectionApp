package com.wearable.inspection.mobile.detection

import com.wearable.inspection.mobile.data.entity.RoiTargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1 模型类别策略测试
 *
 * 覆盖：exp09/exp22/exp23 各自的类别映射、类别名称、支持类型。
 */
class ModelClassPolicyTest {

    // ── exp09 类别映射 ──

    @Test
    fun `exp09 NUT maps to 0`() {
        assertEquals(0, Exp09ClassPolicy.classIndex(RoiTargetType.NUT))
    }

    @Test
    fun `exp09 THREAD maps to 1`() {
        assertEquals(1, Exp09ClassPolicy.classIndex(RoiTargetType.THREAD))
    }

    @Test
    fun `exp09 BOLT maps to 2`() {
        assertEquals(2, Exp09ClassPolicy.classIndex(RoiTargetType.BOLT))
    }

    @Test
    fun `exp09 NUTSERT maps to 3`() {
        assertEquals(3, Exp09ClassPolicy.classIndex(RoiTargetType.NUTSERT))
    }

    @Test
    fun `exp09 FEATURE maps to null`() {
        assertNull(Exp09ClassPolicy.classIndex(RoiTargetType.FEATURE))
    }

    @Test
    fun `exp09 null maps to null`() {
        assertNull(Exp09ClassPolicy.classIndex(null))
    }

    @Test
    fun `exp09 class names`() {
        assertEquals("nut", Exp09ClassPolicy.className(0))
        assertEquals("thread", Exp09ClassPolicy.className(1))
        assertEquals("bolt", Exp09ClassPolicy.className(2))
        assertEquals("nutsert", Exp09ClassPolicy.className(3))
    }

    @Test
    fun `exp09 all target types supported except FEATURE`() {
        assertTrue(Exp09ClassPolicy.isTargetSupported(RoiTargetType.NUT))
        assertTrue(Exp09ClassPolicy.isTargetSupported(RoiTargetType.THREAD))
        assertTrue(Exp09ClassPolicy.isTargetSupported(RoiTargetType.BOLT))
        assertTrue(Exp09ClassPolicy.isTargetSupported(RoiTargetType.NUTSERT))
        assertFalse(Exp09ClassPolicy.isTargetSupported(RoiTargetType.FEATURE))
    }

    // ── exp22 黑件类别映射 ──

    @Test
    fun `exp22 THREAD maps to 0`() {
        assertEquals(0, Exp22BlackClassPolicy.classIndex(RoiTargetType.THREAD))
    }

    @Test
    fun `exp22 NUTSERT maps to 1`() {
        assertEquals(1, Exp22BlackClassPolicy.classIndex(RoiTargetType.NUTSERT))
    }

    @Test
    fun `exp22 NUT maps to 2`() {
        assertEquals(2, Exp22BlackClassPolicy.classIndex(RoiTargetType.NUT))
    }

    @Test
    fun `exp22 BOLT maps to 3`() {
        assertEquals(3, Exp22BlackClassPolicy.classIndex(RoiTargetType.BOLT))
    }

    @Test
    fun `exp22 FEATURE maps to null`() {
        assertNull(Exp22BlackClassPolicy.classIndex(RoiTargetType.FEATURE))
    }

    @Test
    fun `exp22 class names`() {
        assertEquals("Black Thread", Exp22BlackClassPolicy.className(0))
        assertEquals("Black Nutsert", Exp22BlackClassPolicy.className(1))
        assertEquals("Black Nut", Exp22BlackClassPolicy.className(2))
        assertEquals("Black Bolt", Exp22BlackClassPolicy.className(3))
    }

    @Test
    fun `exp22 all target types supported except FEATURE`() {
        assertTrue(Exp22BlackClassPolicy.isTargetSupported(RoiTargetType.NUT))
        assertTrue(Exp22BlackClassPolicy.isTargetSupported(RoiTargetType.THREAD))
        assertTrue(Exp22BlackClassPolicy.isTargetSupported(RoiTargetType.BOLT))
        assertTrue(Exp22BlackClassPolicy.isTargetSupported(RoiTargetType.NUTSERT))
        assertFalse(Exp22BlackClassPolicy.isTargetSupported(RoiTargetType.FEATURE))
    }

    // ── exp23 白件类别映射 ──

    @Test
    fun `exp23 NUT maps to 0`() {
        assertEquals(0, Exp23WhiteClassPolicy.classIndex(RoiTargetType.NUT))
    }

    @Test
    fun `exp23 THREAD maps to 1`() {
        assertEquals(1, Exp23WhiteClassPolicy.classIndex(RoiTargetType.THREAD))
    }

    @Test
    fun `exp23 BOLT maps to null (unsupported)`() {
        assertNull(Exp23WhiteClassPolicy.classIndex(RoiTargetType.BOLT))
    }

    @Test
    fun `exp23 NUTSERT maps to null (unsupported)`() {
        assertNull(Exp23WhiteClassPolicy.classIndex(RoiTargetType.NUTSERT))
    }

    @Test
    fun `exp23 FEATURE maps to null`() {
        assertNull(Exp23WhiteClassPolicy.classIndex(RoiTargetType.FEATURE))
    }

    @Test
    fun `exp23 class names`() {
        assertEquals("White Nut", Exp23WhiteClassPolicy.className(0))
        assertEquals("White Thread", Exp23WhiteClassPolicy.className(1))
    }

    @Test
    fun `exp23 only NUT and THREAD supported`() {
        assertTrue(Exp23WhiteClassPolicy.isTargetSupported(RoiTargetType.NUT))
        assertTrue(Exp23WhiteClassPolicy.isTargetSupported(RoiTargetType.THREAD))
        assertFalse(Exp23WhiteClassPolicy.isTargetSupported(RoiTargetType.BOLT))
        assertFalse(Exp23WhiteClassPolicy.isTargetSupported(RoiTargetType.NUTSERT))
        assertFalse(Exp23WhiteClassPolicy.isTargetSupported(RoiTargetType.FEATURE))
    }

    // ── 类别数组大小 ──

    @Test
    fun `exp09 has 4 classes`() {
        assertEquals(4, Exp09ClassPolicy.classNames.size)
    }

    @Test
    fun `exp22 has 4 classes`() {
        assertEquals(4, Exp22BlackClassPolicy.classNames.size)
    }

    @Test
    fun `exp23 has 2 classes`() {
        assertEquals(2, Exp23WhiteClassPolicy.classNames.size)
    }
}