package com.wearable.inspection.mobile.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wearable.inspection.mobile.data.db.AppDatabase
import com.wearable.inspection.mobile.data.db.ALL_MIGRATIONS
import com.wearable.inspection.mobile.data.entity.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 采集批次完全删除 instrumented 测试
 *
 * 使用真实 AppDatabase + InspectionRepository + 应用 filesDir，
 * 验证 Room CASCADE、文件清理、路径安全和 DPM 保留。
 */
@RunWith(AndroidJUnit4::class)
class BatchDeleteInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: InspectionRepository
    private lateinit var context: android.content.Context

    private lateinit var capturesDir: File
    private lateinit var roiEvidenceDir: File
    private lateinit var dpmEvidenceDir: File

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        repo = InspectionRepository(
            database = db,
            context = context,
            partDao = db.partDao(),
            templateDao = db.templateDao(),
            roiDao = db.roiDao(),
            sessionDao = db.inspectionSessionDao(),
            roiRecordDao = db.roiRecordDao(),
            captureBatchDao = db.captureBatchDao(),
            capturedPhotoDao = db.capturedPhotoDao(),
            viewRoiConfirmDao = db.viewRoiConfirmDao(),
            dpmScanEvidenceDao = db.dpmScanEvidenceDao(),
        )
        capturesDir = File(context.filesDir, "captures").apply { mkdirs() }
        roiEvidenceDir = File(context.filesDir, "roi_evidence").apply { mkdirs() }
        dpmEvidenceDir = File(context.filesDir, "dpm_evidence").apply { mkdirs() }
    }

    @After
    fun teardown() {
        // 清理测试文件
        capturesDir.listFiles()?.forEach { it.delete() }
        roiEvidenceDir.listFiles()?.forEach { it.delete() }
        dpmEvidenceDir.listFiles()?.forEach { it.delete() }
        db.close()
    }

    // ---- 辅助方法 ----

    private fun createBatch(batchId: String, partId: String? = null) {
        runBlocking {
            // 确保 partId 对应的零件存在（FK 约束）
            if (partId != null) {
                db.partDao().insert(PartEntity(id = partId, name = "测试零件"))
            }
            db.captureBatchDao().insert(
                CaptureBatchEntity(
                    batchId = batchId,
                    partId = partId,
                    partName = "测试零件",
                    viewCount = 2
                )
            )
        }
    }

    private fun createPhotoFile(dir: File, name: String): File {
        val file = File(dir, name)
        file.writeBytes(ByteArray(100) { it.toByte() })
        return file
    }

    private fun insertPhoto(batchId: String, filePath: String, viewIndex: Int = 0): Long {
        return runBlocking {
            db.capturedPhotoDao().insert(
                CapturedPhotoEntity(
                    batchId = batchId,
                    filePath = filePath,
                    viewIndex = viewIndex,
                    templateId = "tpl_1",
                    templateName = "视角1"
                )
            )
        }
    }

    private fun insertConfirm(batchId: String, photoId: Long, roiEvidencePath: String? = null): Long {
        return runBlocking {
            db.viewRoiConfirmDao().insertAll(
                listOf(
                    ViewRoiConfirmEntity(
                        batchId = batchId,
                        photoId = photoId,
                        photoPath = "/dummy",
                        viewIndex = 0,
                        templateId = "tpl_1",
                        templateName = "视角1",
                        roiId = "roi_1",
                        roiName = "ROI-1",
                        roiTargetType = "THREAD",
                        roiNormalizedRect = "{}",
                        roiPixelRect = "{}",
                        humanResult = "OK",
                        confirmTime = 1000,
                        overallResult = "OK",
                        overallConfirmTime = 1001,
                        roiEvidencePath = roiEvidencePath,
                    )
                )
            )
            // 返回插入的 ID
            db.viewRoiConfirmDao().getByBatchId(batchId).last().id
        }
    }

    private fun insertDpmEvidence(batchId: String?, imagePath: File): Long {
        return runBlocking {
            db.dpmScanEvidenceDao().insert(
                DpmScanEvidenceEntity(
                    scanSessionId = "session_1",
                    frameTimeMs = 1000,
                    frameSource = "CAMERA",
                    decodedContent = "CODE-123",
                    status = "SUCCESS",
                    decodeSource = "ZXING",
                    originalImagePath = imagePath.absolutePath,
                    batchId = batchId,
                )
            )
        }
    }

    // ---- 测试 ----

    @Test
    fun deleteBatch_removesCaptureBatchRow() = runBlocking {
        createBatch("batch_1")
        assertNotNull(db.captureBatchDao().getById("batch_1"))

        repo.deleteCaptureBatchCompletely("batch_1")

        assertNull("capture_batches 行应被删除", db.captureBatchDao().getById("batch_1"))
    }

    @Test
    fun deleteBatch_cascadeDeletesCapturedPhotos() = runBlocking {
        createBatch("batch_cascade")
        val photoFile = createPhotoFile(capturesDir, "cascade_photo.jpg")
        insertPhoto("batch_cascade", photoFile.absolutePath, 0)
        insertPhoto("batch_cascade", photoFile.absolutePath, 1)
        assertEquals(2, db.capturedPhotoDao().getByBatchId("batch_cascade").size)

        repo.deleteCaptureBatchCompletely("batch_cascade")

        assertTrue(
            "captured_photos 应通过 CASCADE 删除",
            db.capturedPhotoDao().getByBatchId("batch_cascade").isEmpty()
        )
    }

    @Test
    fun deleteBatch_cascadeDeletesViewRoiConfirms() = runBlocking {
        createBatch("batch_confirm")
        val photoFile = createPhotoFile(capturesDir, "confirm_photo.jpg")
        val photoId = insertPhoto("batch_confirm", photoFile.absolutePath)
        insertConfirm("batch_confirm", photoId)
        assertFalse(db.viewRoiConfirmDao().getByBatchId("batch_confirm").isEmpty())

        repo.deleteCaptureBatchCompletely("batch_confirm")

        assertTrue(
            "view_roi_confirms 应通过 CASCADE 删除",
            db.viewRoiConfirmDao().getByBatchId("batch_confirm").isEmpty()
        )
    }

    @Test
    fun deleteBatch_deletesCapturesFiles() = runBlocking {
        createBatch("batch_files")
        val photo1 = createPhotoFile(capturesDir, "batch_files_1.jpg")
        val photo2 = createPhotoFile(capturesDir, "batch_files_2.jpg")
        insertPhoto("batch_files", photo1.absolutePath, 0)
        insertPhoto("batch_files", photo2.absolutePath, 1)
        assertTrue(photo1.exists())
        assertTrue(photo2.exists())

        repo.deleteCaptureBatchCompletely("batch_files")

        assertFalse("现场照片文件 1 应被删除", photo1.exists())
        assertFalse("现场照片文件 2 应被删除", photo2.exists())
    }

    @Test
    fun deleteBatch_deletesRoiEvidenceFiles() = runBlocking {
        createBatch("batch_roi")
        val photoFile = createPhotoFile(capturesDir, "batch_roi_photo.jpg")
        val roiFile = createPhotoFile(roiEvidenceDir, "batch_roi_evidence.jpg")
        val photoId = insertPhoto("batch_roi", photoFile.absolutePath)
        insertConfirm("batch_roi", photoId, roiEvidencePath = roiFile.absolutePath)
        assertTrue(roiFile.exists())

        repo.deleteCaptureBatchCompletely("batch_roi")

        assertFalse("ROI 证据文件应被删除", roiFile.exists())
    }

    @Test
    fun deleteBatch_missingFileIsIdempotentSuccess() = runBlocking {
        createBatch("batch_missing")
        val missingPath = File(capturesDir, "nonexistent_${System.nanoTime()}.jpg").absolutePath
        insertPhoto("batch_missing", missingPath)

        val result = repo.deleteCaptureBatchCompletely("batch_missing")

        assertTrue("缺失文件应视为幂等成功", result.success)
        assertEquals("缺失文件应被计数", 1, result.missingFiles)
        assertNull(db.captureBatchDao().getById("batch_missing"))
    }

    @Test
    fun deleteBatch_outOfBoundsPathFailsAndPreservesBatch() = runBlocking {
        createBatch("batch_oob")
        val oobFile = File(context.cacheDir, "oob_photo.jpg").apply {
            writeBytes(ByteArray(50))
        }
        insertPhoto("batch_oob", oobFile.absolutePath)

        val result = repo.deleteCaptureBatchCompletely("batch_oob")

        assertFalse("路径越界应返回失败", result.success)
        assertNotNull("路径越界应保留批次记录", db.captureBatchDao().getById("batch_oob"))
        assertTrue("错误信息应包含路径越界", result.error?.contains("路径越界") == true)
        oobFile.delete(); Unit
    }

    @Test
    fun deleteBatch_otherBatchesPreserved() = runBlocking {
        createBatch("batch_target")
        createBatch("batch_other")
        val photoFile = createPhotoFile(capturesDir, "target_photo.jpg")
        insertPhoto("batch_target", photoFile.absolutePath)

        repo.deleteCaptureBatchCompletely("batch_target")

        assertNull("目标批次应被删除", db.captureBatchDao().getById("batch_target"))
        assertNotNull("其他批次应保留", db.captureBatchDao().getById("batch_other"))
    }

    @Test
    fun deleteBatch_dpmRowsAndFilesPreserved() = runBlocking {
        createBatch("batch_dpm")
        val photoFile = createPhotoFile(capturesDir, "dpm_photo.jpg")
        val dpmFile = createPhotoFile(dpmEvidenceDir, "dpm_frame.jpg")
        insertPhoto("batch_dpm", photoFile.absolutePath)
        insertDpmEvidence("batch_dpm", dpmFile)
        assertTrue(dpmFile.exists())
        val dpmBytesBefore = dpmFile.readBytes()
        val dpmRowsBefore = db.dpmScanEvidenceDao().getByBatchId("batch_dpm")
        assertFalse(dpmRowsBefore.isEmpty())

        repo.deleteCaptureBatchCompletely("batch_dpm")

        assertTrue("DPM 证据文件应保留", dpmFile.exists())
        val dpmRowsAfter = db.dpmScanEvidenceDao().getByBatchId("batch_dpm")
        assertEquals("DPM 数据库行应保留", dpmRowsBefore.size, dpmRowsAfter.size)
        assertArrayEquals("DPM 文件字节内容应不变", dpmBytesBefore, dpmFile.readBytes())
    }

    @Test
    fun deleteBatch_noResidualAfterDelete() = runBlocking {
        createBatch("batch_residual")
        val photoFile = createPhotoFile(capturesDir, "residual_photo.jpg")
        val roiFile = createPhotoFile(roiEvidenceDir, "residual_roi.jpg")
        val photoId = insertPhoto("batch_residual", photoFile.absolutePath)
        insertConfirm("batch_residual", photoId, roiEvidencePath = roiFile.absolutePath)

        repo.deleteCaptureBatchCompletely("batch_residual")

        assertNull("批次不应残留", db.captureBatchDao().getById("batch_residual"))
        assertTrue("照片记录不应残留", db.capturedPhotoDao().getByBatchId("batch_residual").isEmpty())
        assertTrue("确认记录不应残留", db.viewRoiConfirmDao().getByBatchId("batch_residual").isEmpty())
    }

    @Test
    fun deleteBatch_otherBatchPhotosPreserved() = runBlocking {
        createBatch("batch_del")
        createBatch("batch_keep")
        val delPhoto = createPhotoFile(capturesDir, "del_photo.jpg")
        val keepPhoto = createPhotoFile(capturesDir, "keep_photo.jpg")
        insertPhoto("batch_del", delPhoto.absolutePath, 0)
        insertPhoto("batch_keep", keepPhoto.absolutePath, 0)

        repo.deleteCaptureBatchCompletely("batch_del")

        assertFalse("被删批次照片应删除", delPhoto.exists())
        assertTrue("其他批次照片文件应保留", keepPhoto.exists())
        assertEquals("其他批次照片记录应保留", 1, db.capturedPhotoDao().getByBatchId("batch_keep").size)
    }

    @Test
    fun deleteBatch_templateAndRoiPreserved() = runBlocking {
        // 创建零件 → 模板 → ROI 层级
        val partId = "part_tpl"
        db.partDao().insert(PartEntity(id = partId, name = "模板零件"))
        val tplImgDir = File(context.filesDir, "template_images").apply { mkdirs() }
        val tplImgFile = createPhotoFile(tplImgDir, "tpl_img.jpg")
        db.templateDao().insert(
            InspectionTemplateEntity(
                id = "tpl_1",
                partId = partId,
                name = "视角模板",
                mainImagePath = tplImgFile.absolutePath,
            )
        )
        db.roiDao().insert(
            RoiDefinitionEntity(
                id = "roi_tpl_1",
                templateId = "tpl_1",
                name = "ROI-1",
                order = 0,
                inspectionType = "THREAD",
                normalizedRect = "{}",
            )
        )
        // 创建批次并删除
        createBatch("batch_with_tpl")
        val batchPhoto = createPhotoFile(capturesDir, "batch_with_tpl_photo.jpg")
        insertPhoto("batch_with_tpl", batchPhoto.absolutePath)

        repo.deleteCaptureBatchCompletely("batch_with_tpl")

        // 模板图片、模板和 ROI 应保留
        assertTrue("模板图片文件应保留", tplImgFile.exists())
        assertNotNull("模板 DB 行应保留", db.templateDao().getById("tpl_1"))
        val rois = db.roiDao().getByTemplateId("tpl_1")
        assertEquals("模板 ROI 应保留", 1, rois.size)
        assertEquals("roi_tpl_1", rois[0].id)
        tplImgFile.delete(); Unit
    }

    @Test
    fun deleteBatch_independentDpmPreserved() = runBlocking {
        // 独立 DPM（batchId=null）不受批次删除影响
        createBatch("batch_with_indep_dpm")
        val batchPhoto = createPhotoFile(capturesDir, "indep_photo.jpg")
        val indepDpmFile = createPhotoFile(dpmEvidenceDir, "indep_dpm.jpg")
        insertPhoto("batch_with_indep_dpm", batchPhoto.absolutePath)
        // 插入独立 DPM 证据（batchId=null）
        runBlocking {
            db.dpmScanEvidenceDao().insert(
                DpmScanEvidenceEntity(
                    scanSessionId = "indep_session",
                    frameTimeMs = 2000,
                    frameSource = "CAMERA",
                    decodedContent = "INDEPENDENT-CODE",
                    status = "SUCCESS",
                    decodeSource = "ZXING",
                    originalImagePath = indepDpmFile.absolutePath,
                    batchId = null,
                )
            )
        }
        val indepDpmBytesBefore = indepDpmFile.readBytes()
        val indepRowsBefore = db.dpmScanEvidenceDao().getAll().filter { it.batchId == null }
        assertFalse(indepRowsBefore.isEmpty())

        repo.deleteCaptureBatchCompletely("batch_with_indep_dpm")

        assertTrue("独立 DPM 文件应保留", indepDpmFile.exists())
        val indepRowsAfter = db.dpmScanEvidenceDao().getAll().filter { it.batchId == null }
        assertEquals("独立 DPM 行应保留", indepRowsBefore.size, indepRowsAfter.size)
        assertArrayEquals("独立 DPM 文件内容不变", indepDpmBytesBefore, indepDpmFile.readBytes())
    }
}
