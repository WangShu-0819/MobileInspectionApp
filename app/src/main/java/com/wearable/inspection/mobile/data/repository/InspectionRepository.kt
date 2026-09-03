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
    private val capturedPhotoDao: CapturedPhotoDao
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
        partDao.deleteById(partId)
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

    suspend fun getCaptureBatch(batchId: String): CaptureBatchEntity? =
        captureBatchDao.getById(batchId)

    suspend fun insertCaptureBatch(batch: CaptureBatchEntity) {
        captureBatchDao.insert(batch)
    }

    suspend fun updateCaptureBatch(batch: CaptureBatchEntity) {
        captureBatchDao.update(batch)
    }

    suspend fun deleteCaptureBatch(batchId: String) {
        captureBatchDao.deleteById(batchId)
    }

    // ---- 已采集照片 ----

    fun observeCapturedPhotos(batchId: String): Flow<List<CapturedPhotoEntity>> =
        capturedPhotoDao.observeByBatchId(batchId)

    suspend fun getCapturedPhotos(batchId: String): List<CapturedPhotoEntity> =
        capturedPhotoDao.getByBatchId(batchId)

    suspend fun insertCapturedPhoto(photo: CapturedPhotoEntity) {
        capturedPhotoDao.insert(photo)
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

}
