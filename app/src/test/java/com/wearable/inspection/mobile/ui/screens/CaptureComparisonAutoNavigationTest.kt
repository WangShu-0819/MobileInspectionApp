package com.wearable.inspection.mobile.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 拍后只读对齐页面回归测试（源码结构测试）。
 *
 * 验证边界说明：
 * 本测试套件为源码结构测试（source-code structure tests），通过读取 Kotlin 源文件并验证关键模式的存在性、
 * 不存在性和顺序关系来保证回归安全。这些测试验证的是**代码结构**，而非运行时行为：
 * - ✅ 能验证：关键 API 调用存在、方法调用顺序、控制流路径、文件存在性
 * - ❌ 不能验证：NavController.navigate() 实际执行、ViewModel 运行时状态、Compose 重组行为
 *
 * 运行时行为验证（如 NavController 调用序列、LaunchedEffect 触发时机）需要 instrumented 测试。
 *
 * 验证：
 * - 拍照后渲染 CaptureComparisonScreen（readOnly=true 仅标记语义，不隐藏控件）
 * - ComparisonToolbar 不被 readOnly 条件隐藏（现场/模板/叠加、blink、透明度、缩放均保留）
 * - 视图手势（拖拽平移）不被 readOnly 条件禁用
 * - AppNavigation 层不包含 detectDragGestures/Slider（由 Screen 内部管理）
 * - onProceed 回调中写入 SessionRoiRegistry
 * - write 使用 projectedRoisSnapshot
 * - 写入后才导航 ViewConfirmation
 * - 传递真实 isFullImageFallback
 * - popUpTo CaptureComparison 使用 inclusive=true
 * - 成功路径仍写入 projected ROI
 * - 失败/fallback 路径仍进入整图兜底
 * - 不回退到模板 normalizedRect
 * - isFullImageFallback 传递到 ViewConfirmation
 */
class CaptureComparisonAutoNavigationTest {

    private fun navSource(): String =
        File("src/main/java/com/wearable/inspection/mobile/ui/navigation/AppNavigation.kt").readText()

    private fun vmSource(): String =
        File("src/main/java/com/wearable/inspection/mobile/ui/screens/CaptureComparisonViewModel.kt").readText()

    private fun screenSource(): String =
        File("src/main/java/com/wearable/inspection/mobile/ui/screens/CaptureComparisonScreen.kt").readText()

    // ── 只读对齐页面：CaptureComparison 渲染 CaptureComparisonScreen ──

    @Test
    fun `CaptureComparison composable renders CaptureComparisonScreen in readOnly mode`() {
        val source = navSource()
        val routeBlock = extractCaptureComparisonBlock(source)
        assertTrue(
            "CaptureComparison 路由应渲染 CaptureComparisonScreen",
            routeBlock.contains("CaptureComparisonScreen(")
        )
        assertTrue(
            "应传入 readOnly = true",
            routeBlock.contains("readOnly = true")
        )
    }

    @Test
    fun `CaptureComparison composable does not auto-navigate on isLoaded`() {
        val source = navSource()
        // 不应有自动导航的 LaunchedEffect
        assertFalse(
            "不应有 LaunchedEffect(comparisonViewModel.isLoaded) 自动导航",
            source.contains("LaunchedEffect(comparisonViewModel.isLoaded)")
        )
    }

    @Test
    fun `CaptureComparison composable does not show loading indicator instead of screen`() {
        val source = navSource()
        val routeBlock = extractCaptureComparisonBlock(source)
        // 不应有独立的 CircularProgressIndicator（加载状态由 CaptureComparisonScreen 内部处理）
        assertFalse(
            "不应有独立的 CircularProgressIndicator 占位",
            routeBlock.contains("CircularProgressIndicator")
        )
    }

    // ── SessionRoiRegistry 写入（onProceed 回调中） ──

    @Test
    fun `onProceed writes to SessionRoiRegistry before navigating`() {
        val source = navSource()
        val routeBlock = extractCaptureComparisonBlock(source)
        assertTrue(
            "onProceed 回调中应调用 SessionRoiRegistry.write",
            routeBlock.contains("SessionRoiRegistry.write")
        )
    }

