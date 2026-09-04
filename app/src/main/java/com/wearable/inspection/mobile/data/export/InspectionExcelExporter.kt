package com.wearable.inspection.mobile.data.export

import com.wearable.inspection.mobile.data.entity.CapturedPhotoEntity
import com.wearable.inspection.mobile.data.entity.ViewRoiConfirmEntity
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 检测结果 CSV 导出器
 *
 * 将 ViewRoiConfirmEntity 列表导出为 CSV 文件（Excel 兼容）。
 * 每个 ROI 一行，NG 结果不丢弃，必须写入。
 *
 * CSV 字段：
 * 图片名称, 零件ID, 模板ID, ViewID, View名称, ROI_ID, ROI属性,
 * ROI坐标, ROI_normalizedRect, ROI_映射后像素坐标, 软件检测结果,
 * 人工确认结果, 人工确认时间, 总体结果, 总体确认时间
 */
object InspectionExcelExporter {

    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** CSV 列头 */
    val HEADER: List<String> = listOf(
        "图片名称",
        "零件ID",
        "模板ID",
        "ViewID",
        "View名称",
        "ROI_ID",
        "ROI属性",
        "ROI坐标",
        "ROI_normalizedRect",
        "ROI_映射后像素坐标",
        "软件检测结果",
        "人工确认结果",
        "人工确认时间",
        "总体结果",
        "总体确认时间"
    )

    /** ZIP 内唯一的综合表头：照片行和 ROI 人工确认行共用一张表。 */
    val COMBINED_HEADER: List<String> = listOf("记录类型") + HEADER + listOf(
        "拍摄时间",
        "ZIP路径",
        "照片状态"
    )

    /**
     * 将确认记录导出为 CSV 到文件
     *
     * @param confirms 确认记录列表（每个 ROI 一行）
     * @param partId 零件 ID
     * @param outputFile 输出 CSV 文件
     * @return 写入的行数（不含表头），0 表示无数据
     */
    fun exportToFile(
        confirms: List<ViewRoiConfirmEntity>,
        partId: String,
        outputFile: File
    ): Int {
        if (confirms.isEmpty()) return 0
        outputFile.outputStream().use { os ->
            exportToStream(confirms, partId, os)
        }
        return confirms.size
    }

    /**
     * 将确认记录导出为 CSV 到输出流
     */
    fun exportToStream(
        confirms: List<ViewRoiConfirmEntity>,
        partId: String,
        outputStream: OutputStream
    ) {
        val writer = OutputStreamWriter(outputStream, Charsets.UTF_8)
        // BOM for Excel UTF-8 compatibility
        writer.write("﻿")
        // Header
        writer.write(HEADER.joinToString(",") { escapeCsv(it) })
        writer.write("\n")
        // Rows
        for (confirm in confirms) {
            val row = toCsvRow(confirm, partId)
            writer.write(row.joinToString(",") { escapeCsv(it) })
            writer.write("\n")
        }
        writer.flush()
    }

    /**
     * 将批次照片索引和真实 ROI 确认结果写入同一个 Excel 兼容 CSV。
     * 照片行不是检测结果行，ROI 字段保持为空；因此无 ROI View 不会被伪造为 PASS/FAIL。
     */
    fun exportCombinedToStream(
        photos: List<InspectionPhotoExportRow>,
        confirms: List<ViewRoiConfirmEntity>,
        partId: String,
        outputStream: OutputStream,
    ) {
        val writer = OutputStreamWriter(outputStream, Charsets.UTF_8)
        writer.write("\uFEFF")
        writer.write(COMBINED_HEADER.joinToString(",") { escapeCsv(it) })
        writer.write("\n")

        photos.forEach { photoRow ->
            writer.write(photoToCombinedRow(photoRow, partId).joinToString(",") { escapeCsv(it) })
            writer.write("\n")
        }

        val zipPathByPhotoId = photos.associate { it.photo.photoId to it.zipPath }
        confirms.forEach { confirm ->
            val row = listOf("ROI确认") + toCsvRow(confirm, partId) + listOf(
                "",
                zipPathByPhotoId[confirm.photoId].orEmpty(),
                "已确认"
            )
            writer.write(row.joinToString(",") { escapeCsv(it) })
            writer.write("\n")
        }
        writer.flush()
    }

    private fun photoToCombinedRow(
        photoRow: InspectionPhotoExportRow,
        partId: String,
    ): List<String> {
        val photo = photoRow.photo
        val roiFields = List(10) { "" }
        return listOf(
            "照片",
            File(photo.filePath).name,
            partId,
            photo.templateId.orEmpty(),
            "view_${photo.viewIndex}",
            photo.templateName.orEmpty(),
        ) + roiFields + listOf(
            DATE_FORMAT.format(Date(photo.capturedAt)),
            photoRow.zipPath,
            photoRow.status,
        )
    }

    /**
     * 生成 CSV 行字段列表
     */
    internal fun toCsvRow(confirm: ViewRoiConfirmEntity, partId: String): List<String> {
        val photoName = File(confirm.photoPath).name
        return listOf(
            photoName,
            partId,
            confirm.templateId,
            "view_${confirm.viewIndex}",
            confirm.templateName,
            confirm.roiId,
            confirm.roiTargetType ?: "未选择",
            confirm.roiNormalizedRect,
            confirm.roiNormalizedRect,
            confirm.roiPixelRect,
            confirm.softwareResult ?: "",
            confirm.humanResult,
            DATE_FORMAT.format(Date(confirm.confirmTime)),
            confirm.overallResult,
            DATE_FORMAT.format(Date(confirm.overallConfirmTime))
        )
    }

    /**
     * CSV 字段转义：含逗号、引号或换行的字段用双引号包裹
     */
    internal fun escapeCsv(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    /**
     * 生成默认 CSV 文件名
     */
    fun generateCsvFileName(partId: String, batchId: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "inspection_${partId}_${batchId.take(8)}_$ts.csv"
    }
}

/** ZIP 中一张照片的导出索引，不是新的持久化数据模型。 */
data class InspectionPhotoExportRow(
    val photo: CapturedPhotoEntity,
    val zipPath: String,
    val status: String,
)
