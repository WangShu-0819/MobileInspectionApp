package com.wearable.inspection.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NormalizedRect 和 ROI 编辑逻辑测试
 *
 * 覆盖：序列化/反序列化、move、resize、边界约束、最小尺寸、四角索引、
 * 多 ROI 独立性、move+resize 组合操作。
 *
 * ViewModel 持久化（moveRoi/resizeRoi → InspectionRepository.updateRoi）
 * 通过编译、assembleDebug 和 instrumented 测试验证。
 */
class RoiEditorViewModelTest {

    // ══════════════════════════════════════════
    // NormalizedRect JSON 序列化
    // ══════════════════════════════════════════

    @Test
    fun `NormalizedRect 序列化为 JSON 字符串`() {
        val rect = NormalizedRect(left = 0.1f, top = 0.2f, right = 0.9f, bottom = 0.8f)
        val json = rect.toJsonString()
        assertTrue(json.contains("\"left\""))
        assertTrue(json.contains("\"top\""))
        assertTrue(json.contains("\"right\""))
        assertTrue(json.contains("\"bottom\""))
        val restored = NormalizedRect.fromJsonString(json)
        assertNotNull(restored)
        assertEquals(0.1f, restored!!.left, 0.001f)
        assertEquals(0.2f, restored.top, 0.001f)
        assertEquals(0.9f, restored.right, 0.001f)
        assertEquals(0.8f, restored.bottom, 0.001f)
    }

    @Test
    fun `NormalizedRect 从 JSON 字符串解析`() {
        val json = """{"left":0.1,"top":0.2,"right":0.9,"bottom":0.8}"""
        val rect = NormalizedRect.fromJsonString(json)
        assertNotNull(rect)
        assertEquals(0.1f, rect!!.left, 0.001f)
        assertEquals(0.2f, rect.top, 0.001f)
        assertEquals(0.9f, rect.right, 0.001f)
        assertEquals(0.8f, rect.bottom, 0.001f)
    }

    @Test
    fun `NormalizedRect 无效 JSON 返回 null`() {
        assertNull(NormalizedRect.fromJsonString("not json"))
    }

    @Test
    fun `NormalizedRect 缺少字段返回 null`() {
        assertNull(NormalizedRect.fromJsonString("""{"left":0.1}"""))
    }

    @Test
    fun `NormalizedRect 往返序列化保持精度`() {
        val original = NormalizedRect(left = 0.123456f, top = 0.789012f, right = 0.345678f, bottom = 0.901234f)
        val restored = NormalizedRect.fromJsonString(original.toJsonString())
        assertNotNull(restored)
        assertEquals(original.left, restored!!.left, 0.001f)
        assertEquals(original.top, restored.top, 0.001f)
        assertEquals(original.right, restored.right, 0.001f)
        assertEquals(original.bottom, restored.bottom, 0.001f)
    }

    @Test
    fun `NormalizedRect 全图范围往返`() {
        val rect = NormalizedRect(left = 0f, top = 0f, right = 1f, bottom = 1f)
        val restored = NormalizedRect.fromJsonString(rect.toJsonString())
        assertNotNull(restored)
        assertEquals(0f, restored!!.left, 0.001f)
        assertEquals(0f, restored.top, 0.001f)
        assertEquals(1f, restored.right, 0.001f)
        assertEquals(1f, restored.bottom, 0.001f)
    }

    @Test
    fun `NormalizedRect 零面积矩形往返`() {
        val rect = NormalizedRect(left = 0.5f, top = 0.5f, right = 0.5f, bottom = 0.5f)
        val restored = NormalizedRect.fromJsonString(rect.toJsonString())
        assertNotNull(restored)
        assertEquals(0.5f, restored!!.left, 0.001f)
        assertEquals(0.5f, restored.right, 0.001f)
    }

    // ══════════════════════════════════════════
    // NormalizedRect.move 测试
    // ══════════════════════════════════════════

    @Test
    fun `move 正常偏移`() {
        val rect = NormalizedRect(0.2f, 0.2f, 0.6f, 0.6f)
        val moved = rect.move(0.1f, 0.1f)
        assertEquals(0.3f, moved.left, 0.001f)
        assertEquals(0.3f, moved.top, 0.001f)
        assertEquals(0.7f, moved.right, 0.001f)
        assertEquals(0.7f, moved.bottom, 0.001f)
    }

    @Test
    fun `move 保持宽高不变`() {
        val rect = NormalizedRect(0.2f, 0.3f, 0.5f, 0.7f)
        val moved = rect.move(0.1f, 0.05f)
        assertEquals(rect.right - rect.left, moved.right - moved.left, 0.001f)
        assertEquals(rect.bottom - rect.top, moved.bottom - moved.top, 0.001f)
    }

