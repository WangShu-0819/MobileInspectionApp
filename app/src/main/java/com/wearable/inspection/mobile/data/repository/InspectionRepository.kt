package com.wearable.inspection.mobile.data.repository

import android.content.Context
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
    private val viewRoiConfirmDao: ViewRoiConfirmDao
) {
    private val imageStore: MobileImageStore by lazy { MobileImageStore(context) }
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
     * 1. 查询该批次所有照片记录（用于获取文件路径）
     * 2. 删除批次记录（CASCADE 自动删除 captured_photos 和 view_roi_confirms）
     * 3. 删除照片实际文件
     *
     * @return 实际删除的照片文件数量
     * @throws IllegalArgumentException 如果批次不存在
     */
    suspend fun deleteCaptureBatchCompletely(batchId: String): Int {
        val batch = captureBatchDao.getById(batchId)
            ?: throw IllegalArgumentException("批次不存在: $batchId")

        // 获取照片路径（在删除 DB 记录之前）
        val photos = capturedPhotoDao.getByBatchId(batchId)
        val filePaths = photos.map { it.filePath }

        // 删除批次记录（CASCADE 自动清理 captured_photos 和 view_roi_confirms）
        captureBatchDao.deleteById(batchId)

        // 删除照片实际文件
        var deletedFileCount = 0
        for (path in filePaths) {
            try {
                val file = File(path)
                if (file.exists() && file.delete()) {
                    deletedFileCount++
                }
            } catch (_: SecurityException) {
                // 文件删除失败不阻塞整体操作
            }
        }
        return deletedFileCount
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

    suspend fun getConfirmedViewIndices(batchId: String): List<Int> =
        viewRoiConfirmDao.getConfirmedViewIndices(batchId)

    suspend fun insertViewRoiConfirms(confirms: List<ViewRoiConfirmEntity>) {
        viewRoiConfirmDao.insertAll(confirms)
    }

    suspend fun deleteViewRoiConfirmsByView(batchId: String, viewIndex: Int) {
        viewRoiConfirmDao.deleteByBatchAndViewIndex(batchId, viewIndex)
    }

}
