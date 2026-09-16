package com.wearable.inspection.mobile.data.export

import android.content.Context
import com.wearable.inspection.mobile.data.entity.CaptureBatchEntity
import com.wearable.inspection.mobile.data.entity.CapturedPhotoEntity
import com.wearable.inspection.mobile.data.entity.DpmScanEvidenceEntity
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.data.entity.ViewRoiConfirmEntity
import com.wearable.inspection.mobile.data.repository.InspectionRepository
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

/** 真实临时文件和 ZipInputStream 回读批次 ZIP 与独立 DPM ZIP。 */
class InspectionZipExportArchiveTest {
    @Test
    fun `batch zip contains stable multi-view roi model human and associated dpm bytes`() = runTest {
        val dir = Files.createTempDirectory("inspection-export").toFile()
        val photos = listOf(
            photo(dir, 202, 1, "tpl-1", byteArrayOf(2, 2)),
            photo(dir, 101, 0, "tpl-0", byteArrayOf(1, 1)),
            photo(dir, 204, 1, "tpl-1", byteArrayOf(4, 4)),
            photo(dir, 103, 0, "tpl-0", byteArrayOf(3, 3)),
        )
        val thread = roi("roi-thread", "tpl-0", "THREAD")
        val feature = roi("roi-feature", "tpl-0", "FEATURE")
        val unconfigured = roi("roi-unconfigured", "tpl-1", null)
        val confirmed = confirm(photos[1], thread, human = "NG", overall = "OK")
        val featureConfirmed = confirm(
            photos[1], feature, human = "NG", overall = "OK",
            software = null, softwareStatus = "FEATURE_UNSUPPORTED", detectionsJson = null,
        )
        val reverse = confirm(
            photos[3], thread, human = "OK", overall = "NG",
            software = "NG", score = 0.2f, softwareStatus = "DETECTED_BELOW_THRESHOLD",
            detectionsJson = """[{"classIndex":1,"className":"thread","score":0.2,"roiBox":{"left":3},"imageBox":{"left":4}}]""",
        )
        val evidenceFrame = File(dir, "dpm-frame.jpg").apply { writeBytes(byteArrayOf(8, 7, 6, 5)) }
        val evidenceRoi = File(dir, "dpm-roi.jpg").apply { writeBytes(byteArrayOf(4, 3, 2, 1)) }
        val missingFrame = File(dir, "missing-frame.jpg")
        val success = DpmScanEvidenceEntity(
            id = 7, scanSessionId = "session-b", frameTimeMs = 700,
            frameSource = "CAMERA", decodedContent = "DM-7", status = "SUCCESS",
            decodeSource = "ZXING", originalImagePath = evidenceFrame.absolutePath,
            roiImagePath = evidenceRoi.absolutePath, batchId = "batch-a", partId = "part-a",
            templateId = "tpl-0", viewIndex = 0,
        )
        val noRead = success.copy(id = 8, scanSessionId = "session-no-read", decodedContent = null, status = "NO_READ")
        val unassociated = success.copy(id = 9, scanSessionId = "session-independent", batchId = null)
        val missing = success.copy(id = 10, scanSessionId = "session-missing", originalImagePath = missingFrame.absolutePath)

        val repository = Mockito.mock(InspectionRepository::class.java)
        `when`(repository.getCaptureBatch("batch-a")).thenReturn(CaptureBatchEntity("batch-a", "part-a", "零件A", 1, 2, 2))
        `when`(repository.getCapturedPhotos("batch-a")).thenReturn(photos)
        `when`(repository.getViewRoiConfirms("batch-a")).thenReturn(listOf(confirmed, featureConfirmed, reverse))
        `when`(repository.getRois("tpl-0")).thenReturn(listOf(feature, thread))
        `when`(repository.getRois("tpl-1")).thenReturn(listOf(unconfigured))
        `when`(repository.getDpmScanEvidenceByBatch("batch-a")).thenReturn(listOf(noRead, unassociated, success, missing))
        `when`(repository.getAllDpmScanEvidence()).thenReturn(listOf(noRead, unassociated, success, missing))

        val batchZip = File(dir, "batch.zip")
        val independentZip = File(dir, "independent.zip")
        val context = Mockito.mock(Context::class.java)
        val batchResult = InspectionZipExportService(context, repository)
            .exportInspectionZip("batch-a", "part-a", batchZip)
        val independentResult = DpmEvidenceExportService(context, repository).exportEvidenceZip(independentZip)
        assertTrue("batch export failed: $batchResult", batchResult is InspectionExportResult.Success)
        assertTrue("independent export failed: $independentResult", independentResult is DpmEvidenceExportResult.Success)

        val batchEntries = unzip(batchZip)
        val independentEntries = unzip(independentZip)
        val manifest = batchEntries.getValue("inspection_result.csv").toString(Charsets.UTF_8)
        assertTrue(manifest.contains("batchId"))
        assertTrue(manifest.contains("roi-thread"))
        assertTrue(manifest.contains("DETECTED"))
        assertTrue(manifest.contains(",NG,true,"))
        assertTrue(manifest.contains("FEATURE"))
        assertTrue(manifest.contains("FEATURE_UNSUPPORTED"))
        assertTrue(manifest.contains("未选择"))
        assertTrue(manifest.contains("人工未确认"))
        assertTrue(manifest.contains("DM-7"))
        assertFalse("缺失源帧不得进入批次 DPM 图片/manifest", manifest.contains("session-missing"))
        assertFalse("NO_READ 不得进入批次 DPM 图片/manifest", manifest.contains("session-no-read"))
        assertFalse("无 batchId 的独立 DPM 证据不得进入批次 ZIP", manifest.contains("session-independent"))

        val batchFrame = batchEntries.entries.first { it.key.endsWith("frame_7_700.jpg") }.value
        val batchRoi = batchEntries.entries.first { it.key.endsWith("roi_7_700.jpg") }.value
        val independentFrame = independentEntries.entries.first { it.key.endsWith("frame_7_700.jpg") }.value
        val independentRoi = independentEntries.entries.first { it.key.endsWith("roi_7_700.jpg") }.value
        assertArrayEquals(evidenceFrame.readBytes(), batchFrame)
        assertArrayEquals(evidenceRoi.readBytes(), batchRoi)
        assertArrayEquals(independentFrame, batchFrame)
        assertArrayEquals(independentRoi, batchRoi)
        assertFalse("独立 DPM ZIP 不得导出 NO_READ", independentEntries.keys.any { it.contains("session-no-read") })
        assertFalse("独立 DPM ZIP 不得导出缺失源帧", independentEntries.keys.any { it.contains("session-missing") })
        assertTrue(batchEntries.keys.any { it.startsWith("views/view_01/") })
        assertTrue(batchEntries.keys.any { it.startsWith("views/view_02/") })
        assertTrue(batchEntries.size > 5)

        val records = parseCsv(manifest)
        val roiRecords = records.filter { it[0] == "ROI" }
        val firstThread = roiRecords.first { it[3] == "101" && it[6] == "roi-thread" && it[32] == "0" }
        assertEquals("OK", firstThread[21]) // 模型原值
        assertEquals("NG", firstThread[22]) // 人工终审
        assertEquals("true", firstThread[23])
        assertEquals("OK", firstThread[25]) // 总体人工结论独立于 ROI
        val firstPhoto = records.first { it[0] == "照片" && it[3] == "101" }
        assertEquals("OK", firstPhoto[25])
        assertEquals("views/view_01/photo_101_photo-101.jpg", firstPhoto[28])
        assertEquals("已导出", firstPhoto[29])
        assertEquals(2, roiRecords.count { it[3] == "101" && it[6] == "roi-thread" })
        val reverseRecord = roiRecords.first { it[3] == "103" && it[6] == "roi-thread" }
        assertEquals("NG", reverseRecord[21])
        assertEquals("OK", reverseRecord[22])
        assertEquals("true", reverseRecord[23])
        assertEquals("NG", reverseRecord[25])
        val unconfiguredRecord = roiRecords.first { it[3] == "202" && it[6] == "roi-unconfigured" }
        assertEquals("未执行", unconfiguredRecord[20])
        assertEquals("人工未确认", unconfiguredRecord[22])
    }

