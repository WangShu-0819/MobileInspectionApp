package com.wearable.inspection.mobile.ui.screens.workbench

import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import com.wearable.inspection.mobile.data.entity.PartEntity
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.data.repository.InspectionRepository
import com.wearable.inspection.mobile.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * WorkbenchViewModel 视角推进逻辑测试
 *
 * 覆盖：
 * - advanceToNextView() 非最后一个 View → 返回 true，viewIndex + 1
 * - advanceToNextView() 最后一个 View → 返回 false，allViewsCaptured = true
 * - advanceToNextView() 连续调用 → 按序推进 0, 1, 2, …
 * - advanceToNextView() 到达末尾后不再推进
 * - resetViewIndex() 重置后从头开始
 *
 * 注：templates 使用 WhileSubscribed(5000)，需先 collect 触发订阅。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchViewModelAdvanceTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockRepository: InspectionRepository
    private lateinit var mockSettings: SettingsStore

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockRepository = mock(InspectionRepository::class.java)
        mockSettings = mock(SettingsStore::class.java)

        runBlocking {
            `when`(mockRepository.getParts()).thenReturn(emptyList())
            `when`(mockRepository.getAllTemplates()).thenReturn(emptyList())
        }
        `when`(mockRepository.observeParts()).thenReturn(MutableStateFlow(emptyList()))
        `when`(mockSettings.selectedPartId).thenReturn(null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createTemplate(id: String, partId: String = "p1", order: Int = 0) =
        InspectionTemplateEntity(
            id = id,
            partId = partId,
            name = "View $id",
            mainImagePath = "/img/$id.jpg",
            enabled = true,
            displayOrder = order
        )

    private fun createPart(id: String) = PartEntity(id = id, name = "Part $id")

    private fun setupMocks(partId: String, templates: List<InspectionTemplateEntity>) {
        val part = createPart(partId)
        runBlocking {
            `when`(mockRepository.getParts()).thenReturn(listOf(part))
            `when`(mockRepository.getAllTemplates()).thenReturn(templates)
            `when`(mockRepository.getPartById(partId)).thenReturn(part)
        }
        `when`(mockRepository.observeParts()).thenReturn(MutableStateFlow(listOf(part)))
        `when`(mockRepository.observeTemplates(partId)).thenReturn(flowOf(templates))
        templates.forEach { tpl ->
            `when`(mockRepository.observeRois(tpl.id)).thenReturn(emptyFlow())
        }
        `when`(mockSettings.selectedPartId).thenReturn(partId)
    }

    @Test
    fun `advanceToNextView returns true and increments index for non-last view`() = runTest {
        val templates = listOf(createTemplate("t0", "p1", 0), createTemplate("t1", "p1", 1))
        setupMocks("p1", templates)

        val vm = WorkbenchViewModel(mockRepository, mockSettings)
        // collect 触发 WhileSubscribed 订阅
        val job = launch { vm.templates.collect {} }
        advanceUntilIdle()

        assertEquals(0, vm.currentViewIndex.value)

        val hasNext = vm.advanceToNextView()
        assertTrue(hasNext)
        assertEquals(1, vm.currentViewIndex.value)
        assertFalse(vm.allViewsCaptured.value)

        job.cancel()
    }

    @Test
    fun `advanceToNextView returns false and sets allViewsCaptured for last view`() = runTest {
        val templates = listOf(createTemplate("t0", "p1", 0), createTemplate("t1", "p1", 1))
        setupMocks("p1", templates)

        val vm = WorkbenchViewModel(mockRepository, mockSettings)
        val job = launch { vm.templates.collect {} }
        advanceUntilIdle()

        vm.advanceToNextView()
        assertEquals(1, vm.currentViewIndex.value)

        val hasNext = vm.advanceToNextView()
        assertFalse(hasNext)
        assertTrue(vm.allViewsCaptured.value)

        job.cancel()
    }

    @Test
    fun `advanceToNextView sequential calls advance 0, 1, 2`() = runTest {
        val templates = listOf(
            createTemplate("t0", "p1", 0),
            createTemplate("t1", "p1", 1),
            createTemplate("t2", "p1", 2)
        )
        setupMocks("p1", templates)

        val vm = WorkbenchViewModel(mockRepository, mockSettings)
        val job = launch { vm.templates.collect {} }
        advanceUntilIdle()

        assertEquals(0, vm.currentViewIndex.value)

        assertTrue(vm.advanceToNextView())  // → 1
        assertEquals(1, vm.currentViewIndex.value)

        assertTrue(vm.advanceToNextView())  // → 2
        assertEquals(2, vm.currentViewIndex.value)

        assertFalse(vm.advanceToNextView()) // → end
        assertTrue(vm.allViewsCaptured.value)

        job.cancel()
    }

    @Test
    fun `advanceToNextView does not advance past end`() = runTest {
        val templates = listOf(createTemplate("t0", "p1", 0))
        setupMocks("p1", templates)

        val vm = WorkbenchViewModel(mockRepository, mockSettings)
        val job = launch { vm.templates.collect {} }
        advanceUntilIdle()

        assertFalse(vm.advanceToNextView())
        assertTrue(vm.allViewsCaptured.value)

        assertFalse(vm.advanceToNextView())
        assertTrue(vm.allViewsCaptured.value)

        job.cancel()
    }

    @Test
    fun `resetViewIndex resets to zero and clears allViewsCaptured`() = runTest {
        val templates = listOf(createTemplate("t0", "p1", 0), createTemplate("t1", "p1", 1))
        setupMocks("p1", templates)

        val vm = WorkbenchViewModel(mockRepository, mockSettings)
        val job = launch { vm.templates.collect {} }
        advanceUntilIdle()

        vm.advanceToNextView()
        assertEquals(1, vm.currentViewIndex.value)

        vm.resetViewIndex()
        assertEquals(0, vm.currentViewIndex.value)
        assertFalse(vm.allViewsCaptured.value)

        job.cancel()
    }

    @Test
    fun `completeView advances only the captured view index`() = runTest {
        val templates = listOf(createTemplate("t0", "p1", 0), createTemplate("t1", "p1", 1))
        setupMocks("p1", templates)

        val vm = WorkbenchViewModel(mockRepository, mockSettings)
        val job = launch { vm.templates.collect {} }
        advanceUntilIdle()

        assertEquals(ViewCompletionResult.ADVANCED, vm.completeView(0))
        assertEquals(1, vm.currentViewIndex.value)
        assertEquals(ViewCompletionResult.IGNORED, vm.completeView(0))
        assertEquals(1, vm.currentViewIndex.value)

        job.cancel()
    }

    @Test
    fun `completeView completes the last view and ignores duplicate completion`() = runTest {
        val templates = listOf(createTemplate("t0", "p1", 0))
        setupMocks("p1", templates)

        val vm = WorkbenchViewModel(mockRepository, mockSettings)
        val job = launch { vm.templates.collect {} }
        advanceUntilIdle()

        assertEquals(ViewCompletionResult.COMPLETED, vm.completeView(0))
        assertTrue(vm.allViewsCaptured.value)
        assertEquals(ViewCompletionResult.IGNORED, vm.completeView(0))
        assertEquals(0, vm.currentViewIndex.value)

        job.cancel()
    }

    @Test
    fun `completeView ignores stale or out of order view without skipping next view`() = runTest {
        val templates = listOf(
            createTemplate("t0", "p1", 0),
            createTemplate("t1", "p1", 1),
            createTemplate("t2", "p1", 2)
        )
        setupMocks("p1", templates)

        val vm = WorkbenchViewModel(mockRepository, mockSettings)
        val job = launch { vm.templates.collect {} }
        advanceUntilIdle()

        assertEquals(ViewCompletionResult.IGNORED, vm.completeView(1))
        assertEquals(0, vm.currentViewIndex.value)
        assertEquals(ViewCompletionResult.ADVANCED, vm.completeView(0))
        assertEquals(1, vm.currentViewIndex.value)
        assertEquals(ViewCompletionResult.ADVANCED, vm.completeView(1))
        assertEquals(2, vm.currentViewIndex.value)

        job.cancel()
    }

    @Test
    fun `selectPart reloads templates and rois from the new part`() = runTest {
        val part1 = createPart("p1")
        val part2 = createPart("p2")
        val template1 = createTemplate("t1", "p1")
        val template2 = createTemplate("t2", "p2")
        val roi2 = RoiDefinitionEntity(
            id = "roi2",
            templateId = "t2",
            name = "ROI 2",
            order = 0,
            normalizedRect = "{\"left\":0.1,\"top\":0.1,\"right\":0.4,\"bottom\":0.4}",
            inspectionType = "FEATURE",
        )
        val partsFlow = MutableStateFlow(listOf(part1, part2))

        runBlocking {
            `when`(mockRepository.getParts()).thenReturn(listOf(part1, part2))
            `when`(mockRepository.getAllTemplates()).thenReturn(listOf(template1, template2))
            `when`(mockRepository.getPartById("p1")).thenReturn(part1)
            `when`(mockRepository.getPartById("p2")).thenReturn(part2)
        }
        `when`(mockRepository.observeParts()).thenReturn(partsFlow)
        `when`(mockRepository.observeTemplates("p1")).thenReturn(flowOf(listOf(template1)))
        `when`(mockRepository.observeTemplates("p2")).thenReturn(flowOf(listOf(template2)))
        `when`(mockRepository.observeRois("t1")).thenReturn(flowOf(emptyList()))
        `when`(mockRepository.observeRois("t2")).thenReturn(flowOf(listOf(roi2)))
        `when`(mockSettings.selectedPartId).thenReturn("p1")

        val vm = WorkbenchViewModel(mockRepository, mockSettings)
        val jobs = listOf(
            launch { vm.templates.collect {} },
            launch { vm.selectedTemplate.collect {} },
            launch { vm.rois.collect {} },
        )
        advanceUntilIdle()

        assertEquals("p1", vm.selectedTemplate.value?.partId)
        assertEquals("t1", vm.selectedTemplate.value?.id)

        vm.selectPart("p2")
        advanceUntilIdle()

        assertEquals("p2", vm.selectedPart.value?.id)
        assertEquals(listOf("t2"), vm.templates.value.map { it.id })
        assertEquals("p2", vm.selectedTemplate.value?.partId)
        assertEquals("t2", vm.selectedTemplate.value?.id)
        assertEquals(listOf("roi2"), vm.rois.value.map { it.id })

        jobs.forEach { it.cancel() }
    }
}
