package com.wearable.inspection.mobile.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CameraPreview 辅助函数单元测试
 */
class CameraPreviewTest {

    private fun readSource(): String = File(
        "src/main/java/com/wearable/inspection/mobile/ui/screens/CameraPreview.kt"
    ).readText()

    @Test
    fun `8000x6000 图片计算 inSampleSize=4`() {
        assertEquals(4, calculateInSampleSize(8000, 6000))
    }

    @Test
    fun `6000x8000 纵向图片计算 inSampleSize=4`() {
        assertEquals(4, calculateInSampleSize(6000, 8000))
    }

    @Test
    fun `2048x2048 图片计算 inSampleSize=1`() {
        assertEquals(1, calculateInSampleSize(2048, 2048))
    }

    @Test
    fun `1920x1080 图片计算 inSampleSize=1`() {
        assertEquals(1, calculateInSampleSize(1920, 1080))
    }

    @Test
    fun `1080x1920 纵向图片计算 inSampleSize=1`() {
        assertEquals(1, calculateInSampleSize(1080, 1920))
    }

    @Test
    fun `4000x3000 图片计算 inSampleSize=2`() {
        assertEquals(2, calculateInSampleSize(4000, 3000))
    }

    @Test
    fun `3000x4000 纵向图片计算 inSampleSize=2`() {
        assertEquals(2, calculateInSampleSize(3000, 4000))
    }

    @Test
    fun `4096x4096 图片计算 inSampleSize=2`() {
        assertEquals(2, calculateInSampleSize(4096, 4096))
    }

    @Test
    fun `2049x2049 图片计算 inSampleSize=2`() {
        assertEquals(2, calculateInSampleSize(2049, 2049))
    }

    @Test
    fun `零宽度图片返回 inSampleSize=1`() {
        assertEquals(1, calculateInSampleSize(0, 6000))
    }

    @Test
    fun `零高度图片返回 inSampleSize=1`() {
        assertEquals(1, calculateInSampleSize(8000, 0))
    }

    @Test
    fun `负尺寸图片返回 inSampleSize=1`() {
        assertEquals(1, calculateInSampleSize(-1, -1))
    }

    @Test
    fun `自定义 maxTarget=1024 时 4000x3000 计算 inSampleSize=4`() {
        assertEquals(4, calculateInSampleSize(4000, 3000, maxTarget = 1024))
    }

    @Test
    fun `自定义 maxTarget=1024 时 2000x1500 计算 inSampleSize=2`() {
        assertEquals(2, calculateInSampleSize(2000, 1500, maxTarget = 1024))
    }

    @Test
    fun `active 状态控制连接和立即断开`() {
        val source = readSource()
        assertTrue("CameraPreview 应有 active 参数", source.contains("active: Boolean = true"))
        assertTrue(
            "active=true 且权限就绪时才连接",
            source.contains("if (!active) return@LaunchedEffect") &&
                source.contains("if (!hasCameraPermission) return@LaunchedEffect") &&
                source.contains("cameraController.connect(")
        )
        assertTrue(
            "active=false 时应调用现有 sessionId disconnect",
            source.contains("disconnectCurrentSession()") &&
                source.contains("cameraController.disconnect(sessionId)")
        )
        assertTrue("断开操作应放到后台调度器", source.contains("NonCancellable + Dispatchers.Default"))
        assertFalse("页面离开不得调用永久 release", source.contains("cameraController.release("))
    }

    @Test
    fun `surfaceProvider 只在 Main immediate 获取`() {
        val source = readSource()
        val providerRead = source.indexOf("previewView.surfaceProvider")
        val mainDispatcher = source.indexOf("Dispatchers.Main.immediate")
        val backgroundDispatcher = source.indexOf(
            "val connection = withContext(NonCancellable + Dispatchers.Default)"
        )

        assertTrue("surfaceProvider 应存在", providerRead >= 0)
        assertTrue("surfaceProvider 应在 Main.immediate 代码段中读取", mainDispatcher >= 0 && mainDispatcher < providerRead)
        assertTrue("surfaceProvider 读取应先于后台连接段", providerRead < backgroundDispatcher)
        assertFalse(
            "后台连接段不得再次访问 PreviewView.surfaceProvider",
            source.substring(backgroundDispatcher).contains("previewView.surfaceProvider")
        )
        assertTrue("后台 connect 应接收已获取的 surfaceProvider", source.substring(backgroundDispatcher).contains("surfaceProvider,"))
    }

    @Test
    fun `重入和迟到连接不会留下旧 session`() {
        val source = readSource()
        assertTrue("连接请求应有代次门禁", source.contains("connectionGeneration.incrementAndGet()"))
        assertTrue("迟到连接应按 sessionId 主动清理", source.contains("cameraController.disconnect(session.sessionId)"))
        assertTrue("迟到连接不应触发连接失败回调", source.contains("else if (isCurrentConnection)"))
        assertTrue("DisposableEffect 应保留为销毁兜底", source.contains("DisposableEffect(Unit)"))
    }

    @Test
    fun `现场采集页将可见状态传给 CameraPreview`() {
        val source = File(
            "src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt"
        ).readText()
        assertTrue("现场页应将导航可见状态传给 CameraPreviewSection", source.contains("active = isScreenVisible"))
        assertTrue("不可见时不应处理旧 contentRect 回调", source.contains("if (isScreenVisible) contentRect = info.contentRect"))
        assertTrue("不可见时不应把相机断开误报为连接失败", source.contains("if (isScreenVisible) {\n                        contentRect = null\n                        sessionId = id"))
    }
}
