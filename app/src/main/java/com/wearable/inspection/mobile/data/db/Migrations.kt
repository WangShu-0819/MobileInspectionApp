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
 * 所有 Migration 列表
 * 新增 Migration 时必须在此添加
 */
@JvmField
val ALL_MIGRATIONS = arrayOf<Migration>(
    MIGRATION_1_2,
    MIGRATION_2_3
)
