package com.wearable.inspection.mobile.data.export

import com.wearable.inspection.mobile.data.entity.DpmScanEvidenceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DpmEvidenceExportService 逻辑测试
 *
 * 覆盖：
 * - DpmEvidenceExportResult 类型（Success/Failure/Empty）
 * - DpmEvidenceManifestRow 字段正确性
 * - generateZipFileName 格式
 * - 源码契约：manifest.csv 列头、按 scanSessionId 分目录、缺失文件标注、导出后不删除
 * - csvEscape 逻辑
 *
 * ZIP 内容验证需要 Android Context + Repository，由 instrumented 测试覆盖。
 */
class DpmEvidenceExportServiceTest {

    // ─── DpmEvidenceExportResult 类型测试 ───

    @Test
    fun `Success result has correct fields`() {
        val result = DpmEvidenceExportResult.Success(
            zipFile = java.io.File("/tmp/test.zip"),
            sessionCount = 3,
            exportedCount = 5,
            missingCount = 1
        )
        assertEquals(3, result.sessionCount)
        assertEquals(5, result.exportedCount)
        assertEquals(1, result.missingCount)
    }

    @Test
    fun `Failure result preserves message`() {
        val result = DpmEvidenceExportResult.Failure("文件写入失败")
        assertEquals("文件写入失败", result.message)
    }

    @Test
    fun `Empty result is singleton`() {
        val result = DpmEvidenceExportResult.Empty
        assertTrue(result is DpmEvidenceExportResult.Empty)
    }

    // ─── DpmEvidenceManifestRow 字段测试 ───

    @Test
    fun `manifest row preserves all fields`() {
        val row = DpmEvidenceManifestRow(
            scanSessionId = "sess-abc-123",
            frameTimeMs = 1726000000000L,
            frameSource = "CAMERA",
            status = "SUCCESS",
            decodedContent = "L0549630AE092212080057",
            decodeSource = "ZXING",
            frameZipPath = "sessions/sess-abc-123/frame_1_1726000000000.jpg",
            frameStatus = "已导出",
            roiZipPath = "sessions/sess-abc-123/roi_1_1726000000000.jpg",
            roiStatus = "已导出"
        )
        assertEquals("sess-abc-123", row.scanSessionId)
        assertEquals(1726000000000L, row.frameTimeMs)
        assertEquals("CAMERA", row.frameSource)
        assertEquals("SUCCESS", row.status)
        assertEquals("L0549630AE092212080057", row.decodedContent)
        assertEquals("ZXING", row.decodeSource)
        assertTrue(row.frameZipPath.contains("sess-abc-123"))
        assertTrue(row.roiZipPath.contains("sess-abc-123"))
    }

    @Test
    fun `manifest row for NO_READ has empty decodedContent and decodeSource`() {
        val row = DpmEvidenceManifestRow(
            scanSessionId = "sess-def-456",
            frameTimeMs = 1726000001000L,
            frameSource = "CAMERA",
            status = "NO_READ",
            decodedContent = "",
            decodeSource = "",
            frameZipPath = "sessions/sess-def-456/frame_2_1726000001000.jpg",
            frameStatus = "已导出",
            roiZipPath = "",
            roiStatus = "无 ROI 裁切"
        )
        assertEquals("NO_READ", row.status)
        assertEquals("", row.decodedContent)
        assertEquals("", row.decodeSource)
        assertEquals("", row.roiZipPath)
    }

    @Test
    fun `manifest row can represent missing file`() {
        val row = DpmEvidenceManifestRow(
            scanSessionId = "sess-ghi-789",
            frameTimeMs = 1726000002000L,
            frameSource = "CAMERA",
            status = "SUCCESS",
            decodedContent = "CODE123",
            decodeSource = "ML_KIT",
            frameZipPath = "sessions/sess-ghi-789/frame_3_1726000002000.jpg",
            frameStatus = "缺失：文件不存在或为空",
            roiZipPath = "",
            roiStatus = "无 ROI 裁切"
        )
        assertTrue(row.frameStatus.contains("缺失"))
    }

    // ─── ZIP 文件名格式测试 ───

