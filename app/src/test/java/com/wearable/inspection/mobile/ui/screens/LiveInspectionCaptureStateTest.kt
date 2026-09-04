package com.wearable.inspection.mobile.ui.screens

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * LiveInspectionScreen 拍照状态与提示文案测试
 *
 * 覆盖：
 * - SAVED 状态提示文案为"照片已保存，进入人工确认"
 * - 旧文案"已保存，切换下一视角"不再出现
 * - SAVED 提示文案在源码中只出现一次（单一位置，不重复）
 * - SAVED 提示使用 maxLines=1 + TextOverflow.Ellipsis 防止裁切
 * - 拍摄中、保存失败、拍照失败和重试状态保留
 * - 有 ROI 导航前保留固定过渡状态，返回现场页时复位，不依赖生命周期回调
 */
class LiveInspectionCaptureStateTest {

    private fun readSource(): String {
        val path = File("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
        return path.readText()
    }

    @Test
    fun `SAVED state uses captureSavedMessage for display`() {
        val source = readSource()
        assertTrue(
            "SAVED 状态应使用 captureSavedMessage 变量",
            source.contains("text = captureSavedMessage")
        )
        assertTrue(
            "captureSavedMessage 默认值应为人工确认文案",
            source.contains("captureSavedMessage by remember { mutableStateOf(\"照片已保存，进入人工确认\") }")
        )
    }

    @Test
    fun `old misleading text about next view no longer exists`() {
        val source = readSource()
        assertFalse(
            "旧文案'已保存，切换下一视角'应已移除",
            source.contains("\"已保存，切换下一视角\"")
        )
    }

    @Test
    fun `captureSavedMessage set in both ROI and no-ROI paths`() {
        val source = readSource()
        val count = source.split("captureSavedMessage = ").size - 1
        assertTrue(
            "captureSavedMessage 应在有 ROI 和无 ROI 路径中各设置一次",
            count >= 3  // 声明 + 无 ROI 路径 + 有 ROI 路径
        )
    }

    @Test
    fun `saved text has overflow protection`() {
        val source = readSource()
        // SAVED 文案现在使用 captureSavedMessage 变量
        val savedTextIndex = source.indexOf("text = captureSavedMessage")
        assertTrue("SAVED 文案应使用 captureSavedMessage 变量", savedTextIndex > 0)

        val nearbyCode = source.substring(
            savedTextIndex,
            (savedTextIndex + 400).coerceAtMost(source.length)
        )
        assertTrue(
            "SAVED 提示应使用 maxLines = 1",
            nearbyCode.contains("maxLines = 1")
        )
        assertTrue(
            "SAVED 提示应使用 TextOverflow.Ellipsis",
            nearbyCode.contains("TextOverflow.Ellipsis")
        )
    }

    @Test
    fun `CAPTURING state text is preserved`() {
        val source = readSource()
        assertTrue(
            "应保留'拍摄中…'提示",
            source.contains("\"拍摄中…\"")
        )
    }

    @Test
    fun `ERROR state text is preserved`() {
        val source = readSource()
        assertTrue(
            "应保留错误状态文案",
            source.contains("\"拍照失败\"") || source.contains("captureError")
        )
        assertTrue(
            "应保留重试按钮",
            source.contains("\"重试\"")
        )
    }

    @Test
    fun `capture flow does not use lifecycle based implicit advancement`() {
        val source = readSource()
        assertTrue(
            "不应再用 pendingAdvance 隐式推进",
            !source.contains("pendingAdvance")
        )
        assertFalse("不应再用 readyToExport 延迟导航", source.contains("readyToExport"))
    }

    @Test
    fun `capture state reset does not depend on lifecycle callback`() {
        val source = readSource()
        assertFalse("不应依赖 DisposableEffect 重置拍照状态", source.contains("DisposableEffect"))
        assertFalse("不应依赖 LifecycleEventObserver 重置拍照状态", source.contains("LifecycleEventObserver"))
        assertFalse("不应保留 needsCaptureReset 生命周期状态", source.contains("needsCaptureReset"))
        assertFalse("不应使用 pendingAdvance", source.contains("pendingAdvance"))
    }

    @Test
    fun `roi path hides capture action bar before navigating to confirmation`() {
        val source = readSource()
        val navigateIdx = source.indexOf("onNavigateToConfirm(")
        assertTrue("应有 onNavigateToConfirm 调用", navigateIdx > 0)
        val roiMessageIdx = source.indexOf("captureSavedMessage = \"照片已保存，进入人工确认\"")
        val reservedIdx = source.indexOf("captureState = CaptureUiState.SAVED", roiMessageIdx)
        assertTrue("有 ROI 导航前应保留过渡状态", reservedIdx > roiMessageIdx)
        assertTrue("过渡状态应在 onNavigateToConfirm 之前", reservedIdx < navigateIdx)
        assertTrue("返回现场页应由可见状态统一复位", source.contains("LaunchedEffect(isScreenVisible)"))
        assertTrue(
            "SAVED 过渡状态不能继续渲染现场页拍照栏",
            source.contains("if (isScreenVisible && !captureNavigationPending && captureState != CaptureUiState.SAVED)")
        )
    }

    @Test
    fun `capture action bar is owned by the live screen scaffold bottom bar`() {
        val source = readSource()
        val scaffoldBottomBar = source.indexOf("bottomBar = {")
        val actionBarCall = source.indexOf("CaptureActionBar(", scaffoldBottomBar)
        assertTrue("现场页应使用 Scaffold bottomBar", scaffoldBottomBar > 0)
        assertTrue("拍照栏应位于 Scaffold bottomBar 内", actionBarCall > scaffoldBottomBar)
        assertEquals("拍照栏只能有一个调用点和一个定义", 2, source.split("CaptureActionBar(").size - 1)
    }

    @Test
    fun `live screen removes capture action bar during route transition`() {
        val source = readSource()
        assertTrue(
            "现场页离开或进入确认页时应立即移除拍照操作栏",
            source.contains("if (isScreenVisible && !captureNavigationPending && captureState != CaptureUiState.SAVED)")
        )
    }

    @Test
    fun `camera callbacks cannot reset a pending confirmation navigation`() {
        val source = readSource()
        assertTrue("有 ROI 导航前应锁定现场操作栏", source.contains("captureNavigationPending = true"))
        assertTrue(
            "相机回调不能在导航过渡期间重置拍照状态",
            source.contains("if (!captureNavigationPending)") &&
                source.contains("else if (!captureNavigationPending && captureState != CaptureUiState.CAPTURING)")
        )
        assertTrue("返回现场页时应清除导航过渡锁", source.contains("captureNavigationPending = false"))
    }

    @Test
    fun `saved confirmation message is hidden during roi navigation transition`() {
        val source = readSource()
        assertTrue(
            "现场参考区应接收 ROI 导航过渡状态",
            source.contains("captureNavigationPending = captureNavigationPending")
        )
        assertTrue(
            "拍照保存文案不能在导航到 ROI 确认页期间显示",
            source.contains("if (!allViewsCaptured && !captureNavigationPending)")
        )
    }

    @Test
    fun `multi-view reference does not repeat the template name below the image`() {
        val source = readSource()
        assertTrue("多视角应由顶部切换器显示名称", source.contains("showName = templates.size <= 1"))
        assertTrue("模板名称显示应受 showName 控制", source.contains("if (showName)"))
    }

    @Test
    fun `captured image file processing does not block the main thread`() {
        val source = readSource()
        assertTrue(
            "大图校验和原子落盘应在 IO 调度器执行",
            source.contains("withContext(Dispatchers.IO) {\n                            imageStore.storeCapturedImage(file)\n                        }")
        )
        assertTrue(
            "拍照应直接写入受管理 captures 临时路径，避免再次跨目录复制大 JPEG",
            source.contains("val captureFile = imageStore.generateCaptureFile()") &&
                source.contains("cameraController.takePhoto(currentSessionId, captureFile)")
        )
    }

    @Test
    fun `captured photo id is read back and association is validated`() {
        val source = readSource()
        assertTrue(
            "应取得 Room 返回的真实 photoId",
            source.contains("val photoId = repository.insertCapturedPhoto(capturedPhoto)")
        )
        val insertIndex = source.indexOf("val photoId = repository.insertCapturedPhoto(capturedPhoto)")
        assertTrue(
            "应按 photoId 回读照片记录",
            source.indexOf("repository.getCapturedPhoto(photoId)", insertIndex) > insertIndex
        )
        assertTrue("应校验照片 batchId/viewIndex/templateId",
            source.contains("persistedPhoto.batchId == batchId") &&
                source.contains("persistedPhoto.viewIndex == capturedViewIndex") &&
                source.contains("persistedPhoto.templateId == capturedTemplateId"))
    }

    @Test
    fun `capture completion uses one explicit view completion method`() {
        val source = readSource()
        val advanceCall = source.indexOf("viewModel.completeView(capturedViewIndex)")
        assertTrue("拍照完成后应调用按 viewIndex 校验的推进方法", advanceCall > 0)
        val insertCall = source.indexOf("val photoId = repository.insertCapturedPhoto(capturedPhoto)")
        assertTrue(
            "推进必须在真实照片插入之后",
            advanceCall > insertCall
        )
    }

    @Test
    fun `capture failure cannot reach view completion`() {
        val source = readSource()
        val completion = source.indexOf("viewModel.completeView(capturedViewIndex)")
        val failure = source.indexOf("onFailure = {")
        assertTrue("应存在拍照失败分支", failure > 0)
        assertTrue("推进应位于成功保存分支之后", completion > source.indexOf("if (storeResult != null)"))
        assertFalse("拍照失败处理不应调用推进", source.substring(failure).contains("completeView"))
    }

    @Test
    fun `photo view index and template id come from the same capture snapshot`() {
        val source = readSource()
        assertTrue(source.contains("viewIndex = capturedViewIndex"))
        assertTrue(source.contains("templateId = capturedTemplateId"))
        assertTrue(source.contains("capturedTemplateId = template.id"))
        assertTrue(source.contains("viewModel.completeView(capturedViewIndex)"))
    }

    // ── 无 ROI 直接推进流程测试 ──

    @Test
    fun capture_branchesByRoiCount_onCaptureChecksRoisEmpty() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
            .readText()

        val roiBranch = source.indexOf("val rois = repository.getRois(capturedTemplateId).filter { it.enabled }")
        assertTrue("onCapture 应按当前 templateId 查询 enabled ROI", roiBranch > 0)

        // rois.isEmpty() 检查应在 insertCapturedPhoto 之后（拍照保存后分支）
        val insertCall = source.indexOf("val photoId = repository.insertCapturedPhoto(capturedPhoto)")
        assertTrue("insertCapturedPhoto 应在 onCapture 中", insertCall > 0)
        assertTrue("rois.isEmpty() 检查应在 insertCapturedPhoto 之后", roiBranch > insertCall)
        assertTrue("应按查询结果判断无 ROI", source.indexOf("if (rois.isEmpty())", roiBranch) > roiBranch)
    }

