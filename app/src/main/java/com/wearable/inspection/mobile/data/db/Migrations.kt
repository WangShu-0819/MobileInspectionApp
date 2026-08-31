package com.wearable.inspection.mobile.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库 Migration v1 → v2
 *
 * 当前 v1 → v2 暂未做 schema 变更，但保留此文件作为模板。
 * 后续版本升级时，在此添加具体的 ALTER TABLE 语句。
 *
 * 示例：
 * ```kotlin
 * val MIGRATION_1_2 = object : Migration(1, 2) {
 *     override fun migrate(db: SupportSQLiteDatabase) {
 *         // db.execSQL("ALTER TABLE parts ADD COLUMN new_field TEXT")
 *     }
 * }
 * ```
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 当前版本无 schema 变更，留空
    }
}

/**
 * 数据库 Migration v2 → v3
 * 后续升级时在此实现
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // TODO: 版本升级时实现
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
