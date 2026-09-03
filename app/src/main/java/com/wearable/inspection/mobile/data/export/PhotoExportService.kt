package com.wearable.inspection.mobile.data.export

import android.content.Context
import com.wearable.inspection.mobile.data.entity.CapturedPhotoEntity
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
 * 照片导出结果
 */
sealed class ExportResult {
    /** 导出成功 */
    data class Success(
        val zipFile: File,
        val photoCount: Int,
        val skippedCount: Int
    ) : ExportResult()

    /** 导出失败 */
    data class Failure(val message: String) : ExportResult()
}

/**
 * 照片导出服务
 *
 * 职责：
 * 1. 按采集批次（batchId）查询已保存的现场照片
 * 2. 打包为 ZIP 文件，保留原始文件名
 * 3. 处理空记录、缺失/损坏图片、重复文件名和 IO 错误
 *
 * 不实现：manifest、Excel、PASS/FAIL、Detector 或完整结果包。
 */
class PhotoExportService(
    private val context: Context,
    private val repository: InspectionRepository
) {

    /**
     * 导出指定采集批次的照片到 ZIP 文件
     *
     * @param batchId 采集批次 ID
     * @param outputFile 目标 ZIP 文件（必须可写）
     * @return 导出结果
     */
    suspend fun exportBatchToZip(batchId: String, outputFile: File): ExportResult {
        val photos = repository.getCapturedPhotos(batchId)
        if (photos.isEmpty()) {
            return ExportResult.Failure("该批次没有可导出的照片")
        }

        return try {
            var photoCount = 0
            var skippedCount = 0
            val usedNames = mutableSetOf<String>()

            ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
                for (photo in photos) {
                    val file = File(photo.filePath)

                    // 跳过不存在或空文件
                    if (!file.exists() || file.length() == 0L) {
                        skippedCount++
                        continue
                    }

                    // 处理重复文件名
                    val entryName = uniqueName(file.name, usedNames)
                    usedNames.add(entryName)

                    try {
                        val entry = ZipEntry(entryName).apply {
                            size = file.length()
                            time = file.lastModified()
                        }
                        zos.putNextEntry(entry)
                        FileInputStream(file).use { fis ->
                            fis.copyTo(zos)
                        }
                        zos.closeEntry()
                        photoCount++
                    } catch (_: Exception) {
                        // 单个文件写入失败，跳过继续
                        skippedCount++
                    }
                }
            }

            if (photoCount == 0) {
                outputFile.delete()
                ExportResult.Failure("所有照片均无法导出")
            } else {
                ExportResult.Success(
                    zipFile = outputFile,
                    photoCount = photoCount,
                    skippedCount = skippedCount
                )
            }
        } catch (e: Exception) {
            outputFile.delete()
            ExportResult.Failure("导出失败：${e.localizedMessage ?: "未知错误"}")
        }
    }

    /**
     * 生成默认 ZIP 文件名
     */
    fun generateZipFileName(batchId: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "photos_${batchId.take(8)}_$ts.zip"
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