    @Test
    fun `onProceed writes projectedRoisSnapshot to registry`() {
        val source = navSource()
        val routeBlock = extractCaptureComparisonBlock(source)
        assertTrue(
            "应写入 projectedRoisSnapshot",
            routeBlock.contains("projectedRoisSnapshot")
        )
    }

    // ── 成功路径写入投影 ROI ──

    @Test
    fun `success path still uses projected ROI from registration`() {
        val source = vmSource()
        // projectedRoisSnapshot 赋值来自 sessionRois（配准引擎产出）
        assertTrue(
            "projectedRoisSnapshot 应来自配准结果",
            source.contains("projectedRoisSnapshot = sessionRects")
        )
    }

    @Test
    fun `buildSessionRois returns empty list on FALLBACK`() {
        val source = vmSource()
        assertTrue(
            "FALLBACK_FULL_IMAGE 时应返回空列表",
            source.contains("RegistrationStatus.FALLBACK_FULL_IMAGE")
        )
    }

    @Test
    fun `auto-navigation defaults to FAILED when registration status is null`() {
        val source = navSource()
        assertTrue(
            "regStatus 为 null 时应默认 RegistrationStatus.FAILED",
            source.contains("RegistrationStatus.FAILED")
        )
    }

    @Test
    fun `buildSessionRois does not fall back to template normalizedRect`() {
        val source = vmSource()
        // 投影失败时整体丢弃，不回退原始模板坐标
        assertTrue(
            "任一 ROI 投影失败应返回空列表",
            source.contains("return emptyList()")
        )
        assertFalse(
            "不应使用原始 normalizedRect 作为回退",
            source.contains("normalizedRect") && source.contains("fallback")
        )
    }

    // ── isFullImageFallback 传递 ──

    @Test
    fun `auto-navigation passes isFullImageFallback to ViewConfirmation`() {
        val source = navSource()
        val routeBlock = extractCaptureComparisonBlock(source)
        assertTrue(
            "应传递 isFullImageFallback",
            routeBlock.contains("isFullImageFallback")
        )
        assertTrue(
            "应导航到 ViewConfirmation",
            routeBlock.contains("Screen.ViewConfirmation.createRoute")
        )
    }

    @Test
    fun `isFullImageFallback is true when registration fails or has no projected ROIs`() {
        val source = vmSource()
        assertTrue(
            "registrationResult 为 null 时应 fallback",
            source.contains("registrationResult == null")
        )
        assertTrue(
            "status 非 SUCCESS 时应 fallback",
            source.contains("registrationResult?.status != RegistrationStatus.SUCCESS")
        )
        assertTrue(
            "projectedRoisSnapshot 为空时应 fallback",
            source.contains("projectedRoisSnapshot.isEmpty()")
        )
    }

    // ── 只读门控：仅门控 ROI 编辑，保留查看控件 ──

    @Test
    fun `AppNavigation does not embed gesture code in route block`() {
        val source = navSource()
        val routeBlock = extractCaptureComparisonBlock(source)
        // AppNavigation 层面不应直接包含手势代码（由 CaptureComparisonScreen 内部管理）
        assertFalse(
            "AppNavigation 路由块不应包含 detectDragGestures",
            routeBlock.contains("detectDragGestures")
        )
        assertFalse(
            "AppNavigation 路由块不应包含 Slider",
            routeBlock.contains("Slider")
        )
    }

    @Test
    fun `ComparisonToolbar is not gated by readOnly in CaptureComparisonScreen`() {
        val source = screenSource()
        // ComparisonToolbar 应始终渲染，不被 readOnly 条件隐藏
        assertFalse(
            "不应有 if (!readOnly) 隐藏 ComparisonToolbar",
            source.contains("if (!readOnly)")
        )
        assertTrue(
            "应直接调用 ComparisonToolbar（无条件守卫）",
            source.contains("ComparisonToolbar(")
        )
    }

    @Test
    fun `viewport gestures are not gated by readOnly in CaptureComparisonScreen`() {
        val source = screenSource()
        // 视图拖拽平移手势应始终启用，不被 readOnly 条件禁用
        assertFalse(
            "不应有 if (readOnly) Modifier else Modifier.pointerInput 条件",
            source.contains("if (readOnly) Modifier else Modifier.pointerInput")
        )
        assertTrue(
            "ComparisonViewport 应包含 detectDragGestures 手势",
            source.contains("detectDragGestures")
        )
    }

