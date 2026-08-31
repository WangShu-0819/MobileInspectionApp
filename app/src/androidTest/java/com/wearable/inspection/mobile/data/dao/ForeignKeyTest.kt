package com.wearable.inspection.mobile.data.dao

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wearable.inspection.mobile.data.db.AppDatabase
import com.wearable.inspection.mobile.data.entity.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PartDao + TemplateDao + RoiDao 外键关系测试
 *
 * 验证：
 * 1. Part → Template 外键约束
 * 2. Template → ROI 外键约束
 * 3. Session → ROI Record 外键约束
 * 4. 级联删除行为（CASCADE / SET_NULL）
 */
@RunWith(AndroidJUnit4::class)
class ForeignKeyTest {

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(*Migrations.ALL_MIGRATIONS)
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun part_template_foreignKey_cascadeDelete() = runBlocking {
        // 创建零件
        val part = PartEntity(id = "part_001", name = "测试零件", model = "MODEL-001")
        db.partDao().insert(part)

        // 创建关联模板
        val template = InspectionTemplateEntity(
            id = "template_001",
            partId = "part_001",
            name = "测试模板",
            imagePath = "/path/to/image.jpg",
            outlinePath = "/path/to/outline.json",
            createdAt = System.currentTimeMillis()
        )
        db.templateDao().insert(template)

        // 验证模板存在
        val templates = db.templateDao().getByPartId("part_001")
        assertEquals(1, templates.size)
        assertEquals("template_001", templates[0].id)

        // 删除零件（应级联删除模板）
        db.partDao().deleteById("part_001")

        // 验证模板也被删除
        val templatesAfterDelete = db.templateDao().getByPartId("part_001")
        assertEquals(0, templatesAfterDelete.size)
    }

    @Test
    fun template_roi_foreignKey_cascadeDelete() = runBlocking {
        // 创建零件和模板
        val part = PartEntity(id = "part_002", name = "测试零件2", model = "MODEL-002")
        db.partDao().insert(part)

        val template = InspectionTemplateEntity(
            id = "template_002",
            partId = "part_002",
            name = "测试模板2",
            imagePath = "/path/to/image2.jpg",
            outlinePath = "/path/to/outline2.json",
            createdAt = System.currentTimeMillis()
        )
        db.templateDao().insert(template)

        // 创建 ROI
        val roi = RoiDefinitionEntity(
            id = "roi_001",
            templateId = "template_002",
            name = "测试ROI",
            x = 0.1f,
            y = 0.1f,
            width = 0.5f,
            height = 0.5f,
            inspectionType = "PRESENCE_GENERIC",
            threshold = 0.8f,
            enabled = true,
            orderIndex = 0
        )
        db.roiDao().insert(roi)

        // 验证 ROI 存在
        val rois = db.roiDao().getByTemplateId("template_002")
        assertEquals(1, rois.size)
        assertEquals("roi_001", rois[0].id)

        // 删除模板（应级联删除 ROI）
        db.templateDao().deleteById("template_002")

        // 验证 ROI 也被删除
        val roisAfterDelete = db.roiDao().getByTemplateId("template_002")
        assertEquals(0, roisAfterDelete.size)
    }

    @Test
    fun session_roiRecord_foreignKey_cascadeDelete() = runBlocking {
        // 创建完整的检测会话和 ROI 记录
        val session = InspectionSessionEntity(
            id = "session_001",
            partId = "part_001",
            templateId = "template_001",
            originalImagePath = "/path/to/original.jpg",
            resultImagePath = "/path/to/result.jpg",
            status = "PASS",
            createdAt = System.currentTimeMillis(),
            durationMs = 1000L
        )
        db.inspectionSessionDao().insert(session)

        // 创建 ROI 记录
        val roiRecord = RoiInspectionRecordEntity(
            sessionId = "session_001",
            roiId = "roi_001",
            roiName = "测试ROI",
            roiSnapshot = "{\"x\":0.1,\"y\":0.1,\"width\":0.5,\"height\":0.5}",
            inspectionType = "PRESENCE_GENERIC",
            autoStatus = "PASS",
            durationMs = 500L
        )
        db.roiRecordDao().insert(roiRecord)

        // 验证 ROI 记录存在
        val records = db.roiRecordDao().getBySessionId("session_001")
        assertEquals(1, records.size)

        // 删除会话（应级联删除 ROI 记录）
        db.inspectionSessionDao().deleteById("session_001")

        // 验证 ROI 记录也被删除
        val recordsAfterDelete = db.roiRecordDao().getBySessionId("session_001")
        assertEquals(0, recordsAfterDelete.size)
    }

    @Test
    fun roiRecord_roiId_setNullOnDelete() = runBlocking {
        // 创建会话和 ROI
        val session = InspectionSessionEntity(
            id = "session_002",
            partId = "part_001",
            templateId = "template_001",
            originalImagePath = "/path/to/original.jpg",
            resultImagePath = "/path/to/result.jpg",
            status = "PASS",
            createdAt = System.currentTimeMillis(),
            durationMs = 1000L
        )
        db.inspectionSessionDao().insert(session)

        val roi = RoiDefinitionEntity(
            id = "roi_002",
            templateId = "template_001",
            name = "测试ROI2",
            x = 0.2f,
            y = 0.2f,
            width = 0.3f,
            height = 0.3f,
            inspectionType = "HOLE_PRESENCE",
            threshold = 0.9f,
            enabled = true,
            orderIndex = 1
        )
        db.roiDao().insert(roi)

        val roiRecord = RoiInspectionRecordEntity(
            sessionId = "session_002",
            roiId = "roi_002",
            roiName = "测试ROI2",
            roiSnapshot = "{\"x\":0.2,\"y\":0.2,\"width\":0.3,\"height\":0.3}",
            inspectionType = "HOLE_PRESENCE",
            autoStatus = "PASS",
            durationMs = 300L
        )
        db.roiRecordDao().insert(roiRecord)

        // 删除 ROI（应 SET_NULL roiId）
        db.roiDao().deleteById("roi_002")

        // 验证 ROI 记录的 roiId 变为 null
        val records = db.roiRecordDao().getBySessionId("session_002")
        assertEquals(1, records.size)
        assertNull("roiId should be set to null", records[0].roiId)
    }
}
