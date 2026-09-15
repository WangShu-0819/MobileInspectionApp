package com.wearable.inspection.mobile.data.export

import com.wearable.inspection.mobile.data.entity.CapturedPhotoEntity
import com.wearable.inspection.mobile.data.entity.DpmScanEvidenceEntity
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.data.entity.ViewRoiConfirmEntity
import org.json.JSONArray
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Excel 兼容的现场结果 CSV 导出器。 */
object InspectionExcelExporter {
    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** 历史单独确认 CSV 的列头，保留旧调用方兼容性。 */
    val HEADER: List<String> = listOf(
        "图片名称", "零件ID", "模板ID", "ViewID", "View名称", "ROI_ID", "ROI属性",
        "ROI坐标", "ROI_normalizedRect", "ROI_映射后像素坐标", "软件检测结果",
        "人工确认结果", "人工确认时间", "总体结果", "总体确认时间"
    )

    /** 历史照片/确认综合 CSV 的列头，保留旧导出兼容性。 */
    val COMBINED_HEADER: List<String> = listOf("记录类型") + HEADER + listOf(
        "拍摄时间", "ZIP路径", "照片状态"
    )

    /** 本轮统一 manifest：检测框是一对多行，不压缩成单值。 */
    val MANIFEST_HEADER: List<String> = listOf(
        "记录类型", "batchId", "partId", "photoId", "templateId", "viewIndex", "roiId",
        "图片名称", "View名称", "ROI名称", "ROI属性", "ROI_normalizedRect", "ROI_映射后像素坐标",
        "软件目标类别", "软件最高分", "业务阈值", "候选阈值", "模型版本", "模型摘要", "推理耗时ms",
        "推理状态", "模型建议", "人工最终结果", "humanChangedModel", "人工确认时间",
        "总体人工结果", "总体确认时间", "拍摄时间", "照片ZIP路径", "照片状态",
        "ROI图ZIP路径", "ROI图状态", "detectionIndex", "detectionClass", "detectionScore",
        "detectionRoiBox", "detectionImageBox", "scanSessionId", "dpmCode", "dpmDecodeSource",
        "dpmStatus", "dpmFrameZipPath", "dpmRoiZipPath", "dpmFrameStatus", "dpmRoiStatus"
    )

    fun exportToFile(confirms: List<ViewRoiConfirmEntity>, partId: String, outputFile: File): Int {
        outputFile.outputStream().use { exportToStream(confirms, partId, it) }
        return confirms.size
    }

    fun exportToStream(confirms: List<ViewRoiConfirmEntity>, partId: String, outputStream: OutputStream) {
        val writer = OutputStreamWriter(outputStream, Charsets.UTF_8)
        writer.write("\uFEFF")
        writer.write(HEADER.joinToString(",") { escapeCsv(it) })
        writer.write("\n")
        confirms.forEach {
            writer.write(toCsvRow(it, partId).joinToString(",") { value -> escapeCsv(value) })
            writer.write("\n")
        }
        writer.flush()
    }

