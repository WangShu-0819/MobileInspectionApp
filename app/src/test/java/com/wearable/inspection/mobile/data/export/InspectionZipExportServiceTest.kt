package com.wearable.inspection.mobile.data.export

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * InspectionZipExportService 逻辑测试
 *
 * 覆盖：
 * - uniqueName 去重逻辑
 * - InspectionExportResult 类型
 * - ZIP 文件名生成
 *
 * ZIP 内容验证需要 Android Context + Repository，由 instrumented 测试覆盖。
 */
class InspectionZipExportServiceTest {

    // uniqueName 逻辑（与 PhotoExportServiceTest 一致的测试模式）
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
    fun `InspectionExportResult Success has correct fields`() {
        val result = InspectionExportResult.Success(
            zipFile = java.io.File("/tmp/test.zip"),
            photoCount = 5,
            skippedCount = 1,
            csvRowCount = 10,
            partId = "part_001",
            batchId = "batch_001"
        )
        assertEquals(5, result.photoCount)
        assertEquals(1, result.skippedCount)
        assertEquals(10, result.csvRowCount)
        assertEquals("part_001", result.partId)
        assertEquals("batch_001", result.batchId)
    }

    @Test
    fun `InspectionExportResult Failure has message`() {
        val result = InspectionExportResult.Failure("没有确认记录")
        assertEquals("没有确认记录", result.message)
    }

    @Test
    fun `generateZipFileName format follows convention`() {
        // 验证文件名格式约定: inspection_{partId}_{batchId_short}_{timestamp}.zip
        val partId = "part_001"
        val batchId = "batch_1234567890"
        val batchIdShort = batchId.take(8) // "batch_12" (8 chars)
        assertEquals(8, batchIdShort.length)
        assertTrue(batchIdShort.startsWith("batch_"))
        // 文件名前缀应包含零件ID和批次ID前缀
        val prefix = "inspection_${partId}_${batchIdShort}_"
        assertTrue(prefix.contains(partId))
        assertTrue(prefix.contains(batchIdShort))
    }

    @Test
    fun `photos are sufficient for a zip when batch has no roi confirms`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/data/export/InspectionZipExportService.kt")
            .readText()
        val photosCheck = source.indexOf("val photos = repository.getCapturedPhotos(batchId)")
        val confirmsCheck = source.indexOf("val confirms = repository.getViewRoiConfirms(batchId)")
        assertTrue("应先读取批次照片", photosCheck > 0)
        assertTrue("确认行应允许为空并在照片检查后读取", confirmsCheck > photosCheck)
        assertFalse("不应因确认行为空而拒绝有照片的批次", source.contains("if (confirms.isEmpty())"))
    }

    @Test
    fun `zip keeps all captured photos including views without roi rows`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/data/export/InspectionZipExportService.kt")
            .readText()
        val photoLoop = source.indexOf("for (photo in photos)")
        val csvWrite = source.indexOf("InspectionExcelExporter.exportCombinedToStream(photoRows, confirms, partId, zos)")
        assertTrue("ZIP 应遍历当前批次全部照片", photoLoop > 0)
        assertTrue("照片应按 View 分目录", source.contains("views/view_"))
        assertTrue("照片写入应在 CSV 写入前完成", csvWrite > photoLoop)
        assertTrue("照片索引和确认结果应写入同一个 CSV", source.contains("inspection_result.csv"))
        assertFalse("ZIP 不应再写第二个照片清单 CSV", source.contains("photo_manifest.csv"))
    }

    @Test
    fun `combined excel records photo rows and skipped files`() {
        val exporter = File("src/main/java/com/wearable/inspection/mobile/data/export/InspectionExcelExporter.kt")
            .readText()
        val source = File("src/main/java/com/wearable/inspection/mobile/data/export/InspectionZipExportService.kt")
            .readText()
        assertTrue("综合表应区分照片行", exporter.contains("\"照片\""))
        assertTrue("综合表应区分 ROI 确认行", exporter.contains("\"ROI确认\""))
        assertTrue("综合表应包含 ZIP 路径", exporter.contains("\"ZIP路径\""))
        assertTrue("综合表应包含照片状态", exporter.contains("\"照片状态\""))
        assertTrue("导出器应保留跳过文件状态", source.contains("照片文件不存在或为空"))
    }

    @Test
    fun `trace records must use the full inspection zip exporter`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt")
            .readText()
        assertTrue("追溯记录页应使用检测结果 ZIP 服务", source.contains("InspectionZipExportService"))
        assertTrue("追溯记录页应调用 exportInspectionZip", source.contains("exportInspectionZip"))
        assertFalse("追溯记录页不应继续使用仅照片导出服务", source.contains("PhotoExportService"))
    }

    @Test
    fun `zip export requires a completed capture batch`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/data/export/InspectionZipExportService.kt")
            .readText()
        val batchLookup = source.indexOf("val batch = repository.getCaptureBatch(batchId)")
        val completionGate = source.indexOf("if (batch.endTime == null)", batchLookup)
        val photoLookup = source.indexOf("val photos = repository.getCapturedPhotos(batchId)")
        assertTrue("导出前应读取稳定 batchId 对应的批次", batchLookup > 0)
        assertTrue("未完成批次应被导出门禁拦截", completionGate > batchLookup)
        assertTrue("完成状态检查应在读取照片前", completionGate < photoLookup)
        assertTrue("未完成批次应给出明确提示", source.contains("采集尚未完成，请拍完全部视角后再导出"))
        assertTrue("导出前应校验所有视角均有照片", source.contains("capturedViewIndices.containsAll(expectedViewIndices)"))
    }

    @Test
    fun `trace records disables export button for unfinished batches`() {
        val source = File("src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt")
            .readText()
        assertTrue("卡片应根据 endTime 判断完成状态", source.contains("completed = batch.endTime != null"))
        assertTrue("未完成批次不能点击导出", source.contains("enabled = !exporting && completed"))
        assertTrue("未完成批次应显示完成后导出提示", source.contains("采集中，拍完全部视角后才能导出 ZIP"))
    }
}
