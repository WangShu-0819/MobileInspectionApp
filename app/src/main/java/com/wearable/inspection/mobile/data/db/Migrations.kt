package com.wearable.inspection.mobile.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库 Migration v1 → v2
 *
 * 为模板增加稳定的视角顺序。旧数据没有顺序字段，因此按
 * createdAt ASC、id ASC 回填，保证已有模板在升级后仍有确定顺序。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE inspection_templates " +
                "ADD COLUMN displayOrder INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            """
            UPDATE inspection_templates
            SET displayOrder = (
                SELECT COUNT(*)
                FROM inspection_templates AS previous
                WHERE previous.partId = inspection_templates.partId
                  AND (
                      previous.createdAt < inspection_templates.createdAt
                      OR (
                          previous.createdAt = inspection_templates.createdAt
                          AND previous.id < inspection_templates.id
                      )
                  )
            )
            """.trimIndent()
        )
    }
}

/**
 * 数据库 Migration v2 → v3
 * 后续升级时在此实现
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE roi_inspection_records " +
                "ADD COLUMN debugImagePath TEXT"
        )
    }
}

/**
 * 数据库 Migration v3 → v4
 *
 * 新增采集批次和已采集照片表，支持按零件+批次导出照片。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS capture_batches (
                batchId TEXT NOT NULL PRIMARY KEY,
                partId TEXT,
                partName TEXT,
                startTime INTEGER NOT NULL,
                endTime INTEGER,
                viewCount INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (partId) REFERENCES parts(id) ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS captured_photos (
                photoId INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                batchId TEXT NOT NULL,
                filePath TEXT NOT NULL,
                viewIndex INTEGER NOT NULL,
                templateId TEXT,
                templateName TEXT,
                capturedAt INTEGER NOT NULL,
                FOREIGN KEY (batchId) REFERENCES capture_batches(batchId) ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }
}

/**
 * 数据库 Migration v4 → v5
 *
 * 为 ROI 增加目标属性类型字段（targetType），支持螺纹/螺母/部件检测算法路由。
 * 旧 ROI 的 targetType 为 null，显示"未选择"。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE roi_definitions " +
                "ADD COLUMN targetType TEXT"
        )
    }
}

/**
 * 数据库 Migration v5 → v6
 *
 * 新增 view_roi_confirms 表，支持单零件多 View 人工确认与 ZIP 导出。
 * 每个 ROI 一条记录，包含人工 OK/NG 结果、总体结果和确认时间。
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS view_roi_confirms (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                batchId TEXT NOT NULL,
                photoId INTEGER NOT NULL,
                photoPath TEXT NOT NULL,
                viewIndex INTEGER NOT NULL,
                templateId TEXT NOT NULL,
                templateName TEXT NOT NULL,
                roiId TEXT NOT NULL,
                roiName TEXT NOT NULL,
                roiTargetType TEXT,
                roiNormalizedRect TEXT NOT NULL,
                roiPixelRect TEXT NOT NULL,
                softwareResult TEXT,
                humanResult TEXT NOT NULL,
                confirmTime INTEGER NOT NULL,
                overallResult TEXT NOT NULL,
                overallConfirmTime INTEGER NOT NULL,
                FOREIGN KEY (batchId) REFERENCES capture_batches(batchId) ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }
}

/**
 * 所有 Migration 列表
 * 新增 Migration 时必须在此添加
 */
@JvmField
val ALL_MIGRATIONS = arrayOf<Migration>(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6
)
