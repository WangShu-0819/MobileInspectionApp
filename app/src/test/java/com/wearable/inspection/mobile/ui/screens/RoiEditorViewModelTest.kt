package com.wearable.inspection.mobile.ui.screens

import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.data.repository.InspectionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.ArgumentMatchers.anyString

/**
 * NormalizedRect 和 ROI 编辑逻辑测试
 *
 * 覆盖：序列化/反序列化、move、resize、边界约束、最小尺寸、四角索引、
 * 多 ROI 独立性、move+resize 组合操作、删除选中 ROI、无选中不误删、
 * 删除失败保留状态、多 View templateId 隔离。
 *
 * ViewModel 持久化（moveRoi/resizeRoi/deleteSelectedRoi → InspectionRepository）
 * 通过编译、assembleDebug 和 instrumented 测试验证。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoiEditorViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockRepository: InspectionRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockRepository = mock(InspectionRepository::class.java)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 创建测试用 RoiDefinitionEntity */
    private fun createTestRoi(
        id: String,
        templateId: String = "tpl_001",
        normalizedRect: String = """{"left":0.1,"top":0.1,"right":0.5,"bottom":0.5}""",
    ) = RoiDefinitionEntity(
        id = id,
        templateId = templateId,
        name = "ROI $id",
        order = 0,
        shapeType = "RECT",
        normalizedRect = normalizedRect,
        inspectionType = "VISUAL",
    )

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

    // ══════════════════════════════════════════
    // ViewModel 删除选中 ROI 测试
    // ══════════════════════════════════════════

    @Test
    fun `删除选中 ROI - 成功后从列表移除并清除选中`() = runTest {
        val roi1 = createTestRoi("roi_1")
        val roi2 = createTestRoi("roi_2")
        `when`(mockRepository.getRois("tpl_001")).thenReturn(listOf(roi1, roi2))

        val viewModel = RoiEditorViewModel(mockRepository, "tpl_001")
        advanceUntilIdle()

        assertEquals(2, viewModel.rois.size)

        viewModel.selectRoi("roi_1")
        assertEquals("roi_1", viewModel.selectedRoiId)

        viewModel.deleteSelectedRoi()
        advanceUntilIdle()

        verify(mockRepository).deleteRoi("roi_1")
        assertEquals(1, viewModel.rois.size)
        assertEquals("roi_2", viewModel.rois[0].id)
        assertNull(viewModel.selectedRoiId)
        assertNull(viewModel.deleteError)
    }

    @Test
    fun `无选中 ROI 时调用删除不执行任何操作`() = runTest {
        val roi1 = createTestRoi("roi_1")
        `when`(mockRepository.getRois("tpl_001")).thenReturn(listOf(roi1))

        val viewModel = RoiEditorViewModel(mockRepository, "tpl_001")
        advanceUntilIdle()

        assertNull(viewModel.selectedRoiId)
        assertEquals(1, viewModel.rois.size)

        viewModel.deleteSelectedRoi()
        advanceUntilIdle()

        verify(mockRepository, never()).deleteRoi(anyString())
        assertEquals(1, viewModel.rois.size)
        assertNull(viewModel.selectedRoiId)
    }

    @Test
    fun `删除失败保留 UI 状态并设置错误信息`() = runTest {
        val roi1 = createTestRoi("roi_1")
        val roi2 = createTestRoi("roi_2")
        `when`(mockRepository.getRois("tpl_001")).thenReturn(listOf(roi1, roi2))
        `when`(mockRepository.deleteRoi("roi_1")).thenThrow(RuntimeException("DB 写入失败"))

        val viewModel = RoiEditorViewModel(mockRepository, "tpl_001")
        advanceUntilIdle()

        viewModel.selectRoi("roi_1")
        viewModel.deleteSelectedRoi()
        advanceUntilIdle()

        // 状态应保留，不伪造成功
        assertEquals(2, viewModel.rois.size)
        assertEquals("roi_1", viewModel.selectedRoiId)
        assertNotNull(viewModel.deleteError)
        assertTrue(viewModel.deleteError!!.contains("DB 写入失败"))
    }

    @Test
    fun `清除删除错误`() = runTest {
        val roi1 = createTestRoi("roi_1")
        `when`(mockRepository.getRois("tpl_001")).thenReturn(listOf(roi1))
        `when`(mockRepository.deleteRoi("roi_1")).thenThrow(RuntimeException("失败"))

        val viewModel = RoiEditorViewModel(mockRepository, "tpl_001")
        advanceUntilIdle()

        viewModel.selectRoi("roi_1")
        viewModel.deleteSelectedRoi()
        advanceUntilIdle()
        assertNotNull(viewModel.deleteError)

        viewModel.clearDeleteError()
        assertNull(viewModel.deleteError)
    }

    @Test
    fun `删除后再新增新 ROI 不受影响`() = runTest {
        val roi1 = createTestRoi("roi_1")
        `when`(mockRepository.getRois("tpl_001")).thenReturn(listOf(roi1))

        val viewModel = RoiEditorViewModel(mockRepository, "tpl_001")
        advanceUntilIdle()

        viewModel.selectRoi("roi_1")
        viewModel.deleteSelectedRoi()
        advanceUntilIdle()

        assertEquals(0, viewModel.rois.size)
        assertNull(viewModel.selectedRoiId)

        // 绘制并保存新 ROI
        viewModel.toggleDrawingMode()
        viewModel.updateDrawingRect(NormalizedRect(0.2f, 0.2f, 0.6f, 0.6f))
        viewModel.saveDrawingRect("新 ROI")
        advanceUntilIdle()

        assertEquals(1, viewModel.rois.size)
        assertEquals("新 ROI", viewModel.rois[0].name)
    }

    // ══════════════════════════════════════════
    // 多 View templateId 隔离测试
    // ══════════════════════════════════════════

    @Test
    fun `不同 templateId 的 ViewModel 各自管理 ROI 列表`() = runTest {
        val roiA = createTestRoi("roi_A", templateId = "tpl_A")
        val roiB = createTestRoi("roi_B", templateId = "tpl_B")
        `when`(mockRepository.getRois("tpl_A")).thenReturn(listOf(roiA))
        `when`(mockRepository.getRois("tpl_B")).thenReturn(listOf(roiB))

        val viewModelA = RoiEditorViewModel(mockRepository, "tpl_A")
        val viewModelB = RoiEditorViewModel(mockRepository, "tpl_B")
        advanceUntilIdle()

        assertEquals(1, viewModelA.rois.size)
        assertEquals(1, viewModelB.rois.size)
        assertEquals("roi_A", viewModelA.rois[0].id)
        assertEquals("roi_B", viewModelB.rois[0].id)

        // 删除 tpl_A 的 ROI 不影响 tpl_B
        viewModelA.selectRoi("roi_A")
        viewModelA.deleteSelectedRoi()
        advanceUntilIdle()

        assertEquals(0, viewModelA.rois.size)
        assertEquals(1, viewModelB.rois.size)
        verify(mockRepository).deleteRoi("roi_A")
    }

    // ══════════════════════════════════════════
    // 前序行为回归：选中、取消、移动、缩放
    // ══════════════════════════════════════════

    @Test
    fun `选中和取消选中`() = runTest {
        val roi1 = createTestRoi("roi_1")
        `when`(mockRepository.getRois("tpl_001")).thenReturn(listOf(roi1))

        val viewModel = RoiEditorViewModel(mockRepository, "tpl_001")
        advanceUntilIdle()

        viewModel.selectRoi("roi_1")
        assertEquals("roi_1", viewModel.selectedRoiId)

        viewModel.selectRoi(null)
        assertNull(viewModel.selectedRoiId)
    }

    @Test
    fun `切换绘制模式清除选中状态`() = runTest {
        val roi1 = createTestRoi("roi_1")
        `when`(mockRepository.getRois("tpl_001")).thenReturn(listOf(roi1))

        val viewModel = RoiEditorViewModel(mockRepository, "tpl_001")
        advanceUntilIdle()

        viewModel.selectRoi("roi_1")
        assertEquals("roi_1", viewModel.selectedRoiId)

        viewModel.toggleDrawingMode()
        assertNull(viewModel.selectedRoiId)
        assertTrue(viewModel.isDrawingMode)
    }

    @Test
    fun `取消绘制清除绘制矩形`() = runTest {
        `when`(mockRepository.getRois("tpl_001")).thenReturn(emptyList())

        val viewModel = RoiEditorViewModel(mockRepository, "tpl_001")
        advanceUntilIdle()

        viewModel.toggleDrawingMode()
        viewModel.updateDrawingRect(NormalizedRect(0.1f, 0.1f, 0.5f, 0.5f))
        assertNotNull(viewModel.drawingRect)

        viewModel.cancelDrawing()
        assertNull(viewModel.drawingRect)
        assertFalse(viewModel.isDrawingMode)
    }

    // ══════════════════════════════════════════
    // 删除后重新加载验证
    // ══════════════════════════════════════════

    @Test
    fun `删除后重新加载 ROI 不再出现`() = runTest {
        val roi1 = createTestRoi("roi_1")
        val roi2 = createTestRoi("roi_2")
        // 初始加载：两个 ROI
        `when`(mockRepository.getRois("tpl_001")).thenReturn(listOf(roi1, roi2))

        val viewModel = RoiEditorViewModel(mockRepository, "tpl_001")
        advanceUntilIdle()
        assertEquals(2, viewModel.rois.size)

        // 删除 roi_1
        viewModel.selectRoi("roi_1")
        viewModel.deleteSelectedRoi()
        advanceUntilIdle()
        assertEquals(1, viewModel.rois.size)

        // 模拟重新加载：repository 现在只返回 roi_2
        `when`(mockRepository.getRois("tpl_001")).thenReturn(listOf(roi2))
        viewModel.refreshRois()
        advanceUntilIdle()

        assertEquals(1, viewModel.rois.size)
        assertEquals("roi_2", viewModel.rois[0].id)
        // 确认被删除的 ROI 不再出现
        assertTrue(viewModel.rois.none { it.id == "roi_1" })
    }

    // ══════════════════════════════════════════
    // 前序行为回归：新增、移动、缩放
    // ══════════════════════════════════════════

    @Test
    fun `新增 ROI 后列表包含新 ROI`() = runTest {
        `when`(mockRepository.getRois("tpl_001")).thenReturn(emptyList())

        val viewModel = RoiEditorViewModel(mockRepository, "tpl_001")
        advanceUntilIdle()
        assertEquals(0, viewModel.rois.size)

        viewModel.toggleDrawingMode()
        viewModel.updateDrawingRect(NormalizedRect(0.2f, 0.2f, 0.6f, 0.6f))
        viewModel.saveDrawingRect("新增 ROI")
        advanceUntilIdle()

        assertEquals(1, viewModel.rois.size)
        assertEquals("新增 ROI", viewModel.rois[0].name)
    }

    @Test
    fun `移动 ROI 后 normalizedRect 更新`() = runTest {
        val roi1 = createTestRoi("roi_1", normalizedRect = """{"left":0.2,"top":0.2,"right":0.6,"bottom":0.6}""")
        `when`(mockRepository.getRois("tpl_001")).thenReturn(listOf(roi1))

        val viewModel = RoiEditorViewModel(mockRepository, "tpl_001")
        advanceUntilIdle()

        viewModel.moveRoi("roi_1", 0.1f, 0.1f)
        advanceUntilIdle()

        val moved = NormalizedRect.fromJsonString(viewModel.rois[0].normalizedRect)!!
        assertEquals(0.3f, moved.left, 0.001f)
        assertEquals(0.3f, moved.top, 0.001f)
        assertEquals(0.7f, moved.right, 0.001f)
        assertEquals(0.7f, moved.bottom, 0.001f)
    }

    @Test
    fun `缩放 ROI 后 normalizedRect 更新`() = runTest {
        val roi1 = createTestRoi("roi_1", normalizedRect = """{"left":0.2,"top":0.2,"right":0.6,"bottom":0.6}""")
        `when`(mockRepository.getRois("tpl_001")).thenReturn(listOf(roi1))

        val viewModel = RoiEditorViewModel(mockRepository, "tpl_001")
        advanceUntilIdle()

        viewModel.resizeRoi("roi_1", 3, 0.8f, 0.8f)
        advanceUntilIdle()

        val resized = NormalizedRect.fromJsonString(viewModel.rois[0].normalizedRect)!!
        assertEquals(0.2f, resized.left, 0.001f)
        assertEquals(0.2f, resized.top, 0.001f)
        assertEquals(0.8f, resized.right, 0.001f)
        assertEquals(0.8f, resized.bottom, 0.001f)
    }
}
