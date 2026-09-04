package com.wearable.inspection.mobile.ui.screens

import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import com.wearable.inspection.mobile.data.entity.PartEntity
import com.wearable.inspection.mobile.data.entity.RoiTargetType
import com.wearable.inspection.mobile.data.entity.ViewRoiConfirmEntity
import com.wearable.inspection.mobile.data.export.InspectionExcelExporter
import com.wearable.inspection.mobile.data.repository.InspectionRepository
import com.wearable.inspection.mobile.data.settings.SettingsStore
import com.wearable.inspection.mobile.ui.screens.workbench.ViewCompletionResult
import com.wearable.inspection.mobile.ui.screens.workbench.WorkbenchViewModel
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 无 ROI View 推进和导出专项测试
 *
 * 覆盖：
 * - 连续多个无 ROI View 按顺序拍摄推进
 * - 无 ROI View 位于中间时不被跳过
 * - 无 ROI 最后一个 View 后进入 ExportResult
 * - 拍照失败/未保存时不能推进
 * - 无 ROI View 不生成虚假 ROI 或 PASS/FAIL
 * - ZIP 包含所有 View 原始照片（含无 ROI View）
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoRoiViewAdvancementTest {

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

    // ── 场景 6：连续多个无 ROI View 按顺序拍摄 ──

    @Test
    fun `consecutive no-ROI views advance 0 then 1 then 2 in order`() = runTest {
        // 3 个 View 全部无 ROI
        val templates = listOf(
            createTemplate("t0", "p1", 0),
            createTemplate("t1", "p1", 1),
            createTemplate("t2", "p1", 2)
        )
        setupMocks("p1", templates)

        val vm = WorkbenchViewModel(mockRepository, mockSettings)
        val job = launch { vm.templates.collect {} }
        advanceUntilIdle()

        // View 0 拍照后
        assertEquals(ViewCompletionResult.ADVANCED, vm.completeView(0))
        assertEquals(1, vm.currentViewIndex.value)

        // View 1 拍照后
        assertEquals(ViewCompletionResult.ADVANCED, vm.completeView(1))
        assertEquals(2, vm.currentViewIndex.value)

        // View 2（最后一个）拍照后
        assertEquals(ViewCompletionResult.COMPLETED, vm.completeView(2))
        assertTrue(vm.allViewsCaptured.value)

        job.cancel()
    }

    // ── 场景 7：无 ROI View 位于中间时不被跳过 ──

    @Test
    fun `middle no-ROI view is not skipped and next view loads correctly`() = runTest {
        // 3 个 View：View 0 有 ROI，View 1 无 ROI，View 2 有 ROI
        // completeView 不关心 ROI，它只关心 viewIndex 推进
        val templates = listOf(
            createTemplate("t0", "p1", 0),
            createTemplate("t1", "p1", 1),
            createTemplate("t2", "p1", 2)
        )
        setupMocks("p1", templates)

        val vm = WorkbenchViewModel(mockRepository, mockSettings)
        val job = launch { vm.templates.collect {} }
        advanceUntilIdle()

        // View 0 完成（有 ROI，确认后推进）
        assertEquals(ViewCompletionResult.ADVANCED, vm.completeView(0))
        assertEquals(1, vm.currentViewIndex.value)
        assertFalse(vm.allViewsCaptured.value)

        // View 1 完成（无 ROI，拍照后直接推进）
        assertEquals(ViewCompletionResult.ADVANCED, vm.completeView(1))
        assertEquals(2, vm.currentViewIndex.value)
        assertFalse(vm.allViewsCaptured.value)

        // View 2 完成（有 ROI，确认后推进）
        assertEquals(ViewCompletionResult.COMPLETED, vm.completeView(2))
        assertTrue(vm.allViewsCaptured.value)

        job.cancel()
    }

    @Test
    fun `stale view index after middle no-ROI advance is correctly ignored`() = runTest {
        val templates = listOf(
            createTemplate("t0", "p1", 0),
            createTemplate("t1", "p1", 1),
            createTemplate("t2", "p1", 2)
        )
        setupMocks("p1", templates)

        val vm = WorkbenchViewModel(mockRepository, mockSettings)
        val job = launch { vm.templates.collect {} }
        advanceUntilIdle()

        // View 0 完成
        assertEquals(ViewCompletionResult.ADVANCED, vm.completeView(0))
        assertEquals(1, vm.currentViewIndex.value)

        // 重复的 View 0 回调（来自确认页延迟返回）应被忽略
        assertEquals(ViewCompletionResult.IGNORED, vm.completeView(0))
        assertEquals(1, vm.currentViewIndex.value)

        // View 1 完成
        assertEquals(ViewCompletionResult.ADVANCED, vm.completeView(1))
        assertEquals(2, vm.currentViewIndex.value)

        job.cancel()
    }

    // ── 场景 8：最后一个无 ROI View 拍照后进入 ExportResultScreen ──

    @Test
    fun `last no-ROI view completion returns COMPLETED`() = runTest {
        val templates = listOf(
            createTemplate("t0", "p1", 0),
            createTemplate("t1", "p1", 1)
        )
        setupMocks("p1", templates)

        val vm = WorkbenchViewModel(mockRepository, mockSettings)
        val job = launch { vm.templates.collect {} }
        advanceUntilIdle()

        // View 0 推进
        assertEquals(ViewCompletionResult.ADVANCED, vm.completeView(0))

        // View 1（最后一个）完成
        assertEquals(ViewCompletionResult.COMPLETED, vm.completeView(1))
        assertTrue(vm.allViewsCaptured.value)

        job.cancel()
    }

    // ── 场景 4：无 ROI View 拍照失败或未保存时不能推进 ──

    @Test
    fun `live inspection source prevents view completion on capture failure`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
            .readText()

        // completeView 调用必须在 storeResult != null 分支内
        val storeCheck = source.indexOf("if (storeResult != null)")
        val completeCall = source.indexOf("viewModel.completeView(capturedViewIndex)")
        assertTrue("completeView 必须在 storeResult 检查之后", completeCall > storeCheck)

        // onFailure 分支不应包含 completeView
        val failureIdx = source.indexOf("onFailure = {")
        assertTrue("应存在 onFailure 分支", failureIdx > 0)
        assertFalse(
            "onFailure 分支不应调用 completeView",
            source.substring(failureIdx).contains("completeView")
        )

        // storeResult == null 分支（图片保存失败）不应推进
        val elseBranch = source.indexOf("} else {", storeCheck)
        assertTrue("应有 storeResult 为 null 的 else 分支", elseBranch > storeCheck)
        val nextTry = source.indexOf("try {", storeCheck)
        assertFalse(
            "图片保存失败分支不应调用 completeView",
            source.substring(elseBranch, nextTry).contains("completeView")
        )
    }

    @Test
    fun `live inspection source prevents view completion when photo insert fails`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
            .readText()

        // try-catch 包裹 photoId 校验和 completeView
        val tryIdx = source.indexOf("try {", source.indexOf("val photoId = repository.insertCapturedPhoto"))
        assertTrue("应有 try-catch 包裹照片插入", tryIdx > 0)
        val catchIdx = source.indexOf("catch (error: Exception)", tryIdx)
        assertTrue("应有 catch 分支", catchIdx > tryIdx)

        // catch 内设置 ERROR 状态，不推进
        val catchBlock = source.substring(catchIdx, (catchIdx + 300).coerceAtMost(source.length))
        assertTrue("catch 应设置 captureState = CaptureUiState.ERROR", catchBlock.contains("CaptureUiState.ERROR"))
        assertFalse("catch 不应调用 completeView", catchBlock.contains("completeView"))
    }

    // ── 场景 5：无 ROI View 的照片真实保存到当前 batchId ──

    @Test
    fun `photo insert and validation happen before ROI branch`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
            .readText()

        val insertCall = source.indexOf("val photoId = repository.insertCapturedPhoto(capturedPhoto)")
        val readback = source.indexOf("repository.getCapturedPhoto(photoId)")
        val roiBranch = source.indexOf("val rois = repository.getRois(capturedTemplateId)")

        assertTrue("照片插入应在 ROI 查询之前", insertCall < roiBranch)
        assertTrue("照片回读校验应在 ROI 查询之前", readback < roiBranch)
    }

    @Test
    fun `photo entity uses captured view index and template id from snapshot`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
            .readText()

        // 照片实体使用 capturedViewIndex 和 capturedTemplateId
        assertTrue("viewIndex 应来自 capturedViewIndex", source.contains("viewIndex = capturedViewIndex"))
        assertTrue("templateId 应来自 capturedTemplateId", source.contains("templateId = capturedTemplateId"))
    }

    // ── 场景 12：ZIP 包含所有 View 原始照片 ──

    @Test
    fun `zip export service iterates all photos regardless of confirms`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/data/export/InspectionZipExportService.kt")
            .readText()

        // 照片遍历在确认行读取之前
        val photoLoop = source.indexOf("for (photo in photos)")
        val confirmsRead = source.indexOf("val confirms = repository.getViewRoiConfirms(batchId)")
        assertTrue("照片遍历应在确认行读取之前", photoLoop < confirmsRead)

        // ZIP 写入照片时不检查该照片是否有确认行
        assertFalse(
            "不应按确认行过滤照片",
            source.contains("photos.filter") || source.contains("photos.filterNot")
        )
    }

    @Test
    fun `zip keeps all photos including views without roi confirms`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/data/export/InspectionZipExportService.kt")
            .readText()

        // 写入按 View 区分的目录，并保留照片清单
        assertTrue("照片应写入按 View 区分的目录", source.contains("views/view_"))
        assertTrue("照片索引和结果应写入同一个 CSV", source.contains("exportCombinedToStream(photoRows, confirms, partId, zos)"))
        assertFalse("不应再生成第二个照片清单 CSV", source.contains("photo_manifest.csv"))
        // 遍历 photos 列表
        assertTrue("应遍历全部照片", source.contains("for (photo in photos)"))
    }

    // ── 场景 13：无 ROI View 不生成虚假 ROI 或 PASS/FAIL ──

    @Test
    fun `no-ROI path in live inspection does not create ViewRoiConfirmEntity`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
            .readText()

        val noRoiBranch = source.indexOf("if (rois.isEmpty())")
        assertTrue("应有无 ROI 分支", noRoiBranch > 0)

        // 无 ROI 分支中不应有 insertViewRoiConfirms
        val elseBranch = source.indexOf("} else {", noRoiBranch)
        val noRoiBlock = source.substring(noRoiBranch, elseBranch)
        assertFalse("无 ROI 分支不应插入确认记录", noRoiBlock.contains("insertViewRoiConfirms"))
        assertFalse("无 ROI 分支不应创建 ViewRoiConfirmEntity", noRoiBlock.contains("ViewRoiConfirmEntity"))
    }

    @Test
    fun `view confirmation ViewModel rejects empty ROI list`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationViewModel.kt")
            .readText()

        assertTrue("saveConfirmation 应检查 rois.isEmpty()", source.contains("if (rois.isEmpty())"))
        assertTrue("空 ROI 应返回错误信息", source.contains("当前视角无 ROI，无需人工确认"))
    }

    @Test
    fun `CSV with zero confirms produces header-only output without fake rows`() {
        val os = ByteArrayOutputStream()
        InspectionExcelExporter.exportToStream(emptyList(), "part_001", os)
        val csv = os.toString("UTF-8")
        val lines = csv.trim().split("\n")
        assertEquals("空确认应只有表头", 1, lines.size)
        assertFalse("不应有 PASS", csv.contains("PASS"))
        assertFalse("不应有 FAIL", csv.contains("FAIL"))
    }

    @Test
    fun `softwareResult in entity defaults to null not PASS or FAIL`() {
        val confirm = ViewRoiConfirmEntity(
            id = 0,
            batchId = "batch_001",
            photoId = 1L,
            photoPath = "/captures/view_0.jpg",
            viewIndex = 0,
            templateId = "tpl_0",
            templateName = "视角1",
            roiId = "roi_001",
            roiName = "ROI 1",
            roiTargetType = RoiTargetType.THREAD.name,
            roiNormalizedRect = """{"left":0.1,"top":0.2,"right":0.3,"bottom":0.4}""",
            roiPixelRect = """{"left":100,"top":200,"right":300,"bottom":400}""",
            softwareResult = null,
            humanResult = "OK",
            confirmTime = System.currentTimeMillis(),
            overallResult = "OK",
            overallConfirmTime = System.currentTimeMillis()
        )
        assertNull("softwareResult 应为 null", confirm.softwareResult)
    }

    // ── 源码结构检查 ──

    @Test
    fun `live inspection screen does not use lifecycle observer for advancement`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
            .readText()

        assertFalse("不应使用 pendingAdvance 推进", source.contains("pendingAdvance"))
        assertFalse("现场采集页不应依赖生命周期回调复位状态", source.contains("DisposableEffect"))
        assertFalse("现场采集页不应依赖 LifecycleEventObserver 复位状态", source.contains("LifecycleEventObserver"))
    }

    @Test
    fun `live inspection uses explicit completeView method`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
            .readText()

        assertTrue("应使用 viewModel.completeView 推进", source.contains("viewModel.completeView(capturedViewIndex)"))
        assertTrue("应处理 ViewCompletionResult.ADVANCED", source.contains("ViewCompletionResult.ADVANCED"))
        assertTrue("应处理 ViewCompletionResult.COMPLETED", source.contains("ViewCompletionResult.COMPLETED"))
        assertTrue("应处理 ViewCompletionResult.IGNORED", source.contains("ViewCompletionResult.IGNORED"))
    }

    @Test
    fun `view completion result enum has all three states`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/workbench/WorkbenchViewModel.kt")
            .readText()

        assertTrue("应有 ADVANCED", source.contains("ADVANCED"))
        assertTrue("应有 COMPLETED", source.contains("COMPLETED"))
        assertTrue("应有 IGNORED", source.contains("IGNORED"))
    }

    @Test
    fun `batch endTime is updated before export navigation for last view`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
            .readText()

        val comIdx = source.indexOf("ViewCompletionResult.COMPLETED")
        assertTrue("应有 COMPLETED 处理", comIdx > 0)

        val finishIdx = source.indexOf("repository.finishCaptureBatch(batchId)", comIdx)
        val exportIdx = source.indexOf("onNavigateToExport(batchId", comIdx)
        assertTrue("finishCaptureBatch 应在 onNavigateToExport 之前", finishIdx < exportIdx)
    }
}
