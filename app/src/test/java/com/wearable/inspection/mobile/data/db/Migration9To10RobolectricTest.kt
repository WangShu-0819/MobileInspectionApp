package com.wearable.inspection.mobile.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v9→v10 Room migration 真实 SQLite 测试（Robolectric）
 *
 * 用 SQLiteOpenHelper 创建 v9 schema 全量表（从 9.json 导出），
 * 写入 capture_batches 和 view_roi_confirms 旧行数据，
 * 然后用 Room.databaseBuilder + MIGRATION_9_10 打开，验证：
 * - MIGRATION_9_10 真实执行（新增 overrideTime/roiEvidencePath 列）
 * - 旧行数据和稳定关联保留
 * - 旧行新字段为 null
 * - 新值可写入和重载
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Migration9To10RobolectricTest {

    private lateinit var context: Context
    private val dbName = "migration_9_10_test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /**
     * 创建 v9 全量 schema（从 app/schemas/9.json 导出的 CREATE TABLE 语句）。
     * view_roi_confirms 表无 overrideTime、roiEvidencePath。
     */
    private fun createV9SchemaAndData() {
        val helper = object : SQLiteOpenHelper(context, dbName, null, 9) {
            override fun onCreate(db: SQLiteDatabase) {
                // parts
                db.execSQL("""CREATE TABLE IF NOT EXISTS `parts` (
                    `id` TEXT NOT NULL, `name` TEXT NOT NULL, `model` TEXT, `dpmCode` TEXT,
                    `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`)
                )""")
                // inspection_templates
                db.execSQL("""CREATE TABLE IF NOT EXISTS `inspection_templates` (
                    `id` TEXT NOT NULL, `partId` TEXT NOT NULL, `name` TEXT NOT NULL,
                    `mainImagePath` TEXT NOT NULL, `displayOrder` INTEGER NOT NULL, `outlineData` TEXT,
                    `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `enabled` INTEGER NOT NULL,
                    PRIMARY KEY(`id`), FOREIGN KEY(`partId`) REFERENCES `parts`(`id`) ON DELETE CASCADE
                )""")
                // roi_definitions
                db.execSQL("""CREATE TABLE IF NOT EXISTS `roi_definitions` (
                    `id` TEXT NOT NULL, `templateId` TEXT NOT NULL, `name` TEXT NOT NULL,
                    `order` INTEGER NOT NULL, `shapeType` TEXT NOT NULL, `normalizedRect` TEXT NOT NULL,
                    `points` TEXT, `inspectionType` TEXT NOT NULL, `expectedValue` TEXT, `configJson` TEXT,
                    `preprocessJson` TEXT, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL,
                    `targetType` TEXT, PRIMARY KEY(`id`),
                    FOREIGN KEY(`templateId`) REFERENCES `inspection_templates`(`id`) ON DELETE CASCADE
                )""")
                // inspection_sessions
                db.execSQL("""CREATE TABLE IF NOT EXISTS `inspection_sessions` (
                    `id` TEXT NOT NULL, `partId` TEXT, `partName` TEXT, `templateId` TEXT, `templateName` TEXT,
                    `originalImagePath` TEXT NOT NULL, `annotatedImagePath` TEXT, `startTime` INTEGER NOT NULL,
                    `endTime` INTEGER, `autoOverallStatus` TEXT NOT NULL, `finalOverallStatus` TEXT,
                    `alignmentScore` REAL, `alignmentOverride` INTEGER NOT NULL, `notes` TEXT,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`partId`) REFERENCES `parts`(`id`) ON DELETE SET NULL,
                    FOREIGN KEY(`templateId`) REFERENCES `inspection_templates`(`id`) ON DELETE SET NULL
                )""")
                // roi_inspection_records
                db.execSQL("""CREATE TABLE IF NOT EXISTS `roi_inspection_records` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT NOT NULL, `roiId` TEXT,
                    `roiName` TEXT NOT NULL, `roiSnapshot` TEXT NOT NULL, `inspectionType` TEXT NOT NULL,
                    `algorithmVersion` TEXT NOT NULL, `autoStatus` TEXT NOT NULL, `finalStatus` TEXT, `score` REAL,
                    `metricsJson` TEXT, `durationMs` INTEGER NOT NULL, `roiCropPath` TEXT,
                    `preprocessedPath` TEXT, `debugImagePath` TEXT, `errorMessage` TEXT,
                    FOREIGN KEY(`sessionId`) REFERENCES `inspection_sessions`(`id`) ON DELETE CASCADE,
                    FOREIGN KEY(`roiId`) REFERENCES `roi_definitions`(`id`) ON DELETE SET NULL
                )""")
                // capture_batches
                db.execSQL("""CREATE TABLE IF NOT EXISTS `capture_batches` (
                    `batchId` TEXT NOT NULL, `partId` TEXT, `partName` TEXT, `startTime` INTEGER NOT NULL,
                    `endTime` INTEGER, `viewCount` INTEGER NOT NULL, PRIMARY KEY(`batchId`),
                    FOREIGN KEY(`partId`) REFERENCES `parts`(`id`) ON DELETE SET NULL
                )""")
                // captured_photos
                db.execSQL("""CREATE TABLE IF NOT EXISTS `captured_photos` (
                    `photoId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `batchId` TEXT NOT NULL,
                    `filePath` TEXT NOT NULL, `viewIndex` INTEGER NOT NULL, `templateId` TEXT,
                    `templateName` TEXT, `capturedAt` INTEGER NOT NULL,
                    FOREIGN KEY(`batchId`) REFERENCES `capture_batches`(`batchId`) ON DELETE CASCADE
                )""")
                // view_roi_confirms — v9 无 overrideTime、roiEvidencePath
                db.execSQL("""CREATE TABLE IF NOT EXISTS `view_roi_confirms` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `batchId` TEXT NOT NULL,
                    `photoId` INTEGER NOT NULL, `photoPath` TEXT NOT NULL, `viewIndex` INTEGER NOT NULL,
                    `templateId` TEXT NOT NULL, `templateName` TEXT NOT NULL, `roiId` TEXT NOT NULL,
                    `roiName` TEXT NOT NULL, `roiTargetType` TEXT, `roiNormalizedRect` TEXT NOT NULL,
                    `roiPixelRect` TEXT NOT NULL, `softwareResult` TEXT, `humanResult` TEXT NOT NULL,
                    `confirmTime` INTEGER NOT NULL, `overallResult` TEXT NOT NULL,
                    `overallConfirmTime` INTEGER NOT NULL, `softwareTargetClass` TEXT, `softwareScore` REAL,
                    `softwareThreshold` REAL, `softwareDetectionsJson` TEXT, `softwareStatus` TEXT,
                    `softwareModelVersion` TEXT, `softwareModelSummary` TEXT, `softwareElapsedMs` INTEGER,
                    `humanChangedModel` INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY(`batchId`) REFERENCES `capture_batches`(`batchId`) ON DELETE CASCADE
                )""")
                // dpm_scan_evidence
                db.execSQL("""CREATE TABLE IF NOT EXISTS `dpm_scan_evidence` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `scanSessionId` TEXT NOT NULL,
                    `frameTimeMs` INTEGER NOT NULL, `frameSource` TEXT NOT NULL, `decodedContent` TEXT,
                    `status` TEXT NOT NULL, `decodeSource` TEXT, `originalImagePath` TEXT NOT NULL,
                    `roiImagePath` TEXT, `createdAt` INTEGER NOT NULL, `batchId` TEXT, `partId` TEXT,
                    `templateId` TEXT, `viewIndex` INTEGER, `photoId` INTEGER, `roiId` TEXT
                )""")

                // 写入测试数据
                db.execSQL(
                    "INSERT INTO capture_batches (batchId, partId, partName, startTime, viewCount) " +
                        "VALUES ('batch-v9', 'part-1', '测试零件', 1000, 2)"
                )
                // 已改旧行（OK→NG）
                db.execSQL(
                    "INSERT INTO view_roi_confirms " +
                        "(id, batchId, photoId, photoPath, viewIndex, templateId, templateName, roiId, roiName, " +
                        "roiTargetType, roiNormalizedRect, roiPixelRect, softwareResult, humanResult, confirmTime, " +
                        "overallResult, overallConfirmTime, softwareTargetClass, softwareScore, softwareThreshold, " +
                        "softwareDetectionsJson, softwareStatus, softwareModelVersion, softwareModelSummary, " +
                        "softwareElapsedMs, humanChangedModel) " +
                        "VALUES (100, 'batch-v9', 55, '/old/photo.jpg', 0, 'tpl-0', '正面', 'roi-0', '螺纹', " +
                        "'THREAD', '{}', '{}', 'OK', 'NG', 5000, 'NG', 5001, 'THREAD', 0.85, 0.37, " +
                        "'[]', 'DETECTED', 'nanodet-v1', '{}', 30, 1)"
                )
                // 未改行（FEATURE，softwareResult=null）
                db.execSQL(
                    "INSERT INTO view_roi_confirms " +
                        "(id, batchId, photoId, photoPath, viewIndex, templateId, templateName, roiId, roiName, " +
                        "roiTargetType, roiNormalizedRect, roiPixelRect, softwareResult, humanResult, confirmTime, " +
                        "overallResult, overallConfirmTime, humanChangedModel) " +
                        "VALUES (101, 'batch-v9', 55, '/old/photo.jpg', 0, 'tpl-0', '正面', 'roi-1', '部件', " +
                        "'FEATURE', '{}', '{}', NULL, 'OK', 5002, 'OK', 5003, 0)"
                )
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        // 触发 onCreate
        helper.writableDatabase
        helper.close()
    }

    @Test
    fun migration9To10_preservesOldDataAndAddsNewFields() {
        createV9SchemaAndData()

        val dbV10 = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .addMigrations(*ALL_MIGRATIONS)
            .build()

        // 旧行保留：已改判行（id=100）
        dbV10.openHelper.readableDatabase.query(
            "SELECT batchId, photoId, viewIndex, templateId, roiId, softwareResult, humanResult, " +
                "humanChangedModel, overrideTime, roiEvidencePath FROM view_roi_confirms WHERE id = 100"
        ).use { c ->
            assertTrue("旧行应存在", c.moveToFirst())
            assertEquals("batch-v9", c.getString(c.getColumnIndexOrThrow("batchId")))
            assertEquals(55L, c.getLong(c.getColumnIndexOrThrow("photoId")))
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("viewIndex")))
            assertEquals("tpl-0", c.getString(c.getColumnIndexOrThrow("templateId")))
            assertEquals("roi-0", c.getString(c.getColumnIndexOrThrow("roiId")))
            assertEquals("OK", c.getString(c.getColumnIndexOrThrow("softwareResult")))
            assertEquals("NG", c.getString(c.getColumnIndexOrThrow("humanResult")))
            assertEquals(1, c.getInt(c.getColumnIndexOrThrow("humanChangedModel")))
            assertTrue("旧行 overrideTime 应为 null", c.isNull(c.getColumnIndexOrThrow("overrideTime")))
            assertTrue("旧行 roiEvidencePath 应为 null", c.isNull(c.getColumnIndexOrThrow("roiEvidencePath")))
        }

        // 旧行保留：未改判行（id=101，softwareResult=null）
        dbV10.openHelper.readableDatabase.query(
            "SELECT softwareResult, humanResult, humanChangedModel, overrideTime, roiEvidencePath " +
                "FROM view_roi_confirms WHERE id = 101"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("softwareResult 应为 null", c.isNull(0))
            assertEquals("OK", c.getString(1))
            assertEquals(0, c.getInt(2))
            assertTrue("overrideTime 应为 null", c.isNull(3))
            assertTrue("roiEvidencePath 应为 null", c.isNull(4))
        }

        // 新值可写入
        dbV10.openHelper.writableDatabase.execSQL(
            "INSERT INTO view_roi_confirms " +
                "(id, batchId, photoId, photoPath, viewIndex, templateId, templateName, roiId, roiName, " +
                "roiTargetType, roiNormalizedRect, roiPixelRect, softwareResult, humanResult, confirmTime, " +
                "overallResult, overallConfirmTime, humanChangedModel, overrideTime, roiEvidencePath) " +
                "VALUES (102, 'batch-v9', 56, '/new/photo.jpg', 1, 'tpl-0', '正面', 'roi-0', '螺纹', " +
                "'THREAD', '{}', '{}', 'OK', 'NG', 6000, 'NG', 6001, 1, 6002, '/managed/roi.jpg')"
        )
        dbV10.openHelper.readableDatabase.query(
            "SELECT humanChangedModel, overrideTime, roiEvidencePath FROM view_roi_confirms WHERE id = 102"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals(6002L, c.getLong(1))
            assertEquals("/managed/roi.jpg", c.getString(2))
        }

        // 稳定关联保留
        dbV10.openHelper.readableDatabase.query(
            "SELECT batchId, photoId, viewIndex, templateId, roiId FROM view_roi_confirms WHERE id = 100"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("batch-v9", c.getString(0))
            assertEquals(55L, c.getLong(1))
            assertEquals(0, c.getInt(2))
            assertEquals("tpl-0", c.getString(3))
            assertEquals("roi-0", c.getString(4))
        }

        dbV10.close()
    }
}
