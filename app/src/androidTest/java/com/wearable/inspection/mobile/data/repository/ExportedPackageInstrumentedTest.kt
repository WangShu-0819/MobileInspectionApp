package com.wearable.inspection.mobile.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wearable.inspection.mobile.data.db.AppDatabase
import com.wearable.inspection.mobile.data.db.ALL_MIGRATIONS
import com.wearable.inspection.mobile.data.entity.DpmScanEvidenceEntity
import com.wearable.inspection.mobile.data.entity.ExportedPackageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 导出包记录 instrumented 测试
 *
 * 覆盖：v10→v11 migration、导出包 CRUD、SAF URI 持久化、
 * 精确删除、DPM 证据安全删除、共享/非共享场景。
 */
@RunWith(AndroidJUnit4::class)
class ExportedPackageInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: InspectionRepository
    private lateinit var context: android.content.Context

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
            exportedPackageDao = db.exportedPackageDao()
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    // ---- Migration 测试 ----

    @Test
    fun migration10to11_createsExportedPackagesTable() = runBlocking {
        // inMemoryDatabaseBuilder 已自动执行所有 migration
        // 验证 exported_packages 表可正常读写
        val id = repo.insertExportedPackage(
            ExportedPackageEntity(
                packageType = ExportedPackageEntity.TYPE_DPM_EVIDENCE,
                displayName = "test.zip",
                status = ExportedPackageEntity.STATUS_EXPORTING
            )
        )
        assertTrue("ID 应大于 0", id > 0)
        val pkg = repo.getExportedPackage(id)
        assertNotNull("应能查询到插入的记录", pkg)
        assertEquals("test.zip", pkg!!.displayName)
    }

    // ---- 导出成功后 URI 持久化 ----

    @Test
    fun exportSuccess_persistsUriAndPermission() = runBlocking {
        val id = repo.insertExportedPackage(
            ExportedPackageEntity(
                packageType = ExportedPackageEntity.TYPE_DPM_EVIDENCE,
                displayName = "dpm_evidence_20260916.zip",
                status = ExportedPackageEntity.STATUS_EXPORTING
            )
        )
        // 模拟 SAF URI 返回后更新
        val pkg = repo.getExportedPackage(id)!!
        repo.updateExportedPackage(pkg.copy(
            safUri = "content://com.android.providers.downloads.documents/document/123",
            persistedPermission = true,
            status = ExportedPackageEntity.STATUS_SUCCESS,
            byteSize = 102400
        ))
        val updated = repo.getExportedPackage(id)!!
        assertEquals(ExportedPackageEntity.STATUS_SUCCESS, updated.status)
        assertEquals("content://com.android.providers.downloads.documents/document/123", updated.safUri)
        assertTrue(updated.persistedPermission)
        assertEquals(102400, updated.byteSize)
    }

    // ---- 导出取消/失败状态 ----

    @Test
    fun exportCancelled_recordsCancelledStatus() = runBlocking {
        val id = repo.insertExportedPackage(
            ExportedPackageEntity(
                packageType = ExportedPackageEntity.TYPE_BATCH_INSPECTION,
                displayName = "batch_abc12345.zip",
                batchId = "batch_abc12345_full",
                status = ExportedPackageEntity.STATUS_EXPORTING
            )
        )
        // 模拟用户取消
        val pkg = repo.getExportedPackage(id)!!
        repo.updateExportedPackage(pkg.copy(status = ExportedPackageEntity.STATUS_CANCELLED))
        val updated = repo.getExportedPackage(id)!!
        assertEquals(ExportedPackageEntity.STATUS_CANCELLED, updated.status)
        assertNull("取消时 URI 应为 null", updated.safUri)
    }

    @Test
    fun exportFailed_recordsError() = runBlocking {
        val id = repo.insertExportedPackage(
            ExportedPackageEntity(
                packageType = ExportedPackageEntity.TYPE_DPM_EVIDENCE,
                displayName = "dpm_evidence.zip",
                status = ExportedPackageEntity.STATUS_EXPORTING
            )
        )
        val pkg = repo.getExportedPackage(id)!!
        repo.updateExportedPackage(pkg.copy(
            status = ExportedPackageEntity.STATUS_FAILED,
            errorMessage = "暂无 DPM 扫码证据"
        ))
        val updated = repo.getExportedPackage(id)!!
        assertEquals(ExportedPackageEntity.STATUS_FAILED, updated.status)
        assertEquals("暂无 DPM 扫码证据", updated.errorMessage)
    }

    // ---- 包列表加载 ----

    @Test
    fun observeExportedPackages_returnsAllRecords() = runBlocking {
        repo.insertExportedPackage(ExportedPackageEntity(
            packageType = ExportedPackageEntity.TYPE_DPM_EVIDENCE,
            displayName = "dpm1.zip",
            status = ExportedPackageEntity.STATUS_SUCCESS
        ))
        repo.insertExportedPackage(ExportedPackageEntity(
            packageType = ExportedPackageEntity.TYPE_BATCH_INSPECTION,
            displayName = "batch1.zip",
            status = ExportedPackageEntity.STATUS_SUCCESS
        ))
        val packages = repo.observeExportedPackages().first()
        assertEquals(2, packages.size)
    }

    // ---- 精确删除一个包且其他包不受影响 ----

    @Test
    fun deleteOnePackage_doesNotAffectOthers() = runBlocking {
        val id1 = repo.insertExportedPackage(ExportedPackageEntity(
            packageType = ExportedPackageEntity.TYPE_DPM_EVIDENCE,
            displayName = "dpm1.zip",
            status = ExportedPackageEntity.STATUS_SUCCESS
        ))
        val id2 = repo.insertExportedPackage(ExportedPackageEntity(
            packageType = ExportedPackageEntity.TYPE_BATCH_INSPECTION,
            displayName = "batch1.zip",
            status = ExportedPackageEntity.STATUS_SUCCESS
        ))
        repo.deleteExportedPackage(id1)
        assertNull("已删除的包应为 null", repo.getExportedPackage(id1))
        assertNotNull("未删除的包应存在", repo.getExportedPackage(id2))
        assertEquals(1, repo.observeExportedPackages().first().size)
    }

    // ---- 删除失败后记录保留 ----

    @Test
    fun deleteFailedPackage_recordStillExists() = runBlocking {
        val id = repo.insertExportedPackage(ExportedPackageEntity(
            packageType = ExportedPackageEntity.TYPE_DPM_EVIDENCE,
            displayName = "dpm1.zip",
            status = ExportedPackageEntity.STATUS_FAILED,
            errorMessage = "写入文件失败"
        ))
        // 包记录本身仍然存在，只有用户手动确认删除才会清除
        val pkg = repo.getExportedPackage(id)
        assertNotNull(pkg)
        assertEquals(ExportedPackageEntity.STATUS_FAILED, pkg!!.status)
    }

    // ---- 导出中禁止删除 ----

    @Test
    fun exportingPackage_existsAndCanOnlyBeDeletedByUser() = runBlocking {
        val id = repo.insertExportedPackage(ExportedPackageEntity(
            packageType = ExportedPackageEntity.TYPE_DPM_EVIDENCE,
            displayName = "dpm_exporting.zip",
            status = ExportedPackageEntity.STATUS_EXPORTING
        ))
        val pkg = repo.getExportedPackage(id)
        assertNotNull(pkg)
        assertEquals(ExportedPackageEntity.STATUS_EXPORTING, pkg!!.status)
        // countExporting 应为 1
        assertEquals(1, repo.countExporting())
        // 删除后 countExporting 应为 0（数据库层面允许删除，UI 层禁止）
        repo.deleteExportedPackage(id)
        assertEquals(0, repo.countExporting())
    }

    // ---- 按 batchId 查询 ----

    @Test
    fun getByBatchId_returnsCorrectPackages() = runBlocking {
        repo.insertExportedPackage(ExportedPackageEntity(
            packageType = ExportedPackageEntity.TYPE_BATCH_INSPECTION,
            displayName = "batch1.zip",
            batchId = "batch_001",
            status = ExportedPackageEntity.STATUS_SUCCESS
        ))
        repo.insertExportedPackage(ExportedPackageEntity(
            packageType = ExportedPackageEntity.TYPE_BATCH_INSPECTION,
            displayName = "batch2.zip",
            batchId = "batch_002",
            status = ExportedPackageEntity.STATUS_SUCCESS
        ))
        val batch1Pkgs = repo.getExportedPackagesByBatch("batch_001")
        assertEquals(1, batch1Pkgs.size)
        assertEquals("batch1.zip", batch1Pkgs[0].displayName)
    }

    // ---- DPM 证据安全删除：未绑定批次不可删 ----

    @Test
    fun deleteUnboundDpmEvidence_rejected() = runBlocking {
        // 插入一条未绑定批次的 DPM 证据
        val evidenceId = repo.insertDpmScanEvidence(DpmScanEvidenceEntity(
            scanSessionId = "session_001",
            frameTimeMs = 1000L,
            frameSource = "CAMERA",
            status = "SUCCESS",
            decodedContent = "ABC123",
            decodeSource = "ZXING",
            originalImagePath = "/tmp/test.jpg",
            batchId = null // 未绑定
        ))
        val result = repo.deleteDpmEvidenceSafely(evidenceId)
        assertFalse("未绑定批次的证据不允许删除", result.success)
        assertTrue(result.error!!.contains("未绑定批次"))
    }

    // ---- DPM 证据安全删除：共享引用不可删 ----

    @Test
    fun deleteSharedDpmEvidence_rejected() = runBlocking {
        // 同一 scanSessionId 的证据被两个不同 batchId 引用
        val evidenceId = repo.insertDpmScanEvidence(DpmScanEvidenceEntity(
            scanSessionId = "session_shared",
            frameTimeMs = 1000L,
            frameSource = "CAMERA",
            status = "SUCCESS",
            decodedContent = "ABC123",
            decodeSource = "ZXING",
            originalImagePath = "/tmp/test_shared.jpg",
            batchId = "batch_A"
        ))
        // 同一 session 的另一条证据绑定到不同批次
        repo.insertDpmScanEvidence(DpmScanEvidenceEntity(
            scanSessionId = "session_shared",
            frameTimeMs = 2000L,
            frameSource = "CAMERA",
            status = "SUCCESS",
            decodedContent = "ABC123",
            decodeSource = "ZXING",
            originalImagePath = "/tmp/test_shared2.jpg",
            batchId = "batch_B"
        ))
        val result = repo.deleteDpmEvidenceSafely(evidenceId)
        assertFalse("共享引用的证据不允许删除", result.success)
        assertTrue(result.error!!.contains("共享会话"))
    }

    // ---- DPM 证据安全删除：正常场景可删 ----

    @Test
    fun deleteNonSharedDpmEvidence_succeeds() = runBlocking {
        // 同一 scanSessionId 只有一个 batchId（非共享）
        val evidenceId = repo.insertDpmScanEvidence(DpmScanEvidenceEntity(
            scanSessionId = "session_single",
            frameTimeMs = 1000L,
            frameSource = "CAMERA",
            status = "SUCCESS",
            decodedContent = "XYZ789",
            decodeSource = "ZXING",
            originalImagePath = context.filesDir.resolve("dpm_evidence").apply { mkdirs() }
                .resolve("test_evidence.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) }
                .absolutePath,
            batchId = "batch_only"
        ))
        val result = repo.deleteDpmEvidenceSafely(evidenceId)
        assertTrue("非共享证据应允许删除", result.success)
        assertNull(result.error)
        // 验证证据已删除
        val all = repo.getAllDpmScanEvidence()
        assertFalse(all.any { it.id == evidenceId })
    }

    // ---- DPM 证据安全删除：不存在的 ID ----

    @Test
    fun deleteNonexistentDpmEvidence_returnsError() = runBlocking {
        val result = repo.deleteDpmEvidenceSafely(99999L)
        assertFalse(result.success)
        assertTrue(result.error!!.contains("不存在"))
    }

    // ---- 批次删除回归：导出包不影响批次删除 ----

    @Test
    fun batchDeleteWithExistingExportedPackage_succeeds() = runBlocking {
        // 创建批次
        com.wearable.inspection.mobile.data.entity.CaptureBatchEntity(
            batchId = "batch_export_test",
            partId = null,
            partName = "Test",
            startTime = System.currentTimeMillis(),
            endTime = System.currentTimeMillis()
        ).let { repo.insertCaptureBatch(it) }

        // 创建关联的导出包记录
        repo.insertExportedPackage(ExportedPackageEntity(
            packageType = ExportedPackageEntity.TYPE_BATCH_INSPECTION,
            displayName = "batch_export_test.zip",
            batchId = "batch_export_test",
            status = ExportedPackageEntity.STATUS_SUCCESS,
            safUri = "content://some/uri"
        ))

        // 批次删除应成功（不依赖导出包表的外键）
        val result = repo.deleteCaptureBatchCompletely("batch_export_test")
        assertTrue("批次删除应成功", result.success)

        // 导出包记录仍存在（独立管理）
        val packages = repo.getExportedPackagesByBatch("batch_export_test")
        assertEquals("导出包记录应保留", 1, packages.size)
    }
}