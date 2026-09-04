package com.wearable.inspection.mobile.ui.navigation

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * ViewConfirmation 导航回调逻辑源码级测试
 *
 * 覆盖：
 * - 非最后一个 View 确认后：popBackStack 返回采集页
 * - 最后一个 View 确认后：navigate 到 ExportResult
 * - onBack 只做 popBackStack（取消不推进）
 * - ExportResult 路由存在且参数正确
 * - ViewConfirmation 路由参数完整
 */
class ViewConfirmationNavigationTest {

    private fun readAppNavigation(): String {
        val path = File("src/main/java/com/wearable/inspection/mobile/ui/navigation/AppNavigation.kt")
        return path.readText()
    }

    private fun readScreen(): String {
        val path = File("src/main/java/com/wearable/inspection/mobile/ui/navigation/Screen.kt")
        return path.readText()
    }

    private fun getViewConfirmBlock(): String {
        val source = readAppNavigation()
        val start = source.indexOf("onConfirmed = {")
        assertTrue("onConfirmed 回调应存在", start > 0)
        return source.substring(start, (start + 1600).coerceAtMost(source.length))
    }

    @Test
    fun `non-last view confirmation pops back stack`() {
        val block = getViewConfirmBlock()
        assertTrue(
            "非最后一个 View 应执行 popBackStack()",
            block.contains("popBackStack()")
        )
    }

    @Test
    fun `last view confirmation navigates to ExportResult`() {
        val block = getViewConfirmBlock()
        assertTrue(
            "最后一个 View 应导航到 ExportResult",
            block.contains("Screen.ExportResult.createRoute")
        )
    }

    @Test
    fun `last view uses popUpTo LiveInspection to clear confirmation stack`() {
        val block = getViewConfirmBlock()
        assertTrue(
            "最后一个 View 导航应使用 popUpTo 清理返回栈",
            block.contains("popUpTo(Screen.LiveInspection.route)")
        )
    }

    @Test
    fun `onBack only pops back stack without advancing`() {
        val source = readAppNavigation()
        // 找到 ViewConfirmation composable 中的 onBack
        val viewConfirmStart = source.indexOf("route = Screen.ViewConfirmation.route")
        assertTrue("ViewConfirmation 路由应存在", viewConfirmStart > 0)

        // 使用足够大的窗口覆盖整个 composable 块（约70行，~3500字符）
        val block = source.substring(viewConfirmStart, (viewConfirmStart + 4000).coerceAtMost(source.length))
        val onBackIdx = block.indexOf("onBack = {")
        assertTrue("onBack 应存在", onBackIdx > 0)

        val onBackBlock = block.substring(onBackIdx, (onBackIdx + 100).coerceAtMost(block.length))
        assertFalse(
            "onBack 不应调用 advanceToNextView",
            onBackBlock.contains("advanceToNextView")
        )
        assertFalse(
            "onBack 不应导航到 ExportResult",
            onBackBlock.contains("ExportResult")
        )
        assertTrue(
            "onBack 应只执行 popBackStack",
            onBackBlock.contains("popBackStack()")
        )
    }

    @Test
    fun `ViewConfirmation route has all required arguments`() {
        val source = readScreen()
        val viewConfirmBlock = source.indexOf("object ViewConfirmation")
        assertTrue("ViewConfirmation 应存在", viewConfirmBlock > 0)

        val block = source.substring(viewConfirmBlock, (viewConfirmBlock + 1000).coerceAtMost(source.length))
        val requiredArgs = listOf(
            "ARG_BATCH_ID", "ARG_PHOTO_ID", "ARG_PHOTO_PATH",
            "ARG_VIEW_INDEX", "ARG_TEMPLATE_ID", "ARG_TEMPLATE_NAME",
            "ARG_PART_ID", "ARG_TOTAL_VIEWS"
        )
        for (arg in requiredArgs) {
            assertTrue("ViewConfirmation 应包含参数 $arg", block.contains(arg))
        }
    }

    @Test
    fun `ExportResult route exists with correct parameters`() {
        val source = readScreen()
        val exportBlock = source.indexOf("object ExportResult")
        assertTrue("ExportResult 路由应存在", exportBlock > 0)

        val block = source.substring(exportBlock, (exportBlock + 500).coerceAtMost(source.length))
        assertTrue("ExportResult 应包含 ARG_BATCH_ID", block.contains("ARG_BATCH_ID"))
        assertTrue("ExportResult 应包含 ARG_PART_ID", block.contains("ARG_PART_ID"))
        assertTrue("ExportResult 应包含 ARG_PART_NAME", block.contains("ARG_PART_NAME"))
    }

    @Test
    fun `confirmation completion explicitly uses the shared workbench and view index`() {
        val block = getViewConfirmBlock()
        assertTrue(
            "确认完成应使用共享 WorkbenchViewModel 按 viewIndex 推进",
            block.contains("workbenchViewModel.completeView(viewIndex)")
        )
    }

    @Test
    fun `live inspection receives the root scoped workbench view model`() {
        val source = readAppNavigation()
        val liveStart = source.indexOf("composable(Screen.LiveInspection.route)")
        val liveBlock = source.substring(liveStart, (liveStart + 1500).coerceAtMost(source.length))
        assertTrue("LiveInspection 应接收 AppRoot 共享的 WorkbenchViewModel",
            liveBlock.contains("viewModel = workbenchViewModel"))
    }

