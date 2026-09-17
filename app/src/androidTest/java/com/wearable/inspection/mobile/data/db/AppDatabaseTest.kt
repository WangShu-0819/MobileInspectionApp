package com.wearable.inspection.mobile.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wearable.inspection.mobile.data.dao.*
import com.wearable.inspection.mobile.data.entity.CaptureBatchEntity
import com.wearable.inspection.mobile.data.entity.ViewRoiConfirmEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AppDatabase 基础测试
 *
 * 验证：
 * 1. 数据库创建成功
 * 2. 所有 DAO 可访问
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {

    private val migrationHelper by lazy {
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
        )
    }

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun database_createsSuccessfully() {
        assertNotNull("Database should be created", db)
    }

    @Test
    fun allDaos_areAccessible() {
        assertNotNull("PartDao should be accessible", db.partDao())
        assertNotNull("TemplateDao should be accessible", db.templateDao())
        assertNotNull("RoiDao should be accessible", db.roiDao())
        assertNotNull("InspectionSessionDao should be accessible", db.inspectionSessionDao())
        assertNotNull("RoiRecordDao should be accessible", db.roiRecordDao())
    }

    @Test
    fun migration1To2_backfillsStableDisplayOrder() {
        val databaseName = "template_order_migration_test"
        migrationHelper.createDatabase(databaseName, 1).apply {
            execSQL("INSERT INTO parts (id, name, createdAt, updatedAt) VALUES ('p', '测试零件', 1, 1)")
            execSQL(
                "INSERT INTO inspection_templates " +
                    "(id, partId, name, mainImagePath, createdAt, updatedAt, enabled) " +
                    "VALUES ('b', 'p', 'B', '/b.jpg', 100, 100, 1)"
            )
            execSQL(
                "INSERT INTO inspection_templates " +
                    "(id, partId, name, mainImagePath, createdAt, updatedAt, enabled) " +
                    "VALUES ('a', 'p', 'A', '/a.jpg', 100, 100, 1)"
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            2,
            true,
            MIGRATION_1_2,
        )
        migrated.query(
            "SELECT id, displayOrder FROM inspection_templates WHERE partId = 'p' ORDER BY displayOrder ASC"
        ).use { cursor ->
            assertEquals(2, cursor.count)
            assertEquals(true, cursor.moveToFirst())
            assertEquals("a", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(true, cursor.moveToNext())
            assertEquals("b", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
        }
        migrated.close()
    }

    @Test
    fun migration7To8_keepsManualRowsAndLeavesOldModelUnexecuted() {
        val databaseName = "view_roi_model_result_migration_test"
        migrationHelper.createDatabase(databaseName, 7).apply {
            execSQL(
                "INSERT INTO capture_batches (batchId, partId, partName, startTime, viewCount) " +
                    "VALUES ('batch-v7', NULL, '旧批次', 10, 1)"
            )
            execSQL(
                "INSERT INTO view_roi_confirms " +
                    "(id, batchId, photoId, photoPath, viewIndex, templateId, templateName, roiId, roiName, " +
                    "roiTargetType, roiNormalizedRect, roiPixelRect, softwareResult, humanResult, confirmTime, " +
                    "overallResult, overallConfirmTime) " +
                    "VALUES (4, 'batch-v7', 77, '/old/photo.jpg', 1, 'tpl-old', '旧视角', 'roi-old', '旧 ROI', " +
                    "'THREAD', '{}', '{}', 'OK', 'NG', 123, 'OK', 124)"
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            8,
            true,
            MIGRATION_7_8,
        )
        migrated.query(
            "SELECT batchId, photoId, viewIndex, templateId, roiId, softwareResult, humanResult, " +
                "overallResult, softwareTargetClass, softwareScore, softwareThreshold, softwareDetectionsJson, " +
                "softwareStatus, softwareModelVersion, softwareModelSummary, softwareElapsedMs, humanChangedModel " +
                "FROM view_roi_confirms WHERE id = 4"
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("batch-v7", cursor.getString(0))
            assertEquals(77L, cursor.getLong(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals("tpl-old", cursor.getString(3))
            assertEquals("roi-old", cursor.getString(4))
            assertEquals(null, cursor.getString(5))
            assertEquals("NG", cursor.getString(6))
            assertEquals("OK", cursor.getString(7))
            for (column in 8..15) assertEquals(true, cursor.isNull(column))
            assertEquals(0, cursor.getInt(16))
        }
        migrated.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(databaseName)
    }

    @Test
    fun migration8To9_keepsHistoricalDpmEvidenceUnassociated() {
        val databaseName = "dpm_association_migration_test"
        migrationHelper.createDatabase(databaseName, 8).apply {
            execSQL(
                "INSERT INTO dpm_scan_evidence " +
                    "(id, scanSessionId, frameTimeMs, frameSource, decodedContent, status, decodeSource, " +
                    "originalImagePath, roiImagePath, createdAt) " +
                    "VALUES (11, 'old-session', 22, 'CAMERA', 'OLD-CODE', 'SUCCESS', 'ZXING', '/old.jpg', NULL, 33)"
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            9,
            true,
            MIGRATION_8_9,
        )
        migrated.query(
            "SELECT scanSessionId, status, decodedContent, batchId, partId, templateId, viewIndex, photoId, roiId " +
                "FROM dpm_scan_evidence WHERE id = 11"
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("old-session", cursor.getString(0))
            assertEquals("SUCCESS", cursor.getString(1))
            assertEquals("OLD-CODE", cursor.getString(2))
            for (column in 3..8) assertEquals(true, cursor.isNull(column))
        }
        migrated.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(databaseName)
    }

    @Test
    fun migration9To10_preservesExistingDataAndAddsOverrideFields() {
        val databaseName = "roi_override_migration_test"
        migrationHelper.createDatabase(databaseName, 9).apply {
            execSQL(
                "INSERT INTO capture_batches (batchId, partId, partName, startTime, viewCount) " +
                    "VALUES ('batch-v9', NULL, '旧批次', 10, 1)"
            )
            // v9 确认数据：无 overrideTime、roiEvidencePath 列
            execSQL(
                "INSERT INTO view_roi_confirms " +
                    "(id, batchId, photoId, photoPath, viewIndex, templateId, templateName, roiId, roiName, " +
                    "roiTargetType, roiNormalizedRect, roiPixelRect, softwareResult, humanResult, confirmTime, " +
                    "overallResult, overallConfirmTime, softwareTargetClass, softwareScore, softwareThreshold, " +
                    "softwareDetectionsJson, softwareStatus, softwareModelVersion, softwareModelSummary, " +
                    "softwareElapsedMs, humanChangedModel) " +
                    "VALUES (100, 'batch-v9', 55, '/old/photo.jpg', 0, 'tpl-0', '正面', 'roi-0', '螺纹 ROI', " +
                    "'THREAD', '{}', '{}', 'OK', 'NG', 5000, 'NG', 5001, 'THREAD', 0.85, 0.37, " +
                    "'[]', 'DETECTED', 'nanodet-v1', '{}', 30, 1)"
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            10,
            true,
            MIGRATION_9_10,
        )

        // 验证旧行数据保留，新字段为 null
        migrated.query(
            "SELECT batchId, photoId, viewIndex, templateId, roiId, softwareResult, humanResult, " +
                "humanChangedModel, overrideTime, roiEvidencePath " +
                "FROM view_roi_confirms WHERE id = 100"
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("batch-v9", cursor.getString(0))
            assertEquals(55L, cursor.getLong(1))
            assertEquals(0, cursor.getInt(2))
            assertEquals("tpl-0", cursor.getString(3))
            assertEquals("roi-0", cursor.getString(4))
            assertEquals("OK", cursor.getString(5))
            assertEquals("NG", cursor.getString(6))
            assertEquals(1, cursor.getInt(7)) // humanChangedModel = true
            assertEquals(true, cursor.isNull(8)) // overrideTime = null (旧行)
            assertEquals(true, cursor.isNull(9)) // roiEvidencePath = null (旧行)
        }

        // 验证新值可写入
        migrated.execSQL(
            "INSERT INTO view_roi_confirms " +
                "(id, batchId, photoId, photoPath, viewIndex, templateId, templateName, roiId, roiName, " +
                "roiTargetType, roiNormalizedRect, roiPixelRect, softwareResult, humanResult, confirmTime, " +
                "overallResult, overallConfirmTime, humanChangedModel, overrideTime, roiEvidencePath) " +
                "VALUES (101, 'batch-v9', 56, '/new/photo.jpg', 0, 'tpl-0', '正面', 'roi-0', '螺纹 ROI', " +
                "'THREAD', '{}', '{}', 'OK', 'NG', 6000, 'NG', 6001, 1, 6002, '/managed/roi_evidence.jpg')"
        )
        migrated.query(
            "SELECT humanChangedModel, overrideTime, roiEvidencePath " +
                "FROM view_roi_confirms WHERE id = 101"
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(6002L, cursor.getLong(1))
            assertEquals("/managed/roi_evidence.jpg", cursor.getString(2))
        }

        // 验证稳定关联字段保留
        migrated.query(
            "SELECT batchId, photoId, viewIndex, templateId, roiId FROM view_roi_confirms WHERE id = 100"
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("batch-v9", cursor.getString(0))
            assertEquals(55L, cursor.getLong(1))
            assertEquals(0, cursor.getInt(2))
            assertEquals("tpl-0", cursor.getString(3))
            assertEquals("roi-0", cursor.getString(4))
        }

        migrated.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(databaseName)
    }

    @Test
    fun confirmationRows_reloadByBatchAndPhotoStableAssociation() = runBlocking {
        db.captureBatchDao().insert(CaptureBatchEntity("batch-reload", null, "零件"))
        val first = ViewRoiConfirmEntity(
            batchId = "batch-reload",
            photoId = 101,
            photoPath = "/capture/101.jpg",
            viewIndex = 2,
            templateId = "template-2",
            templateName = "侧面",
            roiId = "roi-1",
            roiName = "螺纹",
            roiTargetType = "THREAD",
            roiNormalizedRect = "{}",
            roiPixelRect = "{}",
            softwareResult = "OK",
            humanResult = "NG",
            confirmTime = 1000,
            overallResult = "OK",
            overallConfirmTime = 1001,
            softwareTargetClass = "THREAD",
            softwareScore = 0.91f,
            softwareThreshold = 0.37f,
            softwareDetectionsJson = "[{\"className\":\"thread\"}]",
            softwareStatus = "DETECTED",
            softwareModelVersion = "nanodet-opt2",
            softwareModelSummary = "{\"outputBlob\":\"out0\"}",
            softwareElapsedMs = 33,
            humanChangedModel = true
        )
        val sameViewOtherPhoto = first.copy(
            id = 0,
            photoId = 102,
            photoPath = "/capture/102.jpg",
            humanResult = "OK",
            humanChangedModel = false
        )
        db.viewRoiConfirmDao().insertAll(listOf(first, sameViewOtherPhoto))

        val reloaded = db.viewRoiConfirmDao().getByBatchAndPhoto("batch-reload", 101)

        assertEquals(1, reloaded.size)
        assertEquals("batch-reload", reloaded.single().batchId)
        assertEquals(101L, reloaded.single().photoId)
        assertEquals("template-2", reloaded.single().templateId)
        assertEquals(2, reloaded.single().viewIndex)
        assertEquals("roi-1", reloaded.single().roiId)
        assertEquals("OK", reloaded.single().softwareResult)
        assertEquals("NG", reloaded.single().humanResult)
        assertEquals("OK", reloaded.single().overallResult)
        assertEquals("[{\"className\":\"thread\"}]", reloaded.single().softwareDetectionsJson)
        assertEquals(true, reloaded.single().humanChangedModel)
    }
}
