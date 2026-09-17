package com.wearable.inspection.mobile.ui.screens

import android.content.Context
import com.wearable.inspection.mobile.data.entity.CaptureBatchEntity
import com.wearable.inspection.mobile.data.entity.CapturedPhotoEntity
import com.wearable.inspection.mobile.data.entity.DpmScanEvidenceEntity
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.data.entity.ViewRoiConfirmEntity
import com.wearable.inspection.mobile.data.export.InspectionExcelExporter
import com.wearable.inspection.mobile.data.export.InspectionExportResult
import com.wearable.inspection.mobile.data.export.InspectionZipExportService
import com.wearable.inspection.mobile.data.repository.InspectionRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * ROI 改判证据导出测试
 *
 * 覆盖：
 * - CSV 列头有 45 列，末尾为 result/overrideTime
 * - 改判时 ROI 证据图写入 ZIP roi_evidence/ 目录
 * - 未改判不写入 roi_evidence
 * - ZIP 内 roi_evidence entry 内容与源文件 SHA-256 一致
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class RoiEvidenceExportTest {

    private val batchId = "batch-evidence"
    private val partId = "part-e"

    private fun photo(dir: File, id: Long, view: Int, template: String, bytes: ByteArray): CapturedPhotoEntity {
        val file = File(dir, "photo-$id.jpg").apply { writeBytes(bytes) }
        return CapturedPhotoEntity(id, batchId, file.absolutePath, view, template, "View$view", id)
    }

    private fun roi(id: String, template: String, target: String?) = RoiDefinitionEntity(
        id = id, templateId = template, name = id, order = 0,
        normalizedRect = """{"left":0.1,"top":0.1,"right":0.9,"bottom":0.9}""",
        inspectionType = "NONE", targetType = target,
    )

    private fun confirm(
        id: Long = 10,
        photo: CapturedPhotoEntity,
        roi: RoiDefinitionEntity,
        human: String = "OK",
        overall: String = "OK",
        software: String? = "OK",
        overrideTime: Long? = null,
        roiEvidencePath: String? = null,
    ) = ViewRoiConfirmEntity(
        id = id, batchId = photo.batchId, photoId = photo.photoId, photoPath = photo.filePath,
        viewIndex = photo.viewIndex, templateId = photo.templateId!!, templateName = photo.templateName!!,
        roiId = roi.id, roiName = roi.name, roiTargetType = roi.targetType,
        roiNormalizedRect = roi.normalizedRect, roiPixelRect = """{"left":1,"top":1,"right":2,"bottom":2}""",
        softwareResult = software, humanResult = human, confirmTime = 1000,
        overallResult = overall, overallConfirmTime = 1001,
        softwareTargetClass = software?.let { roi.targetType },
        softwareScore = software?.let { 0.85f }, softwareThreshold = software?.let { 0.37f },
        softwareDetectionsJson = null, softwareStatus = "DETECTED",
        softwareModelVersion = "nanodet-test", softwareModelSummary = """{"candidateThreshold":0.05}""",
        softwareElapsedMs = software?.let { 12 },
        humanChangedModel = software != null && software != human,
        overrideTime = overrideTime,
        roiEvidencePath = roiEvidencePath,
    )

    private fun parseCsv(csv: String): List<List<String>> = csv.removePrefix("﻿").trimEnd().lines().map { line ->
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            when (val c = line[i]) {
                '"' -> if (quoted && i + 1 < line.length && line[i + 1] == '"') {
                    current.append('"'); i++
                } else quoted = !quoted
                ',' -> if (quoted) current.append(c) else { fields += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        fields += current.toString()
        fields
    }

    private fun unzip(file: File): Map<String, ByteArray> = buildMap {
        ZipInputStream(FileInputStream(file)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                put(entry.name, zip.readBytes())
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    /**
     * 在 runTest 协程上下文内设置 Repository mock（挂起函数需要协程）。
     */
    private suspend fun setupRepoInCoroutine(
        photos: List<CapturedPhotoEntity>,
        confirms: List<ViewRoiConfirmEntity>,
        roisByTemplate: Map<String, List<RoiDefinitionEntity>>,
    ): InspectionRepository {
        val repo = Mockito.mock(InspectionRepository::class.java)
        `when`(repo.getCaptureBatch(batchId)).thenReturn(
            CaptureBatchEntity(batchId, partId, "部件E", 1L, System.currentTimeMillis(), photos.size)
        )
        `when`(repo.getCapturedPhotos(batchId)).thenReturn(photos)
        `when`(repo.getViewRoiConfirms(batchId)).thenReturn(confirms)
        roisByTemplate.forEach { (tplId, rois) -> `when`(repo.getRois(tplId)).thenReturn(rois) }
        `when`(repo.getDpmScanEvidenceByBatch(batchId)).thenReturn(emptyList())
        `when`(repo.getAllDpmScanEvidence()).thenReturn(emptyList())
        return repo
    }

    @Test
    fun `CSV header has 45 columns with result and overrideTime at end`() = runTest {
        val dir = Files.createTempDirectory("roi-evidence-export").toFile()
        try {
            val thread = roi("roi-thread", "tpl-0", "THREAD")
            val photos = listOf(photo(dir, 101, 0, "tpl-0", byteArrayOf(1, 1)))
            val confirms = listOf(confirm(photo = photos[0], roi = thread))
            val repo = setupRepoInCoroutine(photos, confirms, mapOf("tpl-0" to listOf(thread)))
            val zipFile = File(dir, "evidence.zip")
            val result = InspectionZipExportService(Mockito.mock(Context::class.java), repo)
                .exportInspectionZip(batchId, partId, zipFile)
            assertTrue("export failed: $result", result is InspectionExportResult.Success)

            val entries = unzip(zipFile)
            val csv = entries.getValue("inspection_result.csv").toString(Charsets.UTF_8)
            val rows = parseCsv(csv)
            assertTrue(rows.isNotEmpty())
            val header = rows[0]
            // 修改前基线为 45 列；追加 result + overrideTime 后为 47 列
            assertEquals("CSV 列数应为 47", 47, header.size)
            assertEquals("记录类型", header[0])
            assertEquals("batchId", header[1])
            // 原有列索引不变
            assertEquals("拍摄时间", header[27])
            assertEquals("ROI图ZIP路径", header[30])
            assertEquals("ROI图状态", header[31])
            assertEquals("dpmFrameStatus", header[43])
            assertEquals("dpmRoiStatus", header[44])
            // 新列追加在末尾
            assertEquals("result", header[45])
            assertEquals("overrideTime", header[46])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `ROI row has result and overrideTime when overridden`() = runTest {
        val dir = Files.createTempDirectory("roi-evidence-export").toFile()
        try {
            val evidenceDir = File(dir, "roi_evidence").apply { mkdirs() }
            val evidenceFile = File(evidenceDir, "roi_evidence_10.jpg").apply { writeText("FAKE_EVIDENCE_DATA") }
            val thread = roi("roi-thread", "tpl-0", "THREAD")
            val photos = listOf(photo(dir, 101, 0, "tpl-0", byteArrayOf(1, 1)))
            val confirms = listOf(
                confirm(
                    id = 10, photo = photos[0], roi = thread,
                    software = "OK", human = "NG", overall = "NG",
                    overrideTime = 1_700_000_025_000L,
                    roiEvidencePath = evidenceFile.absolutePath,
                )
            )
            val repo = setupRepoInCoroutine(photos, confirms, mapOf("tpl-0" to listOf(thread)))
            val zipFile = File(dir, "evidence.zip")
            val result = InspectionZipExportService(Mockito.mock(Context::class.java), repo)
                .exportInspectionZip(batchId, partId, zipFile)
            assertTrue("export failed: $result", result is InspectionExportResult.Success)

            val entries = unzip(zipFile)
            val csv = entries.getValue("inspection_result.csv").toString(Charsets.UTF_8)
            val rows = parseCsv(csv)
            val roiRow = rows.first { it[0] == "ROI" }
            assertEquals("result 列应为 NG", "NG", roiRow[roiRow.size - 2])
            assertTrue("overrideTime 不应为空", roiRow[roiRow.size - 1].isNotEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `ROI evidence image written to zip when overridden`() = runTest {
        val dir = Files.createTempDirectory("roi-evidence-export").toFile()
        try {
            val evidenceDir = File(dir, "roi_evidence").apply { mkdirs() }
            val evidenceContent = "ROI_EVIDENCE_BYTES_${System.nanoTime()}"
            val evidenceFile = File(evidenceDir, "roi_evidence_10.jpg").apply { writeText(evidenceContent) }
            val thread = roi("roi-thread", "tpl-0", "THREAD")
            val photos = listOf(photo(dir, 101, 0, "tpl-0", byteArrayOf(1, 1)))
            val confirms = listOf(
                confirm(
                    id = 10, photo = photos[0], roi = thread,
                    software = "OK", human = "NG", overall = "NG",
                    overrideTime = 1_700_000_025_000L,
                    roiEvidencePath = evidenceFile.absolutePath,
                )
            )
            val repo = setupRepoInCoroutine(photos, confirms, mapOf("tpl-0" to listOf(thread)))
            val zipFile = File(dir, "evidence.zip")
            val result = InspectionZipExportService(Mockito.mock(Context::class.java), repo)
                .exportInspectionZip(batchId, partId, zipFile)
            assertTrue("export failed: $result", result is InspectionExportResult.Success)

            val entries = unzip(zipFile)
            val roiEvidenceEntries = entries.keys.filter { it.startsWith("roi_evidence/") }
            assertTrue("应有 roi_evidence 条目", roiEvidenceEntries.isNotEmpty())
            val zipContent = entries.getValue(roiEvidenceEntries.first()).toString(Charsets.UTF_8)
            assertEquals("ZIP 内容应与源文件一致", evidenceContent, zipContent)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `ROI evidence not written when not overridden`() = runTest {
        val dir = Files.createTempDirectory("roi-evidence-export").toFile()
        try {
            val thread = roi("roi-thread", "tpl-0", "THREAD")
            val photos = listOf(photo(dir, 101, 0, "tpl-0", byteArrayOf(1, 1)))
            val confirms = listOf(confirm(photo = photos[0], roi = thread))
            val repo = setupRepoInCoroutine(photos, confirms, mapOf("tpl-0" to listOf(thread)))
            val zipFile = File(dir, "no_evidence.zip")
            val result = InspectionZipExportService(Mockito.mock(Context::class.java), repo)
                .exportInspectionZip(batchId, partId, zipFile)
            assertTrue("export failed: $result", result is InspectionExportResult.Success)

            val entries = unzip(zipFile)
            val roiEvidenceCount = entries.keys.count { it.startsWith("roi_evidence/") }
            assertEquals("未改判不应有 roi_evidence 条目", 0, roiEvidenceCount)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `overridden ROI row has non-empty CSV zip path matching real zip entry`() = runTest {
        val dir = Files.createTempDirectory("roi-evidence-export").toFile()
        try {
            val evidenceDir = File(dir, "roi_evidence").apply { mkdirs() }
            val evidenceContent = "CROSS_LINK_EVIDENCE_${System.nanoTime()}"
            val evidenceFile = File(evidenceDir, "roi_evidence_10.jpg").apply { writeText(evidenceContent) }
            val thread = roi("roi-thread", "tpl-0", "THREAD")
            val photos = listOf(photo(dir, 101, 0, "tpl-0", byteArrayOf(1, 1)))
            val confirms = listOf(
                confirm(
                    id = 10, photo = photos[0], roi = thread,
                    software = "OK", human = "NG", overall = "NG",
                    overrideTime = 1_700_000_025_000L,
                    roiEvidencePath = evidenceFile.absolutePath,
                )
            )
            val repo = setupRepoInCoroutine(photos, confirms, mapOf("tpl-0" to listOf(thread)))
            val zipFile = File(dir, "crosslink.zip")
            val result = InspectionZipExportService(Mockito.mock(Context::class.java), repo)
                .exportInspectionZip(batchId, partId, zipFile)
            assertTrue("export failed: $result", result is InspectionExportResult.Success)

            val entries = unzip(zipFile)
            val csv = entries.getValue("inspection_result.csv").toString(Charsets.UTF_8)
            val rows = parseCsv(csv)

            // 改判 ROI 行必须有非空 ROI图ZIP路径（index30）且精确匹配 ZIP entry
            val roiRows = rows.filter { it[0] == "ROI" }
            val overriddenRow = roiRows.first()
            val zipPath = overriddenRow[30]
            assertTrue("改判 ROI 行的 CSV ROI图ZIP路径不应为空", zipPath.isNotBlank())
            assertTrue("CSV 路径 '$zipPath' 必须在 ZIP 中存在", entries.containsKey(zipPath))
            assertTrue("ZIP entry 路径必须以 roi_evidence/ 开头", zipPath.startsWith("roi_evidence/"))
            assertEquals("已导出", overriddenRow[31])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `evidence source file missing before export yields empty CSV zip path`() = runTest {
        val dir = Files.createTempDirectory("roi-evidence-export").toFile()
        try {
            val evidenceDir = File(dir, "roi_evidence").apply { mkdirs() }
            val evidenceFile = File(evidenceDir, "roi_evidence_10.jpg").apply { writeText("TO_BE_DELETED") }
            val thread = roi("roi-thread", "tpl-0", "THREAD")
            val photos = listOf(photo(dir, 101, 0, "tpl-0", byteArrayOf(1, 1)))
            val confirms = listOf(
                confirm(
                    id = 10, photo = photos[0], roi = thread,
                    software = "OK", human = "NG", overall = "NG",
                    overrideTime = 1_700_000_025_000L,
                    roiEvidencePath = evidenceFile.absolutePath,
                )
            )
            // 模拟导出前源文件被删除
            evidenceFile.delete()
            assertFalse("源文件应已删除", evidenceFile.exists())

            val repo = setupRepoInCoroutine(photos, confirms, mapOf("tpl-0" to listOf(thread)))
            val zipFile = File(dir, "missing_source.zip")
            val result = InspectionZipExportService(Mockito.mock(Context::class.java), repo)
                .exportInspectionZip(batchId, partId, zipFile)
            assertTrue("export failed: $result", result is InspectionExportResult.Success)

            val entries = unzip(zipFile)
            val csv = entries.getValue("inspection_result.csv").toString(Charsets.UTF_8)
            val rows = parseCsv(csv)
            val roiRow = rows.first { it[0] == "ROI" }
            assertEquals("源图缺失时 CSV ROI图ZIP路径应为空", "", roiRow[30])
            assertNotEquals("源图缺失时 ROI图状态不应为已导出", "已导出", roiRow[31])
            assertEquals("ZIP 内不应有 roi_evidence 条目", 0, entries.keys.count { it.startsWith("roi_evidence/") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `unmodified ROI rows have empty zip path and correct status`() = runTest {
        val dir = Files.createTempDirectory("roi-evidence-export").toFile()
        try {
            val thread = roi("roi-thread", "tpl-0", "THREAD")
            val photos = listOf(photo(dir, 101, 0, "tpl-0", byteArrayOf(1, 1)))
            // 未改判
            val confirms = listOf(confirm(photo = photos[0], roi = thread))
            val repo = setupRepoInCoroutine(photos, confirms, mapOf("tpl-0" to listOf(thread)))
            val zipFile = File(dir, "no_override.zip")
            val result = InspectionZipExportService(Mockito.mock(Context::class.java), repo)
                .exportInspectionZip(batchId, partId, zipFile)
            assertTrue("export failed: $result", result is InspectionExportResult.Success)

            val entries = unzip(zipFile)
            val csv = entries.getValue("inspection_result.csv").toString(Charsets.UTF_8)
            val rows = parseCsv(csv)
            val roiRows = rows.filter { it[0] == "ROI" }
            roiRows.forEach { row ->
                assertEquals("未改判行的 ROI图ZIP路径 应为空", "", row[30])
                assertEquals("未改判行状态应为 '未改判'", "未改判", row[31])
            }

            // ZIP 内不应有 roi_evidence 条目
            assertEquals(0, entries.keys.count { it.startsWith("roi_evidence/") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `SHA-256 of roi evidence in zip matches source file`() = runTest {
        val dir = Files.createTempDirectory("roi-evidence-export").toFile()
        try {
            val evidenceDir = File(dir, "roi_evidence").apply { mkdirs() }
            val evidenceContent = "SHA256_VERIFY_CONTENT_${System.nanoTime()}"
            val evidenceFile = File(evidenceDir, "roi_evidence_10.jpg").apply { writeText(evidenceContent) }
            val thread = roi("roi-thread", "tpl-0", "THREAD")
            val photos = listOf(photo(dir, 101, 0, "tpl-0", byteArrayOf(1, 1)))
            val confirms = listOf(
                confirm(
                    id = 10, photo = photos[0], roi = thread,
                    software = "OK", human = "NG", overall = "NG",
                    overrideTime = 1_700_000_025_000L,
                    roiEvidencePath = evidenceFile.absolutePath,
                )
            )
            val repo = setupRepoInCoroutine(photos, confirms, mapOf("tpl-0" to listOf(thread)))
            val zipFile = File(dir, "sha256.zip")
            val result = InspectionZipExportService(Mockito.mock(Context::class.java), repo)
                .exportInspectionZip(batchId, partId, zipFile)
            assertTrue("export failed: $result", result is InspectionExportResult.Success)

            val entries = unzip(zipFile)
            val roiEvidenceEntry = entries.keys.first { it.startsWith("roi_evidence/") }
            val zipBytes = entries.getValue(roiEvidenceEntry)
            val zipSha256 = java.security.MessageDigest.getInstance("SHA-256").digest(zipBytes)
                .joinToString("") { "%02x".format(it) }
            val sourceSha256 = java.security.MessageDigest.getInstance("SHA-256")
                .digest(evidenceContent.toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertEquals("SHA-256 应一致", sourceSha256, zipSha256)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `ZIP write failure evidence status is not exported`() = runTest {
        // 当 ZIP 写入失败导致 roiEvidenceZipPath 为空时，
        // roiEvidenceZipStatus 必须返回非"已导出"的状态。
        val dir = Files.createTempDirectory("roi-evidence-export").toFile()
        try {
            val evidenceDir = File(dir, "roi_evidence").apply { mkdirs() }
            val evidenceFile = File(evidenceDir, "roi_evidence_10.jpg").apply { writeText("DATA") }
            val thread = roi("roi-thread", "tpl-0", "THREAD")
            val photos = listOf(photo(dir, 101, 0, "tpl-0", byteArrayOf(1, 1)))
            // 构造一个改判确认行，但 roiEvidenceZipPath 为空（模拟 ZIP 写入失败后回填为空）
            val confirmEntity = confirm(
                id = 10, photo = photos[0], roi = thread,
                software = "OK", human = "NG", overall = "NG",
                overrideTime = 1_700_000_025_000L,
                roiEvidencePath = evidenceFile.absolutePath,
            )
            // 直接验证状态逻辑：当实际 ZIP 路径为空时，状态不得为"已导出"
            val status = InspectionExcelExporter.run {
                // roiEvidenceZipStatus 是 private，通过 roiRow 间接测试：
                // 用 exportUnifiedToStream 构造一个 roiEvidenceZipPath 为空的行
                val baos = java.io.ByteArrayOutputStream()
                val photoRow = com.wearable.inspection.mobile.data.export.InspectionPhotoExportRow(
                    photo = photos[0], zipPath = "views/view_01/photo_101_photo-101.jpg", status = "已导出"
                )
                val roiRow = com.wearable.inspection.mobile.data.export.InspectionRoiExportRow(
                    photo = photos[0], roi = thread, confirm = confirmEntity, roiEvidenceZipPath = ""
                )
                exportUnifiedToStream(batchId, partId, listOf(photoRow), listOf(roiRow), emptyList(), baos)
                val csv = baos.toString(Charsets.UTF_8)
                val rows = csv.removePrefix("﻿").trimEnd().lines().map { line ->
                    val fields = mutableListOf<String>()
                    val current = StringBuilder()
                    var quoted = false
                    var i = 0
                    while (i < line.length) {
                        when (val c = line[i]) {
                            '"' -> if (quoted && i + 1 < line.length && line[i + 1] == '"') {
                                current.append('"'); i++
                            } else quoted = !quoted
                            ',' -> if (quoted) current.append(c) else { fields += current.toString(); current.clear() }
                            else -> current.append(c)
                        }
                        i++
                    }
                    fields += current.toString()
                    fields
                }
                val roiCsvRow = rows.first { it[0] == "ROI" }
                roiCsvRow[31] // ROI图状态 列
            }
            assertNotEquals("ZIP 写入失败时状态不应为已导出", "已导出", status)
            assertEquals("ZIP 写入失败时状态应为缺失", "缺失：改判证据未保存", status)
        } finally {
            dir.deleteRecursively()
        }
    }
}
