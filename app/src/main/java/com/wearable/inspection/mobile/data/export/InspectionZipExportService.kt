package com.wearable.inspection.mobile.data.export

import android.content.Context
import android.util.Log
import com.wearable.inspection.mobile.data.entity.CapturedPhotoEntity
import com.wearable.inspection.mobile.data.entity.DpmScanEvidenceEntity
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.data.entity.ViewRoiConfirmEntity
import com.wearable.inspection.mobile.data.repository.InspectionRepository
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 为一个稳定 batchId 导出现场照片、模型/人工结果和显式关联的 DPM 成功证据。 */
class InspectionZipExportService(
    private val context: Context,
    private val repository: InspectionRepository,
) {
    suspend fun exportInspectionZip(batchId: String, partId: String, outputFile: File): InspectionExportResult {
        val batch = repository.getCaptureBatch(batchId)
            ?: return InspectionExportResult.Failure("采集批次不存在")
        if (batch.partId != partId) return InspectionExportResult.Failure("采集批次与零件不匹配")
        if (batch.endTime == null) return InspectionExportResult.Failure("采集尚未完成，请拍完全部视角后再导出")

        val photos = repository.getCapturedPhotos(batchId)
            .sortedWith(compareBy<CapturedPhotoEntity> { it.viewIndex }.thenBy { it.photoId })
        if (photos.isEmpty()) return InspectionExportResult.Failure("该批次没有照片")
        val expectedViewIndices = (0 until batch.viewCount).toSet()
        if (batch.viewCount <= 0 || !photos.map { it.viewIndex }.toSet().containsAll(expectedViewIndices)) {
            return InspectionExportResult.Failure("采集尚未完成，请拍完全部视角后再导出")
        }

        val confirms = repository.getViewRoiConfirms(batchId)
        val roiDefinitions = photos.mapNotNull { it.templateId }.distinct().associateWith { templateId ->
            repository.getRois(templateId).filter { it.enabled }.sortedBy { it.id }
        }
        val queriedDpmEvidence = repository.getDpmScanEvidenceByBatch(batchId)
        Log.w("DpmBinding", "[DIAG-7] exportInspectionZip: batchId=$batchId, queriedRows=${queriedDpmEvidence.size}")
        queriedDpmEvidence.forEach { e ->
            Log.w("DpmBinding", "[DIAG-7]   row id=${e.id}, sid=${e.scanSessionId}, status=${e.status}, batchId=${e.batchId}, file=${e.originalImagePath}")
        }
        val dpmEvidence = queriedDpmEvidence
            .filter { evidence -> evidence.batchId == batchId && isExportableDpmSuccess(evidence) }
            .sortedWith(compareBy<DpmScanEvidenceEntity> { it.scanSessionId }.thenBy { it.id }.thenBy { it.frameTimeMs })
        Log.w("DpmBinding", "[DIAG-7] exportInspectionZip: filteredValidRows=${dpmEvidence.size}")
        Log.i(TAG, "exportInspectionZip: batchId=$batchId dpmQueryRows=${queriedDpmEvidence.size} validRows=${dpmEvidence.size}")

        return try {
            var photoCount = 0
            var skippedCount = 0
            val photoRows = mutableListOf<InspectionPhotoExportRow>()
            val roiRows = mutableListOf<InspectionRoiExportRow>()
            val dpmRows = mutableListOf<InspectionDpmExportRow>()

            ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
                val roiEvidenceZipPaths = mutableMapOf<Long, String>()
                photos.forEach { photo ->
                    val file = File(photo.filePath)
                    val entryName = "views/view_${(photo.viewIndex + 1).toString().padStart(2, '0')}/photo_${photo.photoId}_${file.name}"
                    val status: String
                    if (!file.isFile || file.length() == 0L) {
                        status = "缺失：照片文件不存在或为空"
                        skippedCount++
                    } else {
                        status = try {
                            zos.putNextEntry(ZipEntry(entryName).apply { size = file.length(); time = file.lastModified() })
                            FileInputStream(file).use { it.copyTo(zos) }
                            zos.closeEntry()
                            photoCount++
                            "已导出"
                        } catch (_: Exception) {
                            runCatching { zos.closeEntry() }
                            skippedCount++
                            "写入失败：照片无法写入 ZIP"
                        }
                    }
                    val photoConfirms = confirms.filter { confirm ->
                        confirm.batchId == batchId && confirm.photoId == photo.photoId &&
                            confirm.photoPath == photo.filePath && confirm.viewIndex == photo.viewIndex &&
                            confirm.templateId == photo.templateId
                    }
                    val overallValues = photoConfirms.map { it.overallResult }.distinct().filter { it == "OK" || it == "NG" }
                    photoRows += InspectionPhotoExportRow(
                        photo = photo,
                        zipPath = if (status == "已导出") entryName else "",
                        status = status,
                        overallResult = overallValues.singleOrNull(),
                        overallConfirmTime = photoConfirms.map { it.overallConfirmTime }.distinct().singleOrNull(),
                    )

                    val definitions = roiDefinitions[photo.templateId].orEmpty()
                    val matchedConfirmKeys = mutableSetOf<String>()
                    definitions.forEach { roi ->
                        val confirm = photoConfirms.firstOrNull { it.roiId == roi.id }
                        if (confirm != null) matchedConfirmKeys += roi.id
                        val zipPath = confirm?.let { roiEvidenceZipPaths[it.id] }.orEmpty()
                        roiRows += InspectionRoiExportRow(photo, roi, confirm, zipPath)
                    }
                    // 只有稳定 photoId + roiId + template/view/path 全部匹配的确认行才导出。
                    photoConfirms.filter { it.roiId !in matchedConfirmKeys }.forEach { confirm ->
                        val matchingDefinition = definitions.firstOrNull { it.id == confirm.roiId }
                        if (matchingDefinition != null) {
                            val zipPath = roiEvidenceZipPaths[confirm.id].orEmpty()
                            roiRows += InspectionRoiExportRow(photo, matchingDefinition, confirm, zipPath)
                        }
                    }
                }

                // 写入改判 ROI 证据图（必须在 CSV 之前，确保 CSV 中的 ZIP 路径准确）
                confirms.filter { it.humanChangedModel && !it.roiEvidencePath.isNullOrBlank() }
                    .forEach { confirm ->
                        val evidenceFile = File(confirm.roiEvidencePath!!)
                        if (evidenceFile.isFile && evidenceFile.length() > 0L) {
                            val entryName = "roi_evidence/${confirm.batchId.take(8)}_${confirm.photoId}_${confirm.viewIndex}_${confirm.roiId.take(8)}_${confirm.id}.jpg"
                            try {
                                zos.putNextEntry(ZipEntry(entryName).apply { size = evidenceFile.length(); time = evidenceFile.lastModified() })
                                FileInputStream(evidenceFile).use { it.copyTo(zos) }
                                zos.closeEntry()
                                roiEvidenceZipPaths[confirm.id] = entryName
                            } catch (_: Exception) {
                                runCatching { zos.closeEntry() }
                            }
                        }
                    }

                // 回填 roiRows 中已成功写入 ZIP 的证据路径
                roiRows.filter { row -> row.confirm?.id?.let { roiEvidenceZipPaths.containsKey(it) } == true }
                    .forEach { row -> row.roiEvidenceZipPath = roiEvidenceZipPaths[row.confirm!!.id].orEmpty() }

                dpmEvidence.forEach { evidence ->
                    val sessionDir = "dpm/sessions/${evidence.scanSessionId}"
                    val frameEntryName = "$sessionDir/frame_${evidence.id}_${evidence.frameTimeMs}.jpg"
                    val frameStatus = copyDpmFileToZip(zos, evidence.originalImagePath, frameEntryName)
                    val framePath = frameEntryName.takeIf { frameStatus == "已导出" }.orEmpty()
                    if (frameStatus != "已导出") {
                        throw IllegalStateException("DPM 原图在导出时不可用：sessionId=${evidence.scanSessionId}")
                    }
                    var roiPath = ""
                    var roiStatus = "无 ROI 裁切"
                    if (!evidence.roiImagePath.isNullOrBlank()) {
                        val roiEntryName = "$sessionDir/roi_${evidence.id}_${evidence.frameTimeMs}.jpg"
                        roiStatus = copyDpmFileToZip(zos, evidence.roiImagePath, roiEntryName)
                        roiPath = roiEntryName.takeIf { roiStatus == "已导出" }.orEmpty()
                        if (roiStatus != "已导出") {
                            throw IllegalStateException("DPM ROI 在导出时不可用：sessionId=${evidence.scanSessionId}")
                        }
                    }
                    dpmRows += InspectionDpmExportRow(evidence, framePath, frameStatus, roiPath, roiStatus)
                }

                zos.putNextEntry(ZipEntry("inspection_result.csv"))
                InspectionExcelExporter.exportUnifiedToStream(
                    batchId = batchId,
                    partId = partId,
                    photos = photoRows,
                    roiRows = roiRows,
                    dpmRows = dpmRows,
                    outputStream = zos,
                )
                zos.closeEntry()
            }

            if (photoCount == 0) {
                outputFile.delete()
                InspectionExportResult.Failure("所有照片均无法导出")
            } else {
                InspectionExportResult.Success(
                    zipFile = outputFile,
                    photoCount = photoCount,
                    skippedCount = skippedCount,
                    csvRowCount = photoRows.size + roiRows.size + dpmRows.size,
                    partId = partId,
                    batchId = batchId,
                )
            }
        } catch (e: Exception) {
            outputFile.delete()
            InspectionExportResult.Failure("导出失败：${e.localizedMessage ?: "未知错误"}")
        }
    }

    fun generateZipFileName(partId: String, batchId: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "inspection_${partId}_${batchId.take(8)}_$ts.zip"
    }

    internal fun uniqueName(originalName: String, usedNames: Set<String>): String {
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

    private fun isExportableDpmSuccess(evidence: DpmScanEvidenceEntity): Boolean =
        evidence.status == "SUCCESS" &&
            !evidence.decodedContent.isNullOrBlank() && evidence.decodeSource in setOf("ZXING", "ML_KIT", "GRID") &&
            evidence.originalImagePath.isNotBlank() &&
            File(evidence.originalImagePath).let { it.isFile && it.length() > 0L } &&
            (evidence.roiImagePath.isNullOrBlank() || File(evidence.roiImagePath).let { it.isFile && it.length() > 0L })

    companion object {
        private const val TAG = "InspectionZipExport"
    }
}

sealed class InspectionExportResult {
    data class Success(
        val zipFile: File,
        val photoCount: Int,
        val skippedCount: Int,
        val csvRowCount: Int,
        val partId: String,
        val batchId: String,
    ) : InspectionExportResult()

    data class Failure(val message: String) : InspectionExportResult()
}
