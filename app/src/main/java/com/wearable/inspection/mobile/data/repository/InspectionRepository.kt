package com.wearable.inspection.mobile.data.repository

import android.content.Context
import android.util.Log
import androidx.core.content.FileProvider
import com.wearable.inspection.mobile.data.db.AppDatabase
import com.wearable.inspection.mobile.data.dao.*
import com.wearable.inspection.mobile.data.entity.*
import com.wearable.inspection.mobile.data.image.MobileImageStore
import com.wearable.inspection.mobile.data.image.StoredImageResult
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

class InspectionRepository(
    private val database: AppDatabase,
    private val context: Context,
    private val partDao: PartDao,
    private val templateDao: TemplateDao,
    private val roiDao: RoiDao,
    private val sessionDao: InspectionSessionDao,
    private val roiRecordDao: RoiRecordDao,
    private val captureBatchDao: CaptureBatchDao,
    private val capturedPhotoDao: CapturedPhotoDao,
    private val viewRoiConfirmDao: ViewRoiConfirmDao,
    private val dpmScanEvidenceDao: DpmScanEvidenceDao
) {
    private val imageStore: MobileImageStore by lazy { MobileImageStore(context) }
    private val filesDir: java.io.File get() = context.filesDir
    // ---- 零件 ----

    fun observeParts(): Flow<List<PartEntity>> = partDao.observeAll()

    suspend fun getParts(): List<PartEntity> = partDao.getAll()

    suspend fun getPartById(id: String): PartEntity? = partDao.getById(id)

    suspend fun getPartByDpmCode(dpmCode: String): PartEntity? =
        partDao.getByDpmCode(dpmCode.trim())

    suspend fun updateDpmCode(partId: String, dpmCode: String?) {
        partDao.updateDpmCode(partId, dpmCode?.trim()?.ifBlank { null })
    }

    suspend fun upsertPart(part: PartEntity) {
        partDao.insert(part)
    }

    suspend fun deletePart(partId: String) {
        deleteTemplatePackage(partId)
    }

    // ---- 模板 ----

    fun observeTemplates(partId: String): Flow<List<InspectionTemplateEntity>> =
        templateDao.observeByPartId(partId)

    fun observeAllTemplates(): Flow<List<InspectionTemplateEntity>> =
        templateDao.observeAll()

    suspend fun getAllTemplates(): List<InspectionTemplateEntity> =
        templateDao.getAll()

    suspend fun getTemplate(id: String): InspectionTemplateEntity? = templateDao.getById(id)

    suspend fun getTemplatesByPart(partId: String): List<InspectionTemplateEntity> =
        templateDao.getByPartId(partId)

    suspend fun insertTemplate(template: InspectionTemplateEntity) {
        templateDao.insert(template)
    }

    suspend fun updateTemplate(template: InspectionTemplateEntity) {
        templateDao.update(template)
    }

    suspend fun deleteTemplate(id: String) {
        templateDao.deleteById(id)
    }

    /**
     * 删除一个零件的模板包：按稳定 partId 删除零件、模板、ROI 和受管理模板图片。
     * 采集批次使用 SET_NULL 外键，因此历史采集照片和批次记录不会被删除。
     */
    suspend fun deleteTemplatePackage(partId: String) {
        partDao.getById(partId) ?: throw IllegalArgumentException("零件不存在: $partId")
        val imagePaths = templateDao.getByPartId(partId)
            .map { it.mainImagePath }
            .distinct()

        partDao.deleteById(partId)
        imagePaths.forEach { deleteTemplateImage(it) }
    }

    // ---- ROI ----

    fun observeRois(templateId: String): Flow<List<RoiDefinitionEntity>> =
        roiDao.observeByTemplateId(templateId)

    suspend fun getRois(templateId: String): List<RoiDefinitionEntity> =
        roiDao.getByTemplateId(templateId)

    suspend fun insertRoi(roi: RoiDefinitionEntity) {
        roiDao.insert(roi)
    }

    suspend fun insertRois(rois: List<RoiDefinitionEntity>) {
        roiDao.insertAll(rois)
    }

    suspend fun updateRoi(roi: RoiDefinitionEntity) {
        roiDao.update(roi)
    }

    suspend fun deleteRoi(id: String) {
        roiDao.deleteById(id)
    }

    // ---- 检测会话 ----

    fun observeSessions(): Flow<List<InspectionSessionEntity>> = sessionDao.observeAll()

    suspend fun getSession(id: String): InspectionSessionEntity? = sessionDao.getById(id)

    suspend fun insertSession(session: InspectionSessionEntity) {
        sessionDao.insert(session)
    }

    suspend fun updateSession(session: InspectionSessionEntity) {
        sessionDao.update(session)
    }

    // ---- ROI 检测记录 ----

    fun observeRoiRecords(sessionId: String): Flow<List<RoiInspectionRecordEntity>> =
        roiRecordDao.observeBySessionId(sessionId)

    suspend fun getRoiRecords(sessionId: String): List<RoiInspectionRecordEntity> =
        roiRecordDao.getBySessionId(sessionId)

    suspend fun insertRoiRecord(record: RoiInspectionRecordEntity) {
        roiRecordDao.insert(record)
    }

    suspend fun insertRoiRecords(records: List<RoiInspectionRecordEntity>) {
        roiRecordDao.insertAll(records)
    }

    // ---- 采集批次 ----

    fun observeCaptureBatches(): Flow<List<CaptureBatchEntity>> = captureBatchDao.observeAll()

    fun observeCaptureBatchesSince(sinceMillis: Long): Flow<List<CaptureBatchEntity>> =
        captureBatchDao.observeByStartTimeSince(sinceMillis)

    suspend fun getCaptureBatch(batchId: String): CaptureBatchEntity? =
        captureBatchDao.getById(batchId)

    suspend fun insertCaptureBatch(batch: CaptureBatchEntity) {
        captureBatchDao.insert(batch)
    }

    suspend fun updateCaptureBatch(batch: CaptureBatchEntity) {
        captureBatchDao.update(batch)
    }

    suspend fun finishCaptureBatch(batchId: String) {
        val batch = captureBatchDao.getById(batchId)
            ?: throw IllegalArgumentException("批次不存在: $batchId")
        if (batch.endTime == null) {
            captureBatchDao.update(batch.copy(endTime = System.currentTimeMillis()))
        }
    }

    suspend fun deleteCaptureBatch(batchId: String) {
        captureBatchDao.deleteById(batchId)
    }

    /**
     * 完全删除采集批次及其关联数据和文件
     *
     * 1. 按精确 batchId 校验批次存在
     * 2. 快照所有受管理文件路径（现场照片 + ROI 证据图）
     * 3. 验证路径在受管理目录内（canonical path 精确父目录比较）
     * 4. 删除所有受管理文件（文件不存在视为幂等成功）
     * 5. 删除 DB 行（CASCADE 自动清理 captured_photos 和 view_roi_confirms）
     * 6. 回读确认 batch、照片和确认记录均已删除
     *
     * 文件清理未完成时不得删除批次数据库行。
     * DPM 证据行和文件完全不动（无 FK，独立管理）。
     * 不扫描或删除 cacheDir、externalFilesDir 或 SAF 中的 ZIP。
     *
     * @return 删除结果（成功/失败及详细计数）
     * @throws IllegalArgumentException 如果批次不存在
     */
    suspend fun deleteCaptureBatchCompletely(batchId: String): BatchDeletionResult {
        // 1. 校验批次存在
        val batch = captureBatchDao.getById(batchId)
            ?: throw IllegalArgumentException("批次不存在: $batchId")

        // 2. 快照所有受管理文件路径
        val photos = capturedPhotoDao.getByBatchId(batchId)
        val confirms = viewRoiConfirmDao.getByBatchId(batchId)
        val dpmEvidence = dpmScanEvidenceDao.getByBatchId(batchId)

        val allPaths = buildList {
            addAll(photos.map { it.filePath })
            addAll(confirms.mapNotNull { it.roiEvidencePath.takeUnless { p -> p.isNullOrBlank() } })
            // DPM 证据路径仅记录用于日志，不删除
            dpmEvidence.forEach { e ->
                Log.d(TAG, "DPM 证据保留不动: id=${e.id} batchId=${e.batchId} file=${e.originalImagePath}")
            }
        }.distinct()

        // 3. 验证路径在受管理目录内，记录越界路径
        val capturesDirCanonical = java.io.File(filesDir, "captures").canonicalPath
        val roiEvidenceDirCanonical = java.io.File(filesDir, "roi_evidence").canonicalPath
        val outOfBounds = mutableListOf<String>()
        for (path in allPaths) {
            try {
                val canonical = java.io.File(path).canonicalPath
                val inCaptures = canonical.startsWith(capturesDirCanonical + java.io.File.separator) ||
                    canonical == capturesDirCanonical
                val inRoiEvidence = canonical.startsWith(roiEvidenceDirCanonical + java.io.File.separator) ||
                    canonical == roiEvidenceDirCanonical
                if (!inCaptures && !inRoiEvidence) {
                    outOfBounds += path
                }
            } catch (e: Exception) {
                outOfBounds += "$path (canonical 解析失败: ${e.message})"
            }
        }
        if (outOfBounds.isNotEmpty()) {
            val msg = "路径越界，拒绝删除: ${outOfBounds.joinToString("; ")}"
            Log.e(TAG, msg)
            return BatchDeletionResult(false, 0, 0, 0, 0, msg)
        }

        // 4. 删除受管理文件（文件不存在视为幂等成功）
        var deletedPhotos = 0
        var deletedRoiEvidence = 0
        var missingFiles = 0
        val failedDeletions = mutableListOf<String>()
        for (path in allPaths) {
            try {
                val file = java.io.File(path)
                if (!file.exists()) {
                    missingFiles++
                    continue
                }
                if (file.delete()) {
                    if (path in photos.map { it.filePath }) deletedPhotos++
                    else deletedRoiEvidence++
                } else {
                    failedDeletions += path
                }
            } catch (e: SecurityException) {
                failedDeletions += "$path (权限不足: ${e.message})"
            } catch (e: Exception) {
                failedDeletions += "$path (异常: ${e.message})"
            }
        }
        if (failedDeletions.isNotEmpty()) {
            val msg = "文件删除失败，保留批次记录: ${failedDeletions.joinToString("; ")}"
            Log.e(TAG, msg)
            return BatchDeletionResult(false, deletedPhotos, deletedRoiEvidence, missingFiles, 0, msg)
        }

        // 5. 所有文件已清理，删除 DB 行（CASCADE 自动清理 captured_photos 和 view_roi_confirms）
        captureBatchDao.deleteById(batchId)

        // 6. 回读确认 batch 已不存在（CASCADE 应确保照片和确认记录同时清理）
        val batchStillExists = captureBatchDao.getById(batchId)
        if (batchStillExists != null) {
            throw IllegalStateException("批次删除后仍存在于数据库: $batchId")
        }

        Log.i(TAG, "批次删除完成: batchId=$batchId, 照片文件=$deletedPhotos, " +
            "ROI 证据文件=$deletedRoiEvidence, 缺失文件=$missingFiles, " +
            "ZIP=无受管理 ZIP 文件")
        return BatchDeletionResult(true, deletedPhotos, deletedRoiEvidence, missingFiles, 0, null)
    }

    companion object {
        private const val TAG = "InspectionRepository"
    }

    // ---- 已采集照片 ----

    fun observeCapturedPhotos(batchId: String): Flow<List<CapturedPhotoEntity>> =
        capturedPhotoDao.observeByBatchId(batchId)

    suspend fun getCapturedPhotos(batchId: String): List<CapturedPhotoEntity> =
        capturedPhotoDao.getByBatchId(batchId)

    suspend fun getCapturedPhoto(photoId: Long): CapturedPhotoEntity? =
        capturedPhotoDao.getById(photoId)

    suspend fun insertCapturedPhoto(photo: CapturedPhotoEntity): Long {
        return capturedPhotoDao.insert(photo)
    }

    // ---- 排序 ----

    /**
     * 批量更新模板 displayOrder
     *
     * @param orders (templateId, newDisplayOrder) 列表
     */
    suspend fun reorderTemplates(orders: List<Pair<String, Int>>) {
        templateDao.reorderTemplates(orders)
    }

    // ---- 模板图片存储 ----

    /**
     * 存储模板拍摄图片
     *
     * 使用 template_images/ 目录，与采集图片 captures/ 分离。
     * @return 存储结果，失败返回 null
     */
    fun storeTemplateImage(tempFile: File): StoredImageResult? {
        return imageStore.storeTemplateImage(tempFile)
    }

    /**
     * 删除模板图片文件
     */
    fun deleteTemplateImage(path: String) {
        imageStore.deleteTemplateImage(path)
    }

    /**
     * 生成临时文件
     */
    fun generateTempFile(): File = imageStore.generateTempFile()

    /**
     * 删除临时文件
     */
    fun deleteTempFile(file: File) = imageStore.deleteTempFile(file)

    // ---- 统计 ----

    suspend fun partCount(): Int = partDao.count()

    suspend fun templateCount(): Int = templateDao.count()

    suspend fun roiCount(): Int = roiDao.count()

    // ---- 工具方法 ----

    suspend fun seedIfEmpty() {
        if (partDao.count() > 0) return

        // 插入预设零件
        val defaultParts = listOf(
            PartEntity(id = "part_001", name = "示例零件A", model = "MODEL-A"),
            PartEntity(id = "part_002", name = "示例零件B", model = "MODEL-B")
        )
        defaultParts.forEach { partDao.insert(it) }
    }

    fun getOutputDirectory(subdir: String = ""): File {
        val dir = File(context.getExternalFilesDir(null), subdir)
        dir.mkdirs()
        return dir
    }

    fun getFileUri(file: File): android.net.Uri? {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    // ---- View ROI 人工确认 ----

    fun observeViewRoiConfirms(batchId: String): Flow<List<ViewRoiConfirmEntity>> =
        viewRoiConfirmDao.observeByBatchId(batchId)

    suspend fun getViewRoiConfirms(batchId: String): List<ViewRoiConfirmEntity> =
        viewRoiConfirmDao.getByBatchId(batchId)

    suspend fun getViewRoiConfirmsByView(batchId: String, viewIndex: Int): List<ViewRoiConfirmEntity> =
        viewRoiConfirmDao.getByBatchAndViewIndex(batchId, viewIndex)

    suspend fun getViewRoiConfirmsByPhoto(batchId: String, photoId: Long): List<ViewRoiConfirmEntity> =
        viewRoiConfirmDao.getByBatchAndPhoto(batchId, photoId)

    suspend fun getConfirmedViewIndices(batchId: String): List<Int> =
        viewRoiConfirmDao.getConfirmedViewIndices(batchId)

    suspend fun insertViewRoiConfirms(confirms: List<ViewRoiConfirmEntity>) {
        viewRoiConfirmDao.insertAll(confirms)
    }

    suspend fun replaceViewRoiConfirmsForPhoto(
        batchId: String,
        photoId: Long,
        confirms: List<ViewRoiConfirmEntity>
    ) {
        viewRoiConfirmDao.replaceByBatchAndPhoto(batchId, photoId, confirms)
    }

    suspend fun deleteViewRoiConfirmsByView(batchId: String, viewIndex: Int) {
        viewRoiConfirmDao.deleteByBatchAndViewIndex(batchId, viewIndex)
    }

    // ---- DPM 扫码会话图像证据 ----

    suspend fun insertDpmScanEvidence(evidence: DpmScanEvidenceEntity): Long =
        dpmScanEvidenceDao.insert(evidence)

    suspend fun getDpmScanEvidenceBySession(sessionId: String): List<DpmScanEvidenceEntity> =
        dpmScanEvidenceDao.getBySessionId(sessionId)

    suspend fun getAllDpmScanEvidence(): List<DpmScanEvidenceEntity> =
        dpmScanEvidenceDao.getAll()

    suspend fun getDpmScanEvidenceByBatch(batchId: String): List<DpmScanEvidenceEntity> =
        dpmScanEvidenceDao.getByBatchId(batchId)

    /**
     * 将指定扫码会话下所有成功、未绑定批次的证据批量关联到新创建的采集批次。
     * 幂等：重复调用不覆盖已绑定行。
     *
     * @return 实际更新的行数
     */
    suspend fun bindDpmScanSessionToBatch(scanSessionId: String, batchId: String): Int {
        android.util.Log.w("DpmBinding", "[DIAG-5] Repository.bindDpmScanSessionToBatch: sessionId=$scanSessionId, batchId=$batchId")
        // 先检查当前 evidence 状态
        val rows = dpmScanEvidenceDao.getBySessionId(scanSessionId)
        android.util.Log.w("DpmBinding", "[DIAG-5] pre-bind check: sessionId=$scanSessionId, totalRows=${rows.size}, batchIds=${rows.map { it.batchId }}, statuses=${rows.map { it.status }}")
        val updated = dpmScanEvidenceDao.bindSessionToBatch(scanSessionId, batchId)
        android.util.Log.w("DpmBinding", "[DIAG-5] post-bind: updated=$updated rows")
        return updated
    }

}

/**
 * 批次完全删除结果
 *
 * @property success 删除是否成功
 * @property deletedPhotos 实际删除的现场照片文件数
 * @property deletedRoiEvidence 实际删除的 ROI 证据图文件数
 * @property missingFiles 文件不存在（幂等）的数量
 * @property deletedZips 删除的 ZIP 文件数（本轮始终为 0，无受管理 ZIP）
 * @property error 失败原因（成功时为 null）
 */
data class BatchDeletionResult(
    val success: Boolean,
    val deletedPhotos: Int,
    val deletedRoiEvidence: Int,
    val missingFiles: Int,
    val deletedZips: Int,
    val error: String?
)