    // ── 调用顺序验证 ──

    @Test
    fun `SessionRoiRegistry write appears before navigate in onProceed`() {
        val source = navSource()
        val routeBlock = extractCaptureComparisonBlock(source)
        val writeIdx = routeBlock.indexOf("SessionRoiRegistry.write")
        val navigateIdx = routeBlock.indexOf("Screen.ViewConfirmation.createRoute")
        assertTrue("onProceed 内应有 write", writeIdx > 0)
        assertTrue("onProceed 内应有 navigate", navigateIdx > 0)
        assertTrue(
            "SessionRoiRegistry.write 必须在 ViewConfirmation.createRoute 之前",
            writeIdx < navigateIdx
        )
    }

    @Test
    fun `registration result read appears before write in onProceed`() {
        val source = navSource()
        val routeBlock = extractCaptureComparisonBlock(source)
        val regStatusIdx = routeBlock.indexOf("registrationResult?.status")
        val writeIdx = routeBlock.indexOf("SessionRoiRegistry.write")
        assertTrue("应读取 registrationResult", regStatusIdx > 0)
        assertTrue(
            "registrationResult 读取必须在 write 之前",
            regStatusIdx < writeIdx
        )
    }

    @Test
    fun `popUpTo CaptureComparison uses inclusive true`() {
        val source = navSource()
        val routeBlock = extractCaptureComparisonBlock(source)
        assertTrue(
            "popUpTo 应使用 inclusive = true",
            routeBlock.contains("popUpTo(Screen.CaptureComparison.route)") &&
                routeBlock.contains("inclusive = true")
        )
    }

    // ── CaptureComparison 路由和 ViewModel 保留 ──

    @Test
    fun `Screen_CaptureComparison route still exists`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/navigation/Screen.kt").readText()
        assertTrue("Screen.CaptureComparison 应保留", source.contains("CaptureComparison"))
    }

    @Test
    fun `CaptureComparisonViewModel is not deleted`() {
        assertTrue(
            "CaptureComparisonViewModel 文件应存在",
            File("src/main/java/com/wearable/inspection/mobile/ui/screens/CaptureComparisonViewModel.kt").exists()
        )
    }

    @Test
    fun `CaptureComparisonScreen is not deleted`() {
        assertTrue(
            "CaptureComparisonScreen 文件应存在",
            File("src/main/java/com/wearable/inspection/mobile/ui/screens/CaptureComparisonScreen.kt").exists()
        )
    }

    @Test
    fun `SessionRoiRegistry is not deleted`() {
        assertTrue(
            "SessionRoiRegistry 文件应存在",
            File("src/main/java/com/wearable/inspection/mobile/ui/screens/SessionRoiRegistry.kt").exists()
        )
    }

    // ── 辅助方法 ──

    /**
     * 提取 AppNavigation.kt 中 CaptureComparison 路由块的完整内容。
     * 使用花括号匹配找到 composable(Screen.CaptureComparison.route) 的完整块。
     */
    private fun extractCaptureComparisonBlock(source: String): String {
        val startMarker = "Screen.CaptureComparison.route"
        val startIdx = source.indexOf(startMarker)
        assertTrue("应找到 CaptureComparison 路由", startIdx > 0)
        // backStackEntry 是 composable body lambda 的参数，位于正确的 `{` 之后
        val bodyAnchor = source.indexOf("backStackEntry", startIdx)
        assertTrue("应找到 backStackEntry", bodyAnchor > startIdx)
        // lastIndexOf 找到同一行的 `{`（`{ backStackEntry ->`），而非 navArgument 的内嵌 `{`
        val openBrace = source.lastIndexOf("{", bodyAnchor)
        assertTrue("应找到开括号", openBrace > startIdx)
        var depth = 0
        for (i in openBrace until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(openBrace, i + 1)
                    }
                }
            }
        }
        org.junit.Assert.fail("未找到 CaptureComparison 路由块的闭合括号")
        error("unreachable")
    }
}
