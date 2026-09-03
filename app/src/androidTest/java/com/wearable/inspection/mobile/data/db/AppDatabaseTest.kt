package com.wearable.inspection.mobile.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wearable.inspection.mobile.data.dao.*
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
}
