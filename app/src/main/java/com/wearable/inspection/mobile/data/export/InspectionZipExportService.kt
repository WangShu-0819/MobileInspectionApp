package com.wearable.inspection.mobile.data.export

import android.content.Context
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

/**
 * 检测结果 ZIP 导出服务
 *
 * 为单个零件的单个检测批次生成 ZIP，包含：
 * 1. 该批次全部 View 原始照片（按 View 分目录）
 * 2. 综合检测 CSV（照片索引行 + 每个 ROI 一行，NG 不丢弃）
 *
 * 一个零件一个 ZIP，不混入其他零件或历史批次。
 */
class InspectionZipExportService(
    private val context: Context,
    private val repository: InspectionRepository
) {

    /**
     * 为指定批次生成检测结果 ZIP
     *
     * @param batchId 采集批次 ID
     * @param partId 零件 ID
     * @param outputFile 目标 ZIP 文件
     * @return 导出结果
     */
    suspend fun exportInspectionZip(
        batchId: String,
        partId: String,
        outputFile: File
    ): InspectionExportResult {
        // 批次在第一张照片落库时就会出现，但只有全部 View 完成后才允许生成 ZIP。
        val batch = repository.getCaptureBatch(batchId)
            ?: return InspectionExportResult.Failure("采集批次不存在")
        if (batch.partId != partId) {
            return InspectionExportResult.Failure("采集批次与零件不匹配")
        }
        if (batch.endTime == null) {
            return InspectionExportResult.Failure("采集尚未完成，请拍完全部视角后再导出")
        }

        // 1. 获取该批次的照片（仅属于此批次）。无 ROI 批次也必须可导出。
        val photos = repository.getCapturedPhotos(batchId)
        if (photos.isEmpty()) {
            return InspectionExportResult.Failure("该批次没有照片")
        }
        val expectedViewIndices = (0 until batch.viewCount).toSet()
        val capturedViewIndices = photos.map { it.viewIndex }.toSet()
        if (batch.viewCount <= 0 || !capturedViewIndices.containsAll(expectedViewIndices)) {
            return InspectionExportResult.Failure("采集尚未完成，请拍完全部视角后再导出")
        }
        // 2. 确认记录可为空：无 ROI View 没有 ROI 行，但原始照片仍要进入 ZIP。
        val confirms = repository.getViewRoiConfirms(batchId)

        return try {
            var photoCount = 0
            var skippedCount = 0
            val usedNames = mutableSetOf<String>()
            val photoRows = mutableListOf<InspectionPhotoExportRow>()

            ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
                // 3. 写入照片到按 View 区分的目录；同一 View 的重拍照片全部保留。
                for (photo in photos) {
                    val file = File(photo.filePath)
                    if (!file.exists() || file.length() == 0L) {
                        skippedCount++
                        photoRows += InspectionPhotoExportRow(
                            photo = photo,
                            zipPath = "",
                            status = "跳过：照片文件不存在或为空",
                        )
                        continue
                    }

                    val uniqueFileName = uniqueName(file.name, usedNames)
                    val viewFolder = "views/view_${(photo.viewIndex + 1).toString().padStart(2, '0')}"
                    val entryName = "$viewFolder/$uniqueFileName"
                    usedNames.add(uniqueFileName)

                    var entryOpen = false
                    try {
                        val entry = ZipEntry(entryName).apply {
                            size = file.length()
                            time = file.lastModified()
                        }
                        zos.putNextEntry(entry)
                        entryOpen = true
                        FileInputStream(file).use { fis ->
                            fis.copyTo(zos)
                        }
                        zos.closeEntry()
                        entryOpen = false
                        photoCount++
                        photoRows += InspectionPhotoExportRow(
                            photo = photo,
                            zipPath = entryName,
                            status = "已导出",
                        )
                    } catch (_: Exception) {
                        if (entryOpen) {
                            runCatching { zos.closeEntry() }
                        }
                        skippedCount++
                        photoRows += InspectionPhotoExportRow(
                            photo = photo,
                            zipPath = "",
                            status = "跳过：写入 ZIP 失败",
                        )
                    }
                }

                // 4. 写出唯一综合 CSV：照片索引与真实 ROI 确认结果共用一张表。
                val csvEntry = ZipEntry("inspection_result.csv")
                zos.putNextEntry(csvEntry)
                InspectionExcelExporter.exportCombinedToStream(photoRows, confirms, partId, zos)
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
                    csvRowCount = confirms.size,
                    partId = partId,
                    batchId = batchId
                )
            }
        } catch (e: Exception) {
            outputFile.delete()
            InspectionExportResult.Failure("导出失败：${e.localizedMessage ?: "未知错误"}")
        }
    }


    /**
     * 生成默认 ZIP 文件名
     */
    fun generateZipFileName(partId: String, batchId: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "inspection_${partId}_${batchId.take(8)}_$ts.zip"
    }

    /**
     * 生成唯一文件名，避免 ZIP 内重名
     */
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
}

/**
 * 检测导出结果
 */
sealed class InspectionExportResult {
    data class Success(
        val zipFile: File,
        val photoCount: Int,
        val skippedCount: Int,
        val csvRowCount: Int,
        val partId: String,
        val batchId: String
    ) : InspectionExportResult()

    data class Failure(val message: String) : InspectionExportResult()
}