    @Test
    fun `move 负方向偏移`() {
        val rect = NormalizedRect(0.3f, 0.3f, 0.7f, 0.7f)
        val moved = rect.move(-0.1f, -0.1f)
        assertEquals(0.2f, moved.left, 0.001f)
        assertEquals(0.2f, moved.top, 0.001f)
    }

    @Test
    fun `move 约束在左边界`() {
        val rect = NormalizedRect(0.1f, 0.2f, 0.5f, 0.6f)
        val moved = rect.move(-0.5f, 0f)
        assertEquals(0f, moved.left, 0.001f)
        assertEquals(0.4f, moved.right, 0.001f)
    }

    @Test
    fun `move 约束在上边界`() {
        val rect = NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f)
        val moved = rect.move(0f, -0.5f)
        assertEquals(0f, moved.top, 0.001f)
        assertEquals(0.4f, moved.bottom, 0.001f)
    }

    @Test
    fun `move 约束在右边界`() {
        val rect = NormalizedRect(0.6f, 0.2f, 0.9f, 0.6f)
        val moved = rect.move(0.5f, 0f)
        assertEquals(0.7f, moved.left, 0.001f)
        assertEquals(1f, moved.right, 0.001f)
    }

    @Test
    fun `move 约束在下边界`() {
        val rect = NormalizedRect(0.2f, 0.6f, 0.6f, 0.9f)
        val moved = rect.move(0f, 0.5f)
        assertEquals(0.7f, moved.top, 0.001f)
        assertEquals(1f, moved.bottom, 0.001f)
    }

    @Test
    fun `move 零偏移不变`() {
        val rect = NormalizedRect(0.2f, 0.3f, 0.6f, 0.7f)
        val moved = rect.move(0f, 0f)
        assertEquals(rect.left, moved.left, 0.001f)
        assertEquals(rect.top, moved.top, 0.001f)
        assertEquals(rect.right, moved.right, 0.001f)
        assertEquals(rect.bottom, moved.bottom, 0.001f)
    }

    @Test
    fun `move 后 normalizedRect 在 0_1 范围`() {
        val rect = NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f)
        val moved = rect.move(0.05f, 0.05f)
        assertTrue("left >= 0", moved.left >= 0f)
        assertTrue("top >= 0", moved.top >= 0f)
        assertTrue("right <= 1", moved.right <= 1f)
        assertTrue("bottom <= 1", moved.bottom <= 1f)
    }

    @Test
    fun `move 到精确边界 0_0`() {
        val rect = NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f)
        val moved = rect.move(-0.1f, -0.1f)
        assertEquals(0f, moved.left, 0.001f)
        assertEquals(0f, moved.top, 0.001f)
    }

    @Test
    fun `move 到精确边界 1_1`() {
        val rect = NormalizedRect(0.5f, 0.5f, 0.9f, 0.9f)
        val moved = rect.move(0.1f, 0.1f)
        assertEquals(0.6f, moved.left, 0.001f)
        assertEquals(1f, moved.right, 0.001f)
        assertEquals(1f, moved.bottom, 0.001f)
    }

    // ══════════════════════════════════════════
    // NormalizedRect.resize 测试
    // ══════════════════════════════════════════

    @Test
    fun `resize 左上角`() {
        val rect = NormalizedRect(0.2f, 0.2f, 0.8f, 0.8f)
        val resized = rect.resize(0, 0.1f, 0.1f)
        assertEquals(0.1f, resized.left, 0.001f)
        assertEquals(0.1f, resized.top, 0.001f)
        assertEquals(0.8f, resized.right, 0.001f)
        assertEquals(0.8f, resized.bottom, 0.001f)
    }

    @Test
    fun `resize 右上角`() {
        val rect = NormalizedRect(0.2f, 0.2f, 0.8f, 0.8f)
        val resized = rect.resize(1, 0.9f, 0.1f)
        assertEquals(0.2f, resized.left, 0.001f)
        assertEquals(0.1f, resized.top, 0.001f)
        assertEquals(0.9f, resized.right, 0.001f)
        assertEquals(0.8f, resized.bottom, 0.001f)
    }

    @Test
    fun `resize 左下角`() {
        val rect = NormalizedRect(0.2f, 0.2f, 0.8f, 0.8f)
        val resized = rect.resize(2, 0.1f, 0.9f)
        assertEquals(0.1f, resized.left, 0.001f)
        assertEquals(0.2f, resized.top, 0.001f)
        assertEquals(0.8f, resized.right, 0.001f)
        assertEquals(0.9f, resized.bottom, 0.001f)
    }

    @Test
    fun `resize 右下角`() {
        val rect = NormalizedRect(0.2f, 0.2f, 0.8f, 0.8f)
        val resized = rect.resize(3, 0.9f, 0.9f)
        assertEquals(0.2f, resized.left, 0.001f)
        assertEquals(0.2f, resized.top, 0.001f)
        assertEquals(0.9f, resized.right, 0.001f)
        assertEquals(0.9f, resized.bottom, 0.001f)
    }

    @Test
    fun `resize 对角点不变`() {
        val rect = NormalizedRect(0.3f, 0.3f, 0.7f, 0.7f)
        val resized = rect.resize(0, 0.4f, 0.4f)
        assertEquals(0.7f, resized.right, 0.001f)
        assertEquals(0.7f, resized.bottom, 0.001f)
    }

    @Test
    fun `resize 自动翻转确保 left less than right`() {
        val rect = NormalizedRect(0.3f, 0.3f, 0.7f, 0.7f)
        val resized = rect.resize(0, 0.8f, 0.8f)
        assertTrue("left < right", resized.left < resized.right)
        assertTrue("top < bottom", resized.top < resized.bottom)
    }

    @Test
    fun `resize 最小尺寸约束`() {
        val rect = NormalizedRect(0.4f, 0.4f, 0.6f, 0.6f)
        val resized = rect.resize(3, 0.405f, 0.405f)
        assertTrue("width >= MIN_SIZE", resized.right - resized.left >= 0.02f)
        assertTrue("height >= MIN_SIZE", resized.bottom - resized.top >= 0.02f)
    }

    @Test
    fun `resize 约束在 0_1 范围`() {
        val rect = NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f)
        val resized = rect.resize(0, -0.1f, -0.1f)
        assertTrue("left >= 0", resized.left >= 0f)
        assertTrue("top >= 0", resized.top >= 0f)
        assertTrue("right <= 1", resized.right <= 1f)
        assertTrue("bottom <= 1", resized.bottom <= 1f)
    }

    @Test
    fun `resize 0_1 全图范围边界`() {
        val rect = NormalizedRect(0f, 0f, 1f, 1f)
        val resized = rect.resize(3, 1.5f, 1.5f)
        assertTrue("right <= 1", resized.right <= 1f)
        assertTrue("bottom <= 1", resized.bottom <= 1f)
    }

    // ══════════════════════════════════════════
    // 四角索引语义
    // ══════════════════════════════════════════

    @Test
    fun `corner 0 是左上角`() {
        val rect = NormalizedRect(0.2f, 0.3f, 0.8f, 0.7f)
        val resized = rect.resize(0, 0.4f, 0.5f)
        assertEquals(0.4f, resized.left, 0.001f)
        assertEquals(0.5f, resized.top, 0.001f)
        assertEquals(0.8f, resized.right, 0.001f)
        assertEquals(0.7f, resized.bottom, 0.001f)
    }

    @Test
    fun `corner 1 是右上角`() {
        val rect = NormalizedRect(0.2f, 0.3f, 0.8f, 0.7f)
        val resized = rect.resize(1, 0.6f, 0.5f)
        assertEquals(0.2f, resized.left, 0.001f)
        assertEquals(0.5f, resized.top, 0.001f)
        assertEquals(0.6f, resized.right, 0.001f)
        assertEquals(0.7f, resized.bottom, 0.001f)
    }

    @Test
    fun `corner 2 是左下角`() {
        val rect = NormalizedRect(0.2f, 0.3f, 0.8f, 0.7f)
        val resized = rect.resize(2, 0.4f, 0.5f)
        assertEquals(0.4f, resized.left, 0.001f)
        assertEquals(0.3f, resized.top, 0.001f)
        assertEquals(0.8f, resized.right, 0.001f)
        assertEquals(0.5f, resized.bottom, 0.001f)
    }

    @Test
    fun `corner 3 是右下角`() {
        val rect = NormalizedRect(0.2f, 0.3f, 0.8f, 0.7f)
        val resized = rect.resize(3, 0.6f, 0.5f)
        assertEquals(0.2f, resized.left, 0.001f)
        assertEquals(0.3f, resized.top, 0.001f)
        assertEquals(0.6f, resized.right, 0.001f)
        assertEquals(0.5f, resized.bottom, 0.001f)
    }

    @Test
    fun `resize 拖角越过对角后自动修正`() {
        val rect = NormalizedRect(0.3f, 0.3f, 0.6f, 0.6f)
        val resized = rect.resize(3, 0.2f, 0.2f)
        assertTrue("left < right", resized.left < resized.right)
        assertTrue("top < bottom", resized.top < resized.bottom)
    }

    // ══════════════════════════════════════════
    // 多 ROI 独立性
    // ══════════════════════════════════════════

    @Test
    fun `多个 NormalizedRect 互相独立`() {
        val roi1 = NormalizedRect(0.1f, 0.1f, 0.3f, 0.3f)
        val roi2 = NormalizedRect(0.5f, 0.5f, 0.8f, 0.8f)
        val moved1 = roi1.move(0.05f, 0.05f)
        assertEquals(0.5f, roi2.left, 0.001f)
        assertEquals(0.8f, roi2.right, 0.001f)
        assertEquals(0.1f, roi1.left, 0.001f)
        assertEquals(0.15f, moved1.left, 0.001f)
    }

    @Test
    fun `move 后 resize 保持一致`() {
        val original = NormalizedRect(0.2f, 0.2f, 0.6f, 0.6f)
        val moved = original.move(0.1f, 0.1f)
        val resized = moved.resize(3, 0.8f, 0.8f)
        assertEquals(0.3f, resized.left, 0.001f)
        assertEquals(0.3f, resized.top, 0.001f)
        assertEquals(0.8f, resized.right, 0.001f)
        assertEquals(0.8f, resized.bottom, 0.001f)
    }

    // ══════════════════════════════════════════
    // 边界组合测试
    // ══════════════════════════════════════════

    @Test
    fun `move 然后 resize 然后 move 保持一致性`() {
        val original = NormalizedRect(0.2f, 0.2f, 0.5f, 0.5f)
        val step1 = original.move(0.1f, 0.1f)       // (0.3, 0.3, 0.6, 0.6)
        val step2 = step1.resize(3, 0.7f, 0.7f)      // (0.3, 0.3, 0.7, 0.7)
        val step3 = step2.move(-0.1f, -0.1f)          // (0.2, 0.2, 0.6, 0.6)

        assertEquals(0.2f, step3.left, 0.001f)
        assertEquals(0.2f, step3.top, 0.001f)
        assertEquals(0.6f, step3.right, 0.001f)
        assertEquals(0.6f, step3.bottom, 0.001f)
    }

    @Test
    fun `resize 最小尺寸后 move 仍合法`() {
        val rect = NormalizedRect(0.4f, 0.4f, 0.6f, 0.6f)
        val shrunk = rect.resize(3, 0.405f, 0.405f)
        assertTrue(shrunk.right - shrunk.left >= 0.02f)
        assertTrue(shrunk.bottom - shrunk.top >= 0.02f)

        val moved = shrunk.move(0.05f, 0.05f)
        assertTrue(moved.left >= 0f)
        assertTrue(moved.top >= 0f)
        assertTrue(moved.right <= 1f)
        assertTrue(moved.bottom <= 1f)
    }

    @Test
    fun `move 对角线偏移`() {
        val rect = NormalizedRect(0.2f, 0.2f, 0.5f, 0.5f)
        val moved = rect.move(0.15f, -0.05f)
        assertEquals(0.35f, moved.left, 0.001f)
        assertEquals(0.15f, moved.top, 0.001f)
        assertEquals(0.65f, moved.right, 0.001f)
        assertEquals(0.45f, moved.bottom, 0.001f)
    }

    @Test
    fun `resize 所有四角同时验证`() {
        val rect = NormalizedRect(0.2f, 0.2f, 0.8f, 0.8f)

        // 左上
        val r0 = rect.resize(0, 0.3f, 0.3f)
        assertEquals(0.3f, r0.left, 0.001f)
        assertEquals(0.3f, r0.top, 0.001f)

        // 右上
        val r1 = rect.resize(1, 0.7f, 0.3f)
        assertEquals(0.7f, r1.right, 0.001f)
        assertEquals(0.3f, r1.top, 0.001f)

        // 左下
        val r2 = rect.resize(2, 0.3f, 0.7f)
        assertEquals(0.3f, r2.left, 0.001f)
        assertEquals(0.7f, r2.bottom, 0.001f)

        // 右下
        val r3 = rect.resize(3, 0.7f, 0.7f)
        assertEquals(0.7f, r3.right, 0.001f)
        assertEquals(0.7f, r3.bottom, 0.001f)
    }

    @Test
    fun `resize 后面积变大`() {
        val rect = NormalizedRect(0.3f, 0.3f, 0.5f, 0.5f)
        val originalArea = (rect.right - rect.left) * (rect.bottom - rect.top)
        val resized = rect.resize(3, 0.8f, 0.8f)
        val newArea = (resized.right - resized.left) * (resized.bottom - resized.top)
        assertTrue("面积变大", newArea > originalArea)
    }

    @Test
    fun `resize 后面积变小但不小于最小面积`() {
        val rect = NormalizedRect(0.2f, 0.2f, 0.8f, 0.8f)
        val resized = rect.resize(3, 0.25f, 0.25f)
        val area = (resized.right - resized.left) * (resized.bottom - resized.top)
        assertTrue("面积 >= MIN_SIZE^2", area >= 0.02f * 0.02f)
    }
}
