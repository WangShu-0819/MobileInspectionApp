package com.wearable.inspection.mobile.data.export

import android.content.Context
import com.wearable.inspection.mobile.data.entity.DpmScanEvidenceEntity
import com.wearable.inspection.mobile.data.repository.InspectionRepository
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * DPM 扫码证据 ZIP 导出服务
 *
 * 为所有已保存的 DPM 扫码证据生成独立 ZIP：
 * 1. 按 scanSessionId 建子目录，放入原始帧和 ROI 裁切图
 * 2. 根目录附 manifest.csv，记录每条证据的元数据和对应 ZIP 路径
 * 3. 缺失或损坏文件在 manifest 中明确标注，不静默跳过
 * 4. 导出后保留应用内原始证据文件，不删除、不移动
 */
class DpmEvidenceExportService(
    private val context: Context,
    private val repository: InspectionRepository
) {

    /**
     * 导出所有 DPM 扫码证据到 ZIP
     *
     * @param outputFile 目标 ZIP 文件（通常在 cacheDir 生成后由 SAF 复制）
     * @return 导出结果
     */
    suspend fun exportEvidenceZip(outputFile: File): DpmEvidenceExportResult {
        val allEvidence = repository.getAllDpmScanEvidence()
        if (allEvidence.isEmpty()) {
            return DpmEvidenceExportResult.Empty
        }

        return try {
            var exportedCount = 0
            var missingCount = 0
            val manifestRows = mutableListOf<DpmEvidenceManifestRow>()

            ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
                for (evidence in allEvidence) {
                    val sessionDir = "sessions/${evidence.scanSessionId}"

                    // 处理原始帧
                    val frameZipPath = "$sessionDir/frame_${evidence.id}_${evidence.frameTimeMs}.jpg"
                    val frameStatus = addFileToZip(
                        zos = zos,
                        filePath = evidence.originalImagePath,
                        entryName = frameZipPath
                    )
                    if (frameStatus == "已导出") exportedCount++ else missingCount++

                    // 处理 ROI 裁切图（可选）
                    var roiZipPath = ""
                    var roiStatus = "无 ROI 裁切"
                    if (!evidence.roiImagePath.isNullOrEmpty()) {
                        roiZipPath = "$sessionDir/roi_${evidence.id}_${evidence.frameTimeMs}.jpg"
                        roiStatus = addFileToZip(
                            zos = zos,
                            filePath = evidence.roiImagePath,
                            entryName = roiZipPath
                        )
                        if (roiStatus == "已导出") exportedCount++ else missingCount++
                    }

                    manifestRows += DpmEvidenceManifestRow(
                        scanSessionId = evidence.scanSessionId,
                        frameTimeMs = evidence.frameTimeMs,
                        frameSource = evidence.frameSource,
                        status = evidence.status,
                        decodedContent = evidence.decodedContent ?: "",
                        decodeSource = evidence.decodeSource ?: "",
                        frameZipPath = frameZipPath,
                        frameStatus = frameStatus,
                        roiZipPath = roiZipPath,
                        roiStatus = roiStatus
                    )
                }

                // 写 manifest.csv
                val csvEntry = ZipEntry("manifest.csv")
                zos.putNextEntry(csvEntry)
                writeManifestCsv(zos, manifestRows)
                zos.closeEntry()
            }

            DpmEvidenceExportResult.Success(
                zipFile = outputFile,
                sessionCount = allEvidence.map { it.scanSessionId }.distinct().size,
                exportedCount = exportedCount,
                missingCount = missingCount
            )
        } catch (e: Exception) {
            outputFile.delete()
            DpmEvidenceExportResult.Failure("导出失败：${e.localizedMessage ?: "未知错误"}")
        }
    }

    /**
     * 将文件添加到 ZIP。文件不存在或为空时返回状态描述，不抛异常。
     */
    private fun addFileToZip(zos: ZipOutputStream, filePath: String, entryName: String): String {
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) {
            return "缺失：文件不存在或为空"
        }
        return try {
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
                "已导出"
            } catch (e: Exception) {
                if (entryOpen) runCatching { zos.closeEntry() }
                "写入失败：${e.localizedMessage ?: "未知错误"}"
            }
        } catch (_: Exception) {
            "写入失败"
        }
    }

    /**
     * 写入 CSV manifest（UTF-8 with BOM，Excel 兼容）
     */
    private fun writeManifestCsv(zos: ZipOutputStream, rows: List<DpmEvidenceManifestRow>) {
        // UTF-8 BOM for Excel
        zos.write(0xEF)
        zos.write(0xBB)
        zos.write(0xBF)

        OutputStreamWriter(zos, Charsets.UTF_8).use { writer ->
            writer.appendLine("scanSessionId,frameTimeMs,frameSource,status,decodedContent,decodeSource,frameZipPath,frameStatus,roiZipPath,roiStatus")
            for (row in rows) {
                writer.appendLine(
                    listOf(
                        csvEscape(row.scanSessionId),
                        row.frameTimeMs.toString(),
                        csvEscape(row.frameSource),
                        csvEscape(row.status),
                        csvEscape(row.decodedContent),
                        csvEscape(row.decodeSource),
                        csvEscape(row.frameZipPath),
                        csvEscape(row.frameStatus),
                        csvEscape(row.roiZipPath),
                        csvEscape(row.roiStatus)
                    ).joinToString(",")
                )
            }
            writer.flush()
        }
    }

    private fun csvEscape(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    /**
     * 生成默认 ZIP 文件名
     */
    fun generateZipFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "dpm_evidence_$ts.zip"
    }
}

/**
 * 导出结果
 */
sealed class DpmEvidenceExportResult {
    data class Success(
        val zipFile: File,
        val sessionCount: Int,
        val exportedCount: Int,
        val missingCount: Int
    ) : DpmEvidenceExportResult()

    data class Failure(val message: String) : DpmEvidenceExportResult()
    object Empty : DpmEvidenceExportResult()
}

/**
 * Manifest CSV 行
 */
data class DpmEvidenceManifestRow(
    val scanSessionId: String,
    val frameTimeMs: Long,
    val frameSource: String,
    val status: String,
    val decodedContent: String,
    val decodeSource: String,
    val frameZipPath: String,
    val frameStatus: String,
    val roiZipPath: String,
    val roiStatus: String
)