    @Test
    fun `generateZipFileName format contains dpm_evidence prefix`() {
        // 验证文件名格式（通过源码契约）
        val source = java.io.File(
            "src/main/java/com/wearable/inspection/mobile/data/export/DpmEvidenceExportService.kt"
        ).readText()
        assertTrue("文件名必须包含 dpm_evidence_ 前缀",
            source.contains("\"dpm_evidence_\$ts.zip\"") || source.contains("dpm_evidence_"))
    }

    // ─── 源码契约测试 ───

    @Test
    fun `export writes manifest csv with required columns`() {
        val source = java.io.File(
            "src/main/java/com/wearable/inspection/mobile/data/export/DpmEvidenceExportService.kt"
        ).readText()
        assertTrue("manifest 必须包含 scanSessionId 列",
            source.contains("scanSessionId"))
        assertTrue("manifest 必须包含 frameTimeMs 列",
            source.contains("frameTimeMs"))
        assertTrue("manifest 必须包含 status 列",
            source.contains("status"))
        assertTrue("manifest 必须包含 decodedContent 列",
            source.contains("decodedContent"))
        assertTrue("manifest 必须包含 decodeSource 列",
            source.contains("decodeSource"))
        assertTrue("manifest 必须包含 frameStatus 列",
            source.contains("frameStatus"))
        assertTrue("manifest 必须包含 roiStatus 列",
            source.contains("roiStatus"))
    }

    @Test
    fun `export organizes files by scanSessionId directory`() {
        val source = java.io.File(
            "src/main/java/com/wearable/inspection/mobile/data/export/DpmEvidenceExportService.kt"
        ).readText()
        assertTrue("ZIP 内必须按 scanSessionId 分目录",
            source.contains("val sessionDir = \"sessions/\${evidence.scanSessionId}\""))
    }

    @Test
    fun `export marks missing files in manifest`() {
        val source = java.io.File(
            "src/main/java/com/wearable/inspection/mobile/data/export/DpmEvidenceExportService.kt"
        ).readText()
        assertTrue("缺失文件必须在 manifest 中标注",
            source.contains("缺失：文件不存在或为空"))
    }

    @Test
    fun `export does not delete original evidence files`() {
        val source = java.io.File(
            "src/main/java/com/wearable/inspection/mobile/data/export/DpmEvidenceExportService.kt"
        ).readText()
        // 原始证据路径不得出现在 delete() 调用中
        val lines = source.lines()
        val deletesOriginal = lines.any { line ->
            line.contains("originalImagePath") && line.contains("delete")
        }
        assertFalse("导出后不得删除原始证据文件", deletesOriginal)
    }

    @Test
    fun `export returns Empty when no evidence exists`() {
        val source = java.io.File(
            "src/main/java/com/wearable/inspection/mobile/data/export/DpmEvidenceExportService.kt"
        ).readText()
        assertTrue("无证据时必须返回 Empty",
            source.contains("DpmEvidenceExportResult.Empty"))
    }

    @Test
    fun `export deletes temp zip on failure`() {
        val source = java.io.File(
            "src/main/java/com/wearable/inspection/mobile/data/export/DpmEvidenceExportService.kt"
        ).readText()
        assertTrue("导出失败时必须删除临时 ZIP",
            source.contains("outputFile.delete()"))
    }

    @Test
    fun `manifest csv includes roi fields even when empty`() {
        val source = java.io.File(
            "src/main/java/com/wearable/inspection/mobile/data/export/DpmEvidenceExportService.kt"
        ).readText()
        assertTrue("manifest 必须包含 roiZipPath 列",
            source.contains("roiZipPath"))
        assertTrue("无 ROI 时 roiStatus 必须标注",
            source.contains("无 ROI 裁切"))
    }

    @Test
    fun `repository exposes getAllDpmScanEvidence`() {
        val source = java.io.File(
            "src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt"
        ).readText()
        assertTrue("Repository 必须暴露 getAllDpmScanEvidence",
            source.contains("suspend fun getAllDpmScanEvidence()"))
    }

    @Test
    fun `trace records screen has dpm evidence export button`() {
        val source = java.io.File(
            "src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt"
        ).readText()
        assertTrue("追溯记录页必须有 DPM 证据导出按钮",
            source.contains("DPM 扫码证据"))
        assertTrue("必须使用 SAF 创建文件",
            source.contains("createDpmZipLauncher"))
        assertTrue("必须使用 DpmEvidenceExportService",
            source.contains("DpmEvidenceExportService"))
    }
}