    @Test
    fun capture_noRoiPath_callsAdvanceDirectly() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
            .readText()

        val roiBranch = source.indexOf("if (rois.isEmpty())")
        val advanceAfterRoi = source.indexOf("viewModel.completeView(capturedViewIndex)", roiBranch)
        assertTrue("无 ROI 路径应直接完成当前 View", advanceAfterRoi > roiBranch)
        val confirmCall = source.indexOf("onNavigateToConfirm(", roiBranch)
        assertTrue("有 ROI 路径才导航确认页", confirmCall > source.indexOf("} else {", roiBranch))
    }

    @Test
    fun capture_noRoiLastView_finishesBatchAndNavigatesToExport() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
            .readText()

        val completed = source.indexOf("ViewCompletionResult.COMPLETED")
        assertTrue("最后一个无 ROI View 应有 COMPLETED 分支", completed > 0)
        val finish = source.indexOf("repository.finishCaptureBatch(batchId)", completed)
        val exportNav = source.indexOf("onNavigateToExport(batchId, part.id, part.name)", completed)
        assertTrue("应先更新批次结束时间", finish > completed)
        assertTrue("批次结束时间更新后才导航导出页", exportNav > finish)
    }

    @Test
    fun capture_onNavigateToExport_callbackParameterExists() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
            .readText()

        assertTrue(
            "LiveInspectionScreen 应有 onNavigateToExport 参数",
            source.contains("onNavigateToExport:")
        )
        assertTrue(
            "onNavigateToExport 应有默认空实现",
            source.contains("onNavigateToExport: (batchId: String, partId: String, partName: String) -> Unit = { _, _, _ -> }")
        )
    }

    @Test
    fun capture_savedText_has_no_misleading_view_switch_message() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
            .readText()

        assertTrue(
            "有 ROI 时应显示人工确认文案",
            source.contains("照片已保存，进入人工确认")
        )
        assertTrue(
            "无 ROI 时应显示进入下一视角文案",
            source.contains("照片已保存，进入下一视角")
        )
        assertFalse("不应显示旧的误导文案'已保存，切换下一视角'", source.contains("已保存，切换下一视角"))
    }

    @Test
    fun capture_templateReferenceSection_keeps_original_layout_contract() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
            .readText()

        val sectionDecl = source.indexOf("private fun TemplateReferenceSection")
        assertTrue("TemplateReferenceSection 应存在", sectionDecl > 0)
        assertTrue("应接受 captureSavedMessage 参数", source.contains("captureSavedMessage: String"))
        assertTrue("应保留实时预览布局", source.contains("CameraPreviewSection("))
        assertTrue("应保留透明度控制栏", source.contains("TemplateOverlayControls("))
    }

    @Test
    fun capture_noRoi_uses_capturedViewIndex_for_sequential_advance() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
            .readText()

        // 无 ROI 路径使用 capturedViewIndex（拍摄时快照）而非当前 viewIndex
        val completeCall = source.indexOf("viewModel.completeView(capturedViewIndex)")
        assertTrue("应使用 capturedViewIndex 调用 completeView", completeCall > 0)

        // capturedViewIndex 应在拍照开始时从 stateAtCapture 快照取得
        val snapshotLine = source.indexOf("val capturedViewIndex = stateAtCapture.currentViewIndex")
        assertTrue("capturedViewIndex 应从拍摄时快照取得", snapshotLine > 0)
        assertTrue("completeView 应在快照之后", completeCall > snapshotLine)
    }

    @Test
    fun capture_noRoi_does_not_insert_roi_confirm_records() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
            .readText()

        val roiBranch = source.indexOf("if (rois.isEmpty())")
        val elseBranch = source.indexOf("} else {", roiBranch)

        // 无 ROI 分支不应调用 insertViewRoiConfirms
        val noRoiBlock = source.substring(roiBranch, elseBranch)
        assertFalse(
            "无 ROI 路径不应插入 ROI 确认记录",
            noRoiBlock.contains("insertViewRoiConfirms")
        )
        assertFalse(
            "无 ROI 路径不应创建 ViewRoiConfirmEntity",
            noRoiBlock.contains("ViewRoiConfirmEntity")
        )
    }

    @Test
    fun capture_roi_path_inserts_confirms_only_after_navigation() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
            .readText()

        // 有 ROI 路径应导航到确认页，确认页 ViewModel 负责保存
        val elseBranch = source.indexOf("} else {", source.indexOf("if (rois.isEmpty())"))
        val confirmNav = source.indexOf("onNavigateToConfirm(", elseBranch)
        assertTrue("有 ROI 路径应导航确认页", confirmNav > elseBranch)

        // 确认页内的保存逻辑在 ViewConfirmationViewModel 中，不在 LiveInspectionScreen
        val roiBlock = source.substring(elseBranch, (elseBranch + 800).coerceAtMost(source.length))
        assertFalse(
            "有 ROI 路径不应在拍照时直接插入确认记录",
            roiBlock.contains("insertViewRoiConfirms")
        )
    }

    @Test
    fun capture_batch_part_association_is_validated() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
            .readText()

        // 批次应校验属于当前零件
        assertTrue("应校验批次零件一致性",
            source.contains("it.partId == part.id"))
        // 批次应校验未结束
        assertTrue("应校验批次未结束",
            source.contains("it.endTime == null"))
    }

    @Test
    fun `template reference image fills its slot and decodes off the main thread`() {
        val source = readSource()
        val cardStart = source.indexOf("// 全宽参考图：保留完整画面")
        assertTrue("模板参考图卡片应存在", cardStart > 0)
        val cardBlock = source.substring(cardStart, (cardStart + 1800).coerceAtMost(source.length))
        assertTrue("模板图应填满参考区域，不应因 maxHeight 留大块空白", cardBlock.contains(".weight(1f)"))
        assertTrue("模板图解码应在 IO 调度器执行", cardBlock.contains("withContext(Dispatchers.IO)"))
    }
}
