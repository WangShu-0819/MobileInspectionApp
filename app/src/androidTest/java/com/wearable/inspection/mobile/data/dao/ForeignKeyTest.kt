package com.wearable.inspection.mobile.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wearable.inspection.mobile.data.db.AppDatabase
import com.wearable.inspection.mobile.data.db.ALL_MIGRATIONS
import com.wearable.inspection.mobile.data.entity.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 外键约束集成测试（真机验证）
 *
 * 验证 Room 数据库的外键级联删除和 SET NULL 行为。
 */
@RunWith(AndroidJUnit4::class)
class ForeignKeyTest {

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        )
            .addMigrations(*ALL_MIGRATIONS)
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    /**
     * 创建 Session 所需的前置数据：Part + Template + RoiDefinition（可选）
     */
    private suspend fun createPrerequisites(
        partId: String = "part_001",
        templateId: String = "template_001",
        roiId: String? = null
    ) {
        db.partDao().insert(
            PartEntity(
                id = partId,
                name = "Test Part"
            )
        )
        db.templateDao().insert(
            InspectionTemplateEntity(
                id = templateId,
                partId = partId,
                name = "Test Template",
                mainImagePath = "/data/templates/test.jpg"
            )
        )
        if (roiId != null) {
            db.roiDao().insert(
                RoiDefinitionEntity(
                    id = roiId,
                    templateId = templateId,
                    name = "Test ROI",
                    order = 0,
                    normalizedRect = "{\"left\":0.1,\"top\":0.2,\"right\":0.9,\"bottom\":0.8}",
                    inspectionType = "SURFACE"
                )
            )
        }
    }

    @Test
    fun template_partForeignKey_cascadeDelete() = runBlocking {
        createPrerequisites()

        db.templateDao().insert(
            InspectionTemplateEntity(
                id = "template_002",
                partId = "part_001",
                name = "另一个模板",
                mainImagePath = "/data/templates/t2.jpg"
            )
        )

        db.partDao().deleteById("part_001")

        val templates = db.templateDao().getByPartId("part_001")
        assertTrue("Part 删除后关联模板应被级联删除", templates.isEmpty())
    }

    @Test
    fun session_partForeignKey_setNullOnDelete() = runBlocking {
        createPrerequisites()

        db.inspectionSessionDao().insert(
            InspectionSessionEntity(
                id = "session_001",
                templateId = "template_001",
                partId = "part_001",
                partName = "Test Part",
                templateName = "Test Template",
                originalImagePath = "/data/sessions/session_001/original.jpg",
                autoOverallStatus = "PENDING"
            )
        )

        db.partDao().deleteById("part_001")

        val found = db.inspectionSessionDao().getById("session_001")
        assertNotNull("Session 应保留（Part FK 是 SET_NULL）", found)
        assertNull("partId 应被置为 null", found?.partId)
    }

    @Test
    fun session_templateForeignKey_setNullOnDelete() = runBlocking {
        createPrerequisites()

        db.inspectionSessionDao().insert(
            InspectionSessionEntity(
                id = "session_002",
                templateId = "template_001",
                partId = "part_001",
                partName = "Test Part",
                templateName = "Test Template",
                originalImagePath = "/data/sessions/session_002/original.jpg",
                autoOverallStatus = "PENDING"
            )
        )

        db.templateDao().deleteById("template_001")

        val found = db.inspectionSessionDao().getById("session_002")
        assertNotNull("Session 应保留", found)
        assertNull("templateId 应被置为 null", found?.templateId)
    }

    @Test
    fun session_roiRecord_foreignKey_cascadeDelete() = runBlocking {
        createPrerequisites(roiId = "roi_001")

        db.inspectionSessionDao().insert(
            InspectionSessionEntity(
                id = "session_003",
                templateId = "template_001",
                partId = "part_001",
                partName = "Test Part",
                templateName = "Test Template",
                originalImagePath = "/data/sessions/session_003/original.jpg",
                autoOverallStatus = "PENDING"
            )
        )

        db.roiRecordDao().insert(
            RoiInspectionRecordEntity(
                sessionId = "session_003",
                roiId = "roi_001",
                roiName = "Test ROI",
                roiSnapshot = "{\"left\":0.1,\"top\":0.2,\"right\":0.9,\"bottom\":0.8}",
                inspectionType = "SURFACE",
                autoStatus = "PASS",
                durationMs = 150L
            )
        )

        val records = db.roiRecordDao().getBySessionId("session_003")
        assertEquals("应有 1 条 ROI 记录", 1, records.size)

        db.inspectionSessionDao().deleteById("session_003")

        val recordsAfterDelete = db.roiRecordDao().getBySessionId("session_003")
        assertTrue("Session 删除后关联 ROI 记录应被级联删除", recordsAfterDelete.isEmpty())
    }

    @Test
    fun roiRecord_roiId_setNullOnDelete() = runBlocking {
        createPrerequisites(roiId = "roi_002")

        db.inspectionSessionDao().insert(
            InspectionSessionEntity(
                id = "session_004",
                templateId = "template_001",
                partId = "part_001",
                partName = "Test Part",
                templateName = "Test Template",
                originalImagePath = "/data/sessions/session_004/original.jpg",
                autoOverallStatus = "PENDING"
            )
        )

        db.roiRecordDao().insert(
            RoiInspectionRecordEntity(
                sessionId = "session_004",
                roiId = "roi_002",
                roiName = "Test ROI 2",
                roiSnapshot = "{\"left\":0.1,\"top\":0.2,\"right\":0.9,\"bottom\":0.8}",
                inspectionType = "SURFACE",
                autoStatus = "FAIL",
                durationMs = 200L
            )
        )

        db.roiDao().deleteById("roi_002")

        val records = db.roiRecordDao().getBySessionId("session_004")
        assertEquals("ROI 记录应保留", 1, records.size)
        assertNull("roiId 应被置为 null", records[0].roiId)
    }
}
