package com.wearable.inspection.mobile.template

import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import com.wearable.inspection.mobile.data.image.StoredImageResult
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
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * Mockito any() 返回 null，Kotlin 非空参数不接受。
 * 使用此辅助函数绕过类型限制。
 */
private fun <T> anyNonNull(type: Class<T>): T {
    org.mockito.ArgumentMatchers.any(type)
    @Suppress("UNCHECKED_CAST")
    return null as T
}

/**
 * TemplateCaptureViewModel 真实可执行 JVM 测试
 *
 * 直接调用 ViewModel 的 internal 函数验证行为，不依赖源码字符串比对。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TemplateCaptureViewModelTest {

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

    private fun createStoredResult(path: String = "/tmp/stored.jpg") = StoredImageResult(
        finalPath = path,
        sizeBytes = 1024L,
        width = 640,
        height = 480,
        orientation = 1,
        capturedAt = System.currentTimeMillis(),
    )

    // ══════════════════════════════════════════
    // Task 1a: insertNewView 真实行为测试
    // ══════════════════════════════════════════

    @Test
    fun `新增 View 返回真实 UUID templateId 而非占位符`() = runTest {
        `when`(mockRepository.getTemplatesByPart("part_001")).thenReturn(emptyList())

        val viewModel = TemplateCaptureViewModel(mockRepository, "part_001", null)
        advanceUntilIdle()

        val savedId = viewModel.insertNewView("/tmp/img.jpg")
        advanceUntilIdle()

        assertNotNull("应返回非 null templateId", savedId)
        assertTrue("templateId 应以 partId 开头", savedId!!.startsWith("part_001_capture_"))
        assertTrue("templateId 应包含 UUID 段（含 '-'）", savedId.contains("-"))
        assertFalse("不应返回占位符 'new'", savedId == "new")
    }

    @Test
    fun `新增 View 调用 repository insertTemplate`() = runTest {
        `when`(mockRepository.getTemplatesByPart("part_001")).thenReturn(emptyList())

        val viewModel = TemplateCaptureViewModel(mockRepository, "part_001", null)
        advanceUntilIdle()

        viewModel.insertNewView("/tmp/img.jpg")
        advanceUntilIdle()

        verify(mockRepository).insertTemplate(anyNonNull(InspectionTemplateEntity::class.java))
    }

    @Test
    fun `新增 View 失败时返回 null`() = runTest {
        `when`(mockRepository.getTemplatesByPart("part_001")).thenThrow(RuntimeException("DB error"))

        val viewModel = TemplateCaptureViewModel(mockRepository, "part_001", null)
        advanceUntilIdle()

        val result = viewModel.insertNewView("/tmp/img.jpg")
        advanceUntilIdle()

        assertNull("失败时应返回 null", result)
    }

    // ══════════════════════════════════════════
    // Task 1b: saveToDatabase 真实行为测试
    // ══════════════════════════════════════════

    @Test
    fun `新增模式 saveToDatabase 返回真实 templateId`() = runTest {
        `when`(mockRepository.getTemplatesByPart("part_001")).thenReturn(emptyList())

        val viewModel = TemplateCaptureViewModel(mockRepository, "part_001", null)
        advanceUntilIdle()

        val savedId = viewModel.saveToDatabase(createStoredResult())
        advanceUntilIdle()

        assertNotNull(savedId)
        assertTrue("应返回 UUID 格式的 ID", savedId!!.startsWith("part_001_capture_"))
        assertFalse("不应是占位符", savedId == "new")
    }

    @Test
    fun `重拍模式 saveToDatabase 返回已有 templateId`() = runTest {
        val existing = InspectionTemplateEntity(
            id = "existing_view_001",
            partId = "part_001",
            name = "视角 1",
            mainImagePath = "/tmp/old.jpg",
        )
        `when`(mockRepository.getTemplate("existing_view_001")).thenReturn(existing)

        val viewModel = TemplateCaptureViewModel(mockRepository, "part_001", "existing_view_001")
        advanceUntilIdle()

        val savedId = viewModel.saveToDatabase(createStoredResult("/tmp/new.jpg"))
        advanceUntilIdle()

        assertEquals("重拍应返回已有 templateId", "existing_view_001", savedId)
    }

    @Test
    fun `重拍模式调用 updateTemplate 替换图片路径`() = runTest {
        val existing = InspectionTemplateEntity(
            id = "existing_view_001",
            partId = "part_001",
            name = "视角 1",
            mainImagePath = "/tmp/old.jpg",
        )
        `when`(mockRepository.getTemplate("existing_view_001")).thenReturn(existing)

        val viewModel = TemplateCaptureViewModel(mockRepository, "part_001", "existing_view_001")
        advanceUntilIdle()

        viewModel.saveToDatabase(createStoredResult("/tmp/new.jpg"))
        advanceUntilIdle()

        // 验证 updateTemplate 被调用（不依赖 ArgumentCaptor）
        verify(mockRepository).updateTemplate(anyNonNull(InspectionTemplateEntity::class.java))
    }

    @Test
    fun `重拍模式删除旧图片`() = runTest {
        val existing = InspectionTemplateEntity(
            id = "existing_view_001",
            partId = "part_001",
            name = "视角 1",
            mainImagePath = "/tmp/old.jpg",
        )
        `when`(mockRepository.getTemplate("existing_view_001")).thenReturn(existing)

        val viewModel = TemplateCaptureViewModel(mockRepository, "part_001", "existing_view_001")
        advanceUntilIdle()

        viewModel.saveToDatabase(createStoredResult("/tmp/new.jpg"))
        advanceUntilIdle()

        verify(mockRepository).deleteTemplateImage("/tmp/old.jpg")
    }

    @Test
    fun `saveToDatabase 失败时返回 null`() = runTest {
        `when`(mockRepository.getTemplatesByPart("part_001")).thenThrow(RuntimeException("DB error"))

        val viewModel = TemplateCaptureViewModel(mockRepository, "part_001", null)
        advanceUntilIdle()

        val result = viewModel.saveToDatabase(createStoredResult())
        advanceUntilIdle()

        assertNull("失败时应返回 null", result)
    }

    // ══════════════════════════════════════════
    // Task 1c: 导航决策纯函数测试
    // ══════════════════════════════════════════

    @Test
    fun `Saved 状态解析出真实 templateId`() {
        val savedId = "part_001_capture_abc-123-def"
        val state = TemplateCaptureViewModel.CaptureState.Saved(savedId)

        val target = TemplateCaptureViewModel.resolveNavigationTarget(state)

        assertEquals(savedId, target)
    }

    @Test
    fun `Idle 状态不触发导航`() {
        val target = TemplateCaptureViewModel.resolveNavigationTarget(
            TemplateCaptureViewModel.CaptureState.Idle
        )
        assertNull(target)
    }

    @Test
    fun `Capturing 状态不触发导航`() {
        val target = TemplateCaptureViewModel.resolveNavigationTarget(
            TemplateCaptureViewModel.CaptureState.Capturing
        )
        assertNull(target)
    }

    @Test
    fun `Error 状态不触发导航`() {
        val target = TemplateCaptureViewModel.resolveNavigationTarget(
            TemplateCaptureViewModel.CaptureState.Error("fail")
        )
        assertNull(target)
    }

    @Test
    fun `构造 ROI Editor 路由包含 templateId`() {
        val route = TemplateCaptureViewModel.buildRoiEditorRoute("tpl_abc_123")
        assertTrue("路由应以 roi_editor/ 开头", route.startsWith("roi_editor/"))
        assertTrue("路由应包含 templateId", route.contains("tpl_abc_123"))
    }

    @Test
    fun `构造路由对特殊字符编码`() {
        val route = TemplateCaptureViewModel.buildRoiEditorRoute("part with space")
        assertTrue("路由应对空格编码", route.contains("+") || route.contains("%20"))
        assertFalse("路由不应包含原始空格", route.endsWith("part with space"))
    }

    // ══════════════════════════════════════════
    // Task 1d: CaptureState.Saved 携带真实 ID
    // ══════════════════════════════════════════

    @Test
    fun `CaptureState_Saved 是 data class 且携带 templateId`() {
        val id1 = "part_001_capture_uuid-1"
        val id2 = "part_001_capture_uuid-2"
        val saved1 = TemplateCaptureViewModel.CaptureState.Saved(id1)
        val saved2 = TemplateCaptureViewModel.CaptureState.Saved(id1)
        val saved3 = TemplateCaptureViewModel.CaptureState.Saved(id2)

        assertEquals("相同 ID 应相等", saved1, saved2)
        assertFalse("不同 ID 不应相等", saved1 == saved3)
        assertEquals("templateId 应可读取", id1, saved1.templateId)
    }

    @Test
    fun `CaptureState_Saved 不使用 new 占位符`() {
        val saved = TemplateCaptureViewModel.CaptureState.Saved("real_id_123")
        assertFalse("不应是 'new'", saved.templateId == "new")
    }

    // ══════════════════════════════════════════
    // Task 1e: onCaptureSuccess 契约验证
    // ══════════════════════════════════════════

    @Test
    fun `resolveNavigationTarget 从 Saved 提取的 ID 可构造正确路由`() {
        val realId = "part_001_capture_f47ac10b-58cc-4372-a567-0e02b2c3d479"
        val state = TemplateCaptureViewModel.CaptureState.Saved(realId)

        val navTarget = TemplateCaptureViewModel.resolveNavigationTarget(state)
        assertNotNull(navTarget)

        val route = TemplateCaptureViewModel.buildRoiEditorRoute(navTarget!!)
        assertTrue("路由应包含 templateId", route.contains(realId))
        assertTrue("路由应以 roi_editor/ 开头", route.startsWith("roi_editor/"))
    }

    @Test
    fun `非 Saved 状态不产生导航目标`() {
        val states = listOf(
            TemplateCaptureViewModel.CaptureState.Idle,
            TemplateCaptureViewModel.CaptureState.Capturing,
            TemplateCaptureViewModel.CaptureState.Error("msg"),
        )
        states.forEach { state ->
            val target = TemplateCaptureViewModel.resolveNavigationTarget(state)
            assertNull("非 Saved 状态不应产生导航目标: $state", target)
        }
    }
}
