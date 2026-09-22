package com.wearable.inspection.mobile.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 拍后比对自动导航回归测试（源码结构测试）。
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
 * - registration 完成后才执行 SessionRoiRegistry.write()
 * - write 使用 projectedRoisSnapshot
 * - 写入后才导航 ViewConfirmation
 * - 传递真实 isFullImageFallback
 * - popUpTo CaptureComparison 使用 inclusive=true
 * - 拍照后不渲染可见的 CaptureComparison UI（自动导航到 ViewConfirmation）
 * - 成功路径仍写入 projected ROI
 * - 失败/fallback 路径仍进入整图兜底
 * - 不回退到模板 normalizedRect
 * - isFullImageFallback 传递到 ViewConfirmation
 * - 不保留拍照后 ROI 拖动/缩放/四角调整入口
 */
class CaptureComparisonAutoNavigationTest {

    private fun navSource(): String =
        File("src/main/java/com/wearable/inspection/mobile/ui/navigation/AppNavigation.kt").readText()

    private fun vmSource(): String =
        File("src/main/java/com/wearable/inspection/mobile/ui/screens/CaptureComparisonViewModel.kt").readText()

    private fun screenSource(): String =
        File("src/main/java/com/wearable/inspection/mobile/ui/screens/CaptureComparisonScreen.kt").readText()

    // ── 自动导航：CaptureComparison 不渲染可见 UI ──

    @Test
    fun `CaptureComparison composable auto-navigates on isLoaded`() {
        val source = navSource()
        // 应有 LaunchedEffect 等待 comparisonViewModel.isLoaded
        assertTrue(
            "应有 LaunchedEffect 监听 isLoaded",
            source.contains("LaunchedEffect(comparisonViewModel.isLoaded)")
        )
    }

    @Test
    fun `CaptureComparison composable does not render CaptureComparisonScreen`() {
        val source = navSource()
        // 在 CaptureComparison 路由块中不应直接调用 CaptureComparisonScreen
        val routeBlock = extractCaptureComparisonBlock(source)
        assertFalse(
            "CaptureComparison 路由不应渲染 CaptureComparisonScreen",
            routeBlock.contains("CaptureComparisonScreen(")
        )
    }

    @Test
    fun `CaptureComparison composable shows only loading indicator`() {
        val source = navSource()
        val routeBlock = extractCaptureComparisonBlock(source)
        assertTrue(
            "应显示 CircularProgressIndicator",
            routeBlock.contains("CircularProgressIndicator")
        )
    }

    // ── SessionRoiRegistry 写入 ──

    @Test
    fun `auto-navigation writes to SessionRoiRegistry before navigating`() {
        val source = navSource()
        val routeBlock = extractCaptureComparisonBlock(source)
        assertTrue(
            "应调用 SessionRoiRegistry.write",
            routeBlock.contains("SessionRoiRegistry.write")
        )
    }

    @Test
    fun `auto-navigation writes projectedRoisSnapshot to registry`() {
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

    // ── 不保留 ROI 拖动/缩放/四角调整 ──

    @Test
    fun `auto-navigation path has no drag or resize entry points`() {
        val source = navSource()
        val routeBlock = extractCaptureComparisonBlock(source)
        assertFalse(
            "自动导航路径不应有拖动手势",
            routeBlock.contains("detectDragGestures")
        )
        assertFalse(
            "自动导航路径不应有 Slider",
            routeBlock.contains("Slider")
        )
    }

    // ── 调用顺序验证 ──

    @Test
    fun `SessionRoiRegistry write appears before navigate in LaunchedEffect`() {
        val source = navSource()
        val routeBlock = extractCaptureComparisonBlock(source)
        val writeIdx = routeBlock.indexOf("SessionRoiRegistry.write")
        val navigateIdx = routeBlock.indexOf("navController.navigate")
        assertTrue("LaunchedEffect 内应有 write", writeIdx > 0)
        assertTrue("LaunchedEffect 内应有 navigate", navigateIdx > 0)
        assertTrue(
            "SessionRoiRegistry.write 必须在 navController.navigate 之前",
            writeIdx < navigateIdx
        )
    }

    @Test
    fun `LaunchedEffect guards with isLoaded before executing`() {
        val source = navSource()
        val routeBlock = extractCaptureComparisonBlock(source)
        val guardIdx = routeBlock.indexOf("if (!comparisonViewModel.isLoaded)")
        val writeIdx = routeBlock.indexOf("SessionRoiRegistry.write")
        assertTrue("应有 isLoaded 守卫", guardIdx > 0)
        assertTrue(
            "isLoaded 守卫必须在 write 之前",
            guardIdx < writeIdx
        )
    }

    @Test
    fun `registration result read appears before write`() {
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