    /** 旧的照片/确认综合导出入口。 */
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
        photos.forEach {
            writer.write(photoToCombinedRow(it, partId).joinToString(",") { value -> escapeCsv(value) })
            writer.write("\n")
        }
        val zipPathByPhotoId = photos.associate { it.photo.photoId to it.zipPath }
        confirms.forEach {
            val row = listOf("ROI确认") + toCsvRow(it, partId) + listOf("", zipPathByPhotoId[it.photoId].orEmpty(), "已确认")
            writer.write(row.joinToString(",") { value -> escapeCsv(value) })
            writer.write("\n")
        }
        writer.flush()
    }

    /** 写入统一 manifest；只消费已保存结果，不重新推理或推导总体结论。 */
    fun exportUnifiedToStream(
        batchId: String,
        partId: String,
        photos: List<InspectionPhotoExportRow>,
        roiRows: List<InspectionRoiExportRow>,
        dpmRows: List<InspectionDpmExportRow>,
        outputStream: OutputStream,
    ) {
        val writer = OutputStreamWriter(outputStream, Charsets.UTF_8)
        writer.write("\uFEFF")
        writer.write(MANIFEST_HEADER.joinToString(",") { escapeCsv(it) })
        writer.write("\n")
        photos.sortedWith(compareBy<InspectionPhotoExportRow> { it.photo.viewIndex }.thenBy { it.photo.photoId })
            .forEach { writeRow(writer, photoRow(batchId, partId, it)) }
        roiRows.sortedWith(
            compareBy<InspectionRoiExportRow> { it.photo.viewIndex }
                .thenBy { it.photo.photoId }
                .thenBy { it.roi.id }
                .thenBy { it.confirm?.id ?: Long.MAX_VALUE }
        ).forEach { row ->
            val detections = parseDetections(row.confirm?.softwareDetectionsJson)
            if (detections.isEmpty()) writeRow(writer, roiRow(batchId, partId, row, null, null))
            else detections.forEachIndexed { index, detection -> writeRow(writer, roiRow(batchId, partId, row, index, detection)) }
        }
        dpmRows.sortedWith(
            compareBy<InspectionDpmExportRow> { it.evidence.scanSessionId }
                .thenBy { it.evidence.id }
                .thenBy { it.evidence.frameTimeMs }
        ).forEach { writeRow(writer, dpmRow(batchId, partId, it)) }
        writer.flush()
    }

    private fun writeRow(writer: OutputStreamWriter, row: List<String>) {
        check(row.size == MANIFEST_HEADER.size) { "统一 manifest 列数不一致: ${row.size}" }
        writer.write(row.joinToString(",") { escapeCsv(it) })
        writer.write("\n")
    }

    private fun photoRow(batchId: String, partId: String, row: InspectionPhotoExportRow): List<String> {
        val photo = row.photo
        return manifestPrefix("照片", batchId, partId, photo, null, null) +
            List(12) { "" } + listOf(
                row.overallResult.orEmpty(),
                row.overallConfirmTime?.let { DATE_FORMAT.format(Date(it)) }.orEmpty(),
                DATE_FORMAT.format(Date(photo.capturedAt)), row.zipPath, row.status
            ) + List(15) { "" }
    }

    private fun roiRow(
        batchId: String,
        partId: String,
        row: InspectionRoiExportRow,
        detectionIndex: Int?,
        detection: DetectionCsvRow?,
    ): List<String> {
        val photo = row.photo
        val roi = row.roi
        val confirm = row.confirm
        val humanTime = confirm?.confirmTime?.let { DATE_FORMAT.format(Date(it)) }.orEmpty()
        val overallTime = confirm?.overallConfirmTime?.let { DATE_FORMAT.format(Date(it)) }.orEmpty()
        return manifestPrefix("ROI", batchId, partId, photo, roi, confirm) + listOf(
            confirm?.softwareTargetClass.orEmpty(), confirm?.softwareScore?.toString().orEmpty(),
            confirm?.softwareThreshold?.toString().orEmpty(), inferCandidateThreshold(confirm?.softwareModelSummary),
            confirm?.softwareModelVersion.orEmpty(), confirm?.softwareModelSummary.orEmpty(),
            confirm?.softwareElapsedMs?.toString().orEmpty(), confirm?.softwareStatus ?: "未执行",
            confirm?.softwareResult.orEmpty(), confirm?.humanResult ?: "人工未确认",
            confirm?.humanChangedModel?.toString().orEmpty(), humanTime,
            confirm?.overallResult.orEmpty(), overallTime, DATE_FORMAT.format(Date(photo.capturedAt)),
            "", "已由照片行记录", "", "缺失：未持久化",
            detectionIndex?.toString().orEmpty(), detection?.className.orEmpty(), detection?.score.orEmpty(),
            detection?.roiBox.orEmpty(), detection?.imageBox.orEmpty()
        ) + List(8) { "" }
    }

    private fun dpmRow(batchId: String, partId: String, row: InspectionDpmExportRow): List<String> {
        val e = row.evidence
        return listOf(
            "DPM", batchId, partId, e.photoId?.toString().orEmpty(), e.templateId.orEmpty(),
            e.viewIndex?.toString().orEmpty(), e.roiId.orEmpty(), "", "", "", "", "", "", "", "", "", "", "", "", "",
            "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
            "",
            e.scanSessionId, e.decodedContent.orEmpty(), e.decodeSource.orEmpty(), e.status,
            row.frameZipPath, row.roiZipPath, row.frameStatus, row.roiStatus
        )
    }

    private fun manifestPrefix(
        type: String,
        batchId: String,
        partId: String,
        photo: CapturedPhotoEntity,
        roi: RoiDefinitionEntity?,
        confirm: ViewRoiConfirmEntity?,
    ): List<String> = listOf(
        type, batchId, partId, photo.photoId.toString(), photo.templateId.orEmpty(), photo.viewIndex.toString(),
        roi?.id ?: confirm?.roiId.orEmpty(), File(photo.filePath).name, photo.templateName.orEmpty(),
        roi?.name ?: confirm?.roiName.orEmpty(), roi?.targetType ?: confirm?.roiTargetType ?: "未选择",
        roi?.normalizedRect ?: confirm?.roiNormalizedRect.orEmpty(), confirm?.roiPixelRect.orEmpty()
    )

    private fun parseDetections(json: String?): List<DetectionCsvRow> = runCatching {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    DetectionCsvRow(
                        className = item.optString("className"),
                        score = item.optDouble("score").takeIf { !it.isNaN() }?.toString().orEmpty(),
                        roiBox = item.optJSONObject("roiBox")?.toString().orEmpty(),
                        imageBox = item.optJSONObject("imageBox")?.toString().orEmpty(),
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun inferCandidateThreshold(summary: String?): String = runCatching {
        summary?.let { org.json.JSONObject(it).optDouble("candidateThreshold").toString() }.orEmpty()
    }.getOrDefault("")

    internal fun toCsvRow(confirm: ViewRoiConfirmEntity, partId: String): List<String> = listOf(
        File(confirm.photoPath).name, partId, confirm.templateId, "view_${confirm.viewIndex}", confirm.templateName,
        confirm.roiId, confirm.roiTargetType ?: "未选择", confirm.roiNormalizedRect, confirm.roiNormalizedRect,
        confirm.roiPixelRect, confirm.softwareResult ?: "", confirm.humanResult,
        DATE_FORMAT.format(Date(confirm.confirmTime)), confirm.overallResult,
        DATE_FORMAT.format(Date(confirm.overallConfirmTime))
    )

    internal fun escapeCsv(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n')) "\"${value.replace("\"", "\"\"")}\"" else value

    fun generateCsvFileName(partId: String, batchId: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "inspection_${partId}_${batchId.take(8)}_$ts.csv"
    }

    private fun photoToCombinedRow(photoRow: InspectionPhotoExportRow, partId: String): List<String> {
        val photo = photoRow.photo
        return listOf("照片", File(photo.filePath).name, partId, photo.templateId.orEmpty(), "view_${photo.viewIndex}", photo.templateName.orEmpty()) +
            List(10) { "" } + listOf(DATE_FORMAT.format(Date(photo.capturedAt)), photoRow.zipPath, photoRow.status)
    }
}

private data class DetectionCsvRow(val className: String, val score: String, val roiBox: String, val imageBox: String)

/** ZIP 导出期间构造的 ROI 关联行，不是新的持久化模型。 */
data class InspectionRoiExportRow(val photo: CapturedPhotoEntity, val roi: RoiDefinitionEntity, val confirm: ViewRoiConfirmEntity?)

/** ZIP 导出期间构造的 DPM 文件索引，不是新的持久化模型。 */
data class InspectionDpmExportRow(
    val evidence: DpmScanEvidenceEntity,
    val frameZipPath: String,
    val frameStatus: String,
    val roiZipPath: String,
    val roiStatus: String,
)

/** ZIP 中一张照片的导出索引，不是新的持久化数据模型。 */
data class InspectionPhotoExportRow(
    val photo: CapturedPhotoEntity,
    val zipPath: String,
    val status: String,
    val overallResult: String? = null,
    val overallConfirmTime: Long? = null,
)
