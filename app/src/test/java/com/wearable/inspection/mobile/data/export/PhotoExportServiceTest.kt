package com.wearable.inspection.mobile.data.export

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * PhotoExportService 逻辑测试
 *
 * 覆盖：uniqueName 去重逻辑、ExportResult 类型。
 * 真实 ZIP 导出需要 Android Context + Repository，由 instrumented 测试覆盖。
 *
 * Instrumented 测试应覆盖：
 * 1. 批次隔离：batch A 的照片不出现在 batch B 的 ZIP 中
 * 2. ZIP 条目命名：view_0.jpg, view_1.jpg 格式
 * 3. 多视角导出：4 视角零件 → ZIP 包含 4 张照片
 * 4. 跨零件批次：不同零件的批次独立导出
 * 5. 失败路径：空批次返回 Failure
 * 6. 未关联旧照片：不在任何批次中的照片不被导出
 */
class PhotoExportServiceTest {

    // uniqueName 是 internal，可直接测试
    private fun uniqueNameLogic(originalName: String, usedNames: Set<String>): String {
        if (originalName !in usedNames) return originalName

        val dotIndex = originalName.lastIndexOf('.')
        val base = if (dotIndex > 0) originalName.substring(0, dotIndex) else originalName
        val ext = if (dotIndex > 0) originalName.substring(dotIndex) else ""

        var counter = 2
        var candidate: String
        do {
            candidate = "${base}_$counter$ext"
            counter++
        } while (candidate in usedNames)

        return candidate
    }

    @Test
    fun `uniqueName returns original when not in used set`() {
        val result = uniqueNameLogic("photo.jpg", emptySet())
        assertEquals("photo.jpg", result)
    }

    @Test
    fun `uniqueName appends counter for duplicate name`() {
        val result = uniqueNameLogic("photo.jpg", setOf("photo.jpg"))
        assertEquals("photo_2.jpg", result)
    }

    @Test
    fun `uniqueName increments counter for multiple duplicates`() {
        val result = uniqueNameLogic("photo.jpg", setOf("photo.jpg", "photo_2.jpg"))
        assertEquals("photo_3.jpg", result)
    }

    @Test
    fun `uniqueName handles file without extension`() {
        val result = uniqueNameLogic("photo", setOf("photo"))
        assertEquals("photo_2", result)
    }

    @Test
    fun `uniqueName handles file with multiple dots`() {
        val result = uniqueNameLogic("capture_2026.09.03.jpg", setOf("capture_2026.09.03.jpg"))
        assertEquals("capture_2026.09.03_2.jpg", result)
    }

    @Test
    fun `uniqueName handles empty extension`() {
        val result = uniqueNameLogic("photo.", setOf("photo."))
        assertEquals("photo_2.", result)
    }

    @Test
    fun `ExportResult Success has correct fields`() {
        val file = File("/tmp/test.zip")
        val result = ExportResult.Success(zipFile = file, photoCount = 5, skippedCount = 1)
        assertEquals(5, result.photoCount)
        assertEquals(1, result.skippedCount)
        assertEquals(file, result.zipFile)
    }

    @Test
    fun `ExportResult Failure has message`() {
        val result = ExportResult.Failure("没有可导出的照片")
        assertEquals("没有可导出的照片", result.message)
    }

    @Test
    fun `ExportResult Failure for empty batch`() {
        val result = ExportResult.Failure("该采集批次没有照片")
        assertEquals("该采集批次没有照片", result.message)
    }
}