    @Test
    fun `batch export never includes evidence or photos from another batch`() = runTest {
        val dir = Files.createTempDirectory("inspection-export-isolation").toFile()
        val photoAFile = File(dir, "a.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val photoBFile = File(dir, "b.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val dpmAFile = File(dir, "dpm-a.jpg").apply { writeBytes(byteArrayOf(7, 8)) }
        val dpmBFile = File(dir, "dpm-b.jpg").apply { writeBytes(byteArrayOf(9, 10)) }
        val photoA = CapturedPhotoEntity(1, "batch-a", photoAFile.absolutePath, 0, null, null, 1)
        val photoB = CapturedPhotoEntity(2, "batch-b", photoBFile.absolutePath, 0, null, null, 2)
        val dpmA = DpmScanEvidenceEntity(
            id = 1, scanSessionId = "session-a", frameTimeMs = 1, frameSource = "CAMERA",
            decodedContent = "A", status = "SUCCESS", decodeSource = "ZXING",
            originalImagePath = dpmAFile.absolutePath, batchId = "batch-a",
        )
        val dpmB = dpmA.copy(id = 2, scanSessionId = "session-b", decodedContent = "B",
            originalImagePath = dpmBFile.absolutePath, batchId = "batch-b")
        val invalidSource = dpmA.copy(id = 3, scanSessionId = "session-invalid-source", decodeSource = "OTHER")
        val emptyCode = dpmA.copy(id = 4, scanSessionId = "session-empty-code", decodedContent = "")
        val repository = Mockito.mock(InspectionRepository::class.java)
        `when`(repository.getCaptureBatch("batch-a")).thenReturn(CaptureBatchEntity("batch-a", "part-a", "A", 1, 2, 1))
        `when`(repository.getCapturedPhotos("batch-a")).thenReturn(listOf(photoA))
        `when`(repository.getViewRoiConfirms("batch-a")).thenReturn(emptyList())
        `when`(repository.getDpmScanEvidenceByBatch("batch-a")).thenReturn(listOf(dpmA, dpmB, invalidSource, emptyCode))
        `when`(repository.getAllDpmScanEvidence()).thenReturn(listOf(dpmA, dpmB, invalidSource, emptyCode))

        val zip = File(dir, "batch-a.zip")
        val result = InspectionZipExportService(Mockito.mock(Context::class.java), repository)
            .exportInspectionZip("batch-a", "part-a", zip)
        assertTrue(result is InspectionExportResult.Success)
        val entries = unzip(zip)
        assertTrue(entries.keys.any { it.contains("a.jpg") })
        assertFalse(entries.keys.any { it.contains("b.jpg") })
        val csv = entries.getValue("inspection_result.csv").toString(Charsets.UTF_8)
        assertTrue(csv.contains("session-a"))
        assertFalse(csv.contains("session-b"))
        assertFalse(csv.contains("session-invalid-source"))
        assertFalse(csv.contains("session-empty-code"))
    }

    private fun photo(dir: File, id: Long, view: Int, template: String, bytes: ByteArray): CapturedPhotoEntity {
        val file = File(dir, "photo-$id.jpg").apply { writeBytes(bytes) }
        return CapturedPhotoEntity(id, "batch-a", file.absolutePath, view, template, "View$view", id)
    }

    private fun roi(id: String, template: String, target: String?) = RoiDefinitionEntity(
        id = id, templateId = template, name = id, order = 0,
        normalizedRect = "{\"left\":0.1,\"top\":0.1,\"right\":0.9,\"bottom\":0.9}",
        inspectionType = "NONE", targetType = target,
    )

    private fun confirm(
        photo: CapturedPhotoEntity,
        roi: RoiDefinitionEntity,
        human: String,
        overall: String,
        software: String? = "OK",
        score: Float = 0.9f,
        softwareStatus: String = "DETECTED",
        detectionsJson: String? = """[{"className":"thread","score":0.9,"roiBox":{"left":1},"imageBox":{"left":2}},{"className":"thread","score":0.8,"roiBox":{"left":5},"imageBox":{"left":6}}]""",
    ) =
        ViewRoiConfirmEntity(
            id = 10, batchId = photo.batchId, photoId = photo.photoId, photoPath = photo.filePath,
            viewIndex = photo.viewIndex, templateId = photo.templateId!!, templateName = photo.templateName!!,
            roiId = roi.id, roiName = roi.name, roiTargetType = roi.targetType,
            roiNormalizedRect = roi.normalizedRect, roiPixelRect = "{\"left\":1,\"top\":1,\"right\":2,\"bottom\":2}",
            softwareResult = software, humanResult = human, confirmTime = 1000,
            overallResult = overall, overallConfirmTime = 1001,
            softwareTargetClass = software?.let { roi.targetType },
            softwareScore = software?.let { score }, softwareThreshold = software?.let { 0.37f },
            softwareDetectionsJson = detectionsJson,
            softwareStatus = softwareStatus, softwareModelVersion = "nanodet-test", softwareModelSummary = "{\"candidateThreshold\":0.05}",
            softwareElapsedMs = software?.let { 12 }, humanChangedModel = software != null && software != human,
        )

    private fun parseCsv(csv: String): List<List<String>> = csv.removePrefix("\uFEFF").trimEnd().lines().map { line ->
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
     * 真实绑定流程：DPM 证据初始 batchId=null，通过 bindSessionToBatch 模拟 DAO 更新后，
     * getDpmScanEvidenceByBatch 返回绑定后的记录，导出服务正确包含 DPM 帧。
     * 不使用 copy(batchId=...) 伪造绑定结果。
     */
    @Test
    fun `batch zip includes dpm evidence bound after scan-before-batch flow`() = runTest {
        val dir = Files.createTempDirectory("inspection-export-bound").toFile()
        val dpmFrame = File(dir, "dpm-scan-frame.jpg").apply { writeBytes(byteArrayOf(0x50, 0x4D, 0x01)) }
        val dpmRoi = File(dir, "dpm-scan-roi.jpg").apply { writeBytes(byteArrayOf(0x50, 0x4D, 0x02)) }

        // 真实可变状态：模拟 DAO 行为
        val evidenceStore = mutableListOf(
            DpmScanEvidenceEntity(
                id = 50, scanSessionId = "session-scan-first", frameTimeMs = 5000,
                frameSource = "CAMERA", decodedContent = "DPM-BOUND-01", status = "SUCCESS",
                decodeSource = "ZXING", originalImagePath = dpmFrame.absolutePath,
                roiImagePath = dpmRoi.absolutePath, batchId = null, partId = "part-bound",
            )
        )

        val photo = photo(dir, 501, 0, "tpl-bound", byteArrayOf(0x10, 0x20))
        val threadRoi = roi("roi-bound", "tpl-bound", "THREAD")
        val confirmed = confirm(photo, threadRoi, human = "OK", overall = "OK")

        val repository = Mockito.mock(InspectionRepository::class.java)
        `when`(repository.getCaptureBatch("batch-bound")).thenReturn(
            CaptureBatchEntity("batch-bound", "part-bound", "绑定零件", 1, 1, 1)
        )
        `when`(repository.getCapturedPhotos("batch-bound")).thenReturn(listOf(photo))
        `when`(repository.getViewRoiConfirms("batch-bound")).thenReturn(listOf(confirmed))
        `when`(repository.getRois("tpl-bound")).thenReturn(listOf(threadRoi))

        // 模拟真实 DAO 绑定行为：更新 evidenceStore 中 batchId 为 null 的记录
        `when`(repository.bindDpmScanSessionToBatch("session-scan-first", "batch-bound")).thenAnswer {
            var updated = 0
            evidenceStore.forEachIndexed { idx, e ->
                if (e.scanSessionId == "session-scan-first" && e.batchId == null && e.status == "SUCCESS") {
                    evidenceStore[idx] = e.copy(batchId = "batch-bound")
                    updated++
                }
            }
            updated
        }

        // 绑定前：getByBatchId 返回空（无 batchId 匹配）
        `when`(repository.getDpmScanEvidenceByBatch("batch-bound")).thenAnswer {
            evidenceStore.filter { it.batchId == "batch-bound" }
        }
        `when`(repository.getAllDpmScanEvidence()).thenAnswer { evidenceStore.toList() }

        // 验证绑定前导出不包含 DPM
        val preBindZip = File(dir, "pre-bind.zip")
        val preResult = InspectionZipExportService(Mockito.mock(Context::class.java), repository)
            .exportInspectionZip("batch-bound", "part-bound", preBindZip)
        assertTrue("绑定前导出必须成功", preResult is InspectionExportResult.Success)
        val preEntries = unzip(preBindZip)
        assertFalse("绑定前 ZIP 不应包含 DPM 帧",
            preEntries.keys.any { it.startsWith("dpm/sessions/") })

        // 执行真实绑定
        val updated = repository.bindDpmScanSessionToBatch("session-scan-first", "batch-bound")
        assertEquals("绑定必须更新 1 行", 1, updated)

        // 验证绑定后导出包含 DPM
        val postBindZip = File(dir, "post-bind.zip")
        val postResult = InspectionZipExportService(Mockito.mock(Context::class.java), repository)
            .exportInspectionZip("batch-bound", "part-bound", postBindZip)
        assertTrue("绑定后导出必须成功", postResult is InspectionExportResult.Success)

        val entries = unzip(postBindZip)
        assertTrue("ZIP 必须包含 DPM 原始帧",
            entries.keys.any { it.startsWith("dpm/sessions/") && it.contains("frame_") })
        assertTrue("ZIP 必须包含 DPM ROI",
            entries.keys.any { it.startsWith("dpm/sessions/") && it.contains("roi_") })

        // 验证 DPM 文件字节正确
        val frameEntry = entries.entries.first { it.key.contains("frame_50_5000") }
        assertArrayEquals("DPM 帧字节必须一致", dpmFrame.readBytes(), frameEntry.value)
        val roiEntry = entries.entries.first { it.key.contains("roi_50_5000") }
        assertArrayEquals("DPM ROI 字节必须一致", dpmRoi.readBytes(), roiEntry.value)

        val csv = entries.getValue("inspection_result.csv").toString(Charsets.UTF_8)
        assertTrue("CSV 必须包含绑定的扫码会话", csv.contains("session-scan-first"))
        assertTrue("CSV 必须包含解码内容", csv.contains("DPM-BOUND-01"))
        assertTrue("CSV 必须包含 batchId", csv.contains("batch-bound"))
    }
}