    @Test
    fun `capture confirmation and export routes explicitly hide the root bottom bar`() {
        val source = readAppNavigation()
        assertTrue("应按路由主键识别过渡页", source.contains("currentRoute?.substringBefore('/')"))
        assertTrue("应识别确认页为采集子流程", source.contains("routeKey == \"view_confirmation\""))
        assertTrue("应识别导出页为采集子流程", source.contains("routeKey == \"export_result\""))
        assertTrue("采集子流程不能显示根级底部导航", source.contains("!isCaptureSubFlow"))
    }

    @Test
    fun `capture subflows disable transitions while top-level pages keep fade transitions`() {
        val source = readAppNavigation()
        assertTrue("页面进入应保留轻量淡入", source.contains("else fadeIn(animationSpec = tween(180))"))
        assertTrue("页面离开应保留轻量淡出", source.contains("else fadeOut(animationSpec = tween(120))"))
        assertTrue("进入确认页应禁用过渡残影", source.contains("EnterTransition.None"))
        assertTrue("离开确认页应禁用过渡残影", source.contains("ExitTransition.None"))
        assertTrue("确认页应按 view_confirmation 路由识别", source.contains("startsWith(\"view_confirmation\")"))
        assertTrue("导出页应按 export_result 路由识别", source.contains("startsWith(\"export_result\")"))
    }

    @Test
    fun `reselecting the current bottom tab does not enqueue another navigation`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/navigation/BottomNavigationBar.kt")
            .readText()
        assertTrue(
            "重复点击当前 Tab 不应再次触发导航",
            source.contains("if (currentDestination?.route != item.route)")
        )
    }

    @Test
    fun `confirmation completion removes its action bar before navigation`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationScreen.kt")
            .readText()
        assertTrue(
            "确认完成事件处理后不应继续渲染确认栏",
            source.contains("!completionHandled.value")
        )
        assertTrue(
            "保存完成状态下不应继续渲染确认栏",
            source.contains("!saveCompleted && !completionHandled.value")
        )
    }

    @Test
    fun `confirmation screen consumes completion event only once`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationScreen.kt")
            .readText()
        assertTrue("确认完成事件应有一次性消费标记", source.contains("completionHandled"))
        assertTrue("应检查 saveCompleted 且未处理", source.contains("saveCompleted && !completionHandled.value"))
    }

    @Test
    fun `confirmation screen keeps its own back navigation and confirmation bar`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationScreen.kt")
            .readText()
        assertTrue("确认页应提供返回现场采集的入口", source.contains("contentDescription = \"返回现场采集\""))
        assertTrue("确认页应保留自己的底部确认栏", source.contains("BottomConfirmBar("))
        assertTrue("确认页不应嵌入现场拍照栏", !source.contains("CaptureActionBar("))
    }

    @Test
    fun `confirmation action keeps a stable label and fixed hint slot`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationScreen.kt")
            .readText()
        assertTrue("确认栏高度应固定", source.contains(".height(140.dp)"))
        assertTrue("提示槽高度应固定", source.contains(".height(18.dp)"))
        assertTrue("按钮文案应始终固定", source.contains("text = \"确认并继续\""))
        assertFalse("按钮文案不应随选择状态切换", source.contains("if (isAllConfirmed) \"确认并继续\""))
    }

    // ── 无 ROI 直接导出导航测试 ──

    @Test
    fun `LiveInspection receives onNavigateToExport callback`() {
        val source = readAppNavigation()
        val liveInspectionIdx = source.indexOf("Screen.LiveInspection.route")
        assertTrue("LiveInspection 路由应存在", liveInspectionIdx > 0)

        val block = source.substring(liveInspectionIdx, (liveInspectionIdx + 2000).coerceAtMost(source.length))
        assertTrue(
            "AppNavigation 应传递 onNavigateToExport 回调",
            block.contains("onNavigateToExport =")
        )
    }

    @Test
    fun `onNavigateToExport navigates to ExportResult with correct params`() {
        val source = readAppNavigation()
        val exportCallback = source.indexOf("onNavigateToExport =")
        assertTrue("onNavigateToExport 回调应存在", exportCallback > 0)

        val block = source.substring(exportCallback, (exportCallback + 400).coerceAtMost(source.length))
        assertTrue(
            "onNavigateToExport 应导航到 ExportResult",
            block.contains("Screen.ExportResult.createRoute")
        )
        assertTrue(
            "应传递 batchId",
            block.contains("batchId = batchId")
        )
        assertTrue(
            "应传递 partId",
            block.contains("partId = partId")
        )
        assertTrue(
            "应传递 partName",
            block.contains("partName = partName")
        )
    }

    @Test
    fun `no ROI path does not navigate to ViewConfirmation`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt").readText()
        val roiBranch = source.indexOf("val rois = repository.getRois(capturedTemplateId).filter { it.enabled }")
        assertTrue("应按当前 templateId 查询 ROI", roiBranch > 0)

        val elseBranch = source.indexOf("} else {", roiBranch)
        val confirmCall = source.indexOf("onNavigateToConfirm(", roiBranch)

        // onNavigateToConfirm 应在 else 分支之后（有 ROI 路径）
        assertTrue(
            "onNavigateToConfirm 应在有 ROI 分支中（else 之后）",
            confirmCall > elseBranch
        )
    }
}
