package com.wearable.inspection.mobile.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wearable.inspection.mobile.data.db.ALL_MIGRATIONS
import com.wearable.inspection.mobile.data.db.AppDatabase
import com.wearable.inspection.mobile.data.entity.PartEntity
import com.wearable.inspection.mobile.data.repository.InspectionRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DPM 绑定 DAO 集成测试。
 *
 * 只验证持久化边界：查询按码命中正确 Part，以及更新只修改目标 Part。
 * “冲突绑定拒绝”由导航层在写入前完成，不在 DAO 中重复实现业务策略。
 */
@RunWith(AndroidJUnit4::class)
class PartDpmDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: InspectionRepository

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        repository = InspectionRepository(
            database = db,
            context = ApplicationProvider.getApplicationContext(),
            partDao = db.partDao(),
            templateDao = db.templateDao(),
            roiDao = db.roiDao(),
            sessionDao = db.inspectionSessionDao(),
            roiRecordDao = db.roiRecordDao(),
        )
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun getByDpmCode_returnsMatchingPartAndNullForUnknownCode() = runBlocking {
        val matching = PartEntity(id = "part_a", name = "零件 A", dpmCode = "DM-A")
        db.partDao().insert(matching)
        db.partDao().insert(PartEntity(id = "part_b", name = "零件 B"))

        val found = db.partDao().getByDpmCode("DM-A")

        assertEquals(matching.id, found?.id)
        assertEquals(matching.name, found?.name)
        assertEquals(matching.dpmCode, found?.dpmCode)
        assertNull(db.partDao().getByDpmCode("DM-UNKNOWN"))
    }

    @Test
    fun updateDpmCode_updatesOnlyTargetPartAndTimestamp() = runBlocking {
        val first = PartEntity(id = "part_a", name = "零件 A", dpmCode = "DM-OLD", updatedAt = 10L)
        val second = PartEntity(id = "part_b", name = "零件 B", dpmCode = "DM-B", updatedAt = 20L)
        db.partDao().insert(first)
        db.partDao().insert(second)

        db.partDao().updateDpmCode("part_a", "DM-NEW", updatedAt = 99L)

        val updated = db.partDao().getById("part_a")
        val untouched = db.partDao().getById("part_b")
        assertEquals("DM-NEW", updated?.dpmCode)
        assertEquals(99L, updated?.updatedAt)
        assertEquals(second, untouched)
        assertEquals("part_a", db.partDao().getByDpmCode("DM-NEW")?.id)
        assertNull(db.partDao().getByDpmCode("DM-OLD"))
    }

    @Test
    fun repository_normalizesDpmCodeOnLookupAndUpdate() = runBlocking {
        db.partDao().insert(PartEntity(id = "part_a", name = "零件 A", dpmCode = "DM-A"))

        assertEquals("part_a", repository.getPartByDpmCode("  DM-A  ")?.id)

        repository.updateDpmCode("part_a", "  DM-B  ")
        assertEquals("part_a", repository.getPartByDpmCode("DM-B")?.id)

        repository.updateDpmCode("part_a", "   ")
        assertNull(db.partDao().getById("part_a")?.dpmCode)
    }
}
