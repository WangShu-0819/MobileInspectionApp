package com.wearable.inspection.mobile.data.image

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 现场拍照路径和大文件移动契约测试。
 */
class MobileImageStoreCapturePathTest {

    @Test
    fun `camera capture can write directly to managed captures directory`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/data/image/MobileImageStore.kt")
            .readText()
        assertTrue("应提供现场照片受管理临时路径生成器", source.contains("fun generateCaptureFile(): File"))
        assertTrue(
            "CameraX 输出临时路径应位于 captures 目录",
            source.contains("return File(getCapturesDir(), \"${'$'}TEMP_PREFIX")
        )
    }

    @Test
    fun `captured image is not decoded twice after move`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/data/image/MobileImageStore.kt")
            .readText()
        assertTrue("移动后应复用移动前的校验结果", source.contains("return validation.copy(finalPath = finalFile.absolutePath)"))
        assertTrue("最终文件应使用正式 JPEG 文件名", source.contains("private fun generateFinalFile(): File"))
    }
}
