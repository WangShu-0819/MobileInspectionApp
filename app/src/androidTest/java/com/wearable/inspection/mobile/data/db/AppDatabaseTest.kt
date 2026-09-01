package com.wearable.inspection.mobile.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wearable.inspection.mobile.data.dao.*
import org.junit.After
import org.junit.Assert.assertNotNull
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
}
