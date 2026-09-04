package com.wearable.inspection.mobile.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 确认页大图加载线程契约测试。
 */
class ViewConfirmationPerformanceTest {

    @Test
    fun `photo dimensions and roi crops are loaded on IO dispatcher`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationViewModel.kt")
            .readText()
        val dimensions = source.indexOf("val dimensions = withContext(Dispatchers.IO)")
        val crops = source.indexOf("val loadedBitmaps = withContext(Dispatchers.IO)")
        assertTrue("照片尺寸读取不能在主线程执行", dimensions > 0)
        assertTrue("ROI 大图裁剪不能在主线程执行", crops > dimensions)
        assertTrue("确认页应批量回填已加载的 Bitmap", source.contains("roiBitmaps.putAll(loadedBitmaps)"))
    }
}
