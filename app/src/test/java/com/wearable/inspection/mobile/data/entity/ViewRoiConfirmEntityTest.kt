package com.wearable.inspection.mobile.data.entity

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * ViewRoiConfirmEntity 单元测试
 *
 * 覆盖：
 * - 实体字段正确性
 * - OK/NG 值保存
 * - ROI 属性序列化
 * - 时间戳正确性
 */
class ViewRoiConfirmEntityTest {

    private fun createEntity(
        humanResult: String = "OK",
        overallResult: String = "OK",
        roiTargetType: String? = "THREAD",
        softwareResult: String? = null
    ) = ViewRoiConfirmEntity(
        id = 1,
        batchId = "batch_001",
        photoId = 100,
        photoPath = "/captures/view_0_photo.jpg",
        viewIndex = 0,
        templateId = "tpl_001",
        templateName = "视角1",
        roiId = "roi_001",
        roiName = "螺纹区域",
        roiTargetType = roiTargetType,
        roiNormalizedRect = """{"left":0.1,"top":0.2,"right":0.3,"bottom":0.4}""",
        roiPixelRect = """{"left":100,"top":200,"right":300,"bottom":400}""",
        softwareResult = softwareResult,
        humanResult = humanResult,
        confirmTime = 1693824000000L,
        overallResult = overallResult,
        overallConfirmTime = 1693824005000L
    )

    @Test
    fun `entity has correct field values`() {
        val entity = createEntity()
        assertEquals("batch_001", entity.batchId)
        assertEquals(100L, entity.photoId)
        assertEquals("/captures/view_0_photo.jpg", entity.photoPath)
        assertEquals(0, entity.viewIndex)
        assertEquals("tpl_001", entity.templateId)
        assertEquals("视角1", entity.templateName)
        assertEquals("roi_001", entity.roiId)
        assertEquals("螺纹区域", entity.roiName)
    }

    @Test
    fun `humanResult OK is preserved`() {
        val entity = createEntity(humanResult = "OK")
        assertEquals("OK", entity.humanResult)
    }

    @Test
    fun `humanResult NG is preserved`() {
        val entity = createEntity(humanResult = "NG")
        assertEquals("NG", entity.humanResult)
    }

    @Test
    fun `overallResult OK is preserved`() {
        val entity = createEntity(overallResult = "OK")
        assertEquals("OK", entity.overallResult)
    }

    @Test
    fun `overallResult NG is preserved`() {
        val entity = createEntity(overallResult = "NG")
        assertEquals("NG", entity.overallResult)
    }

    @Test
    fun `roiTargetType THREAD is preserved`() {
        val entity = createEntity(roiTargetType = "THREAD")
        assertEquals("THREAD", entity.roiTargetType)
        assertEquals("螺纹", RoiTargetType.fromName(entity.roiTargetType)?.displayName)
    }

    @Test
    fun `roiTargetType NUT is preserved`() {
        val entity = createEntity(roiTargetType = "NUT")
        assertEquals("NUT", entity.roiTargetType)
        assertEquals("螺母", RoiTargetType.fromName(entity.roiTargetType)?.displayName)
    }

    @Test
    fun `roiTargetType FEATURE is preserved`() {
        val entity = createEntity(roiTargetType = "FEATURE")
        assertEquals("FEATURE", entity.roiTargetType)
        assertEquals("部件", RoiTargetType.fromName(entity.roiTargetType)?.displayName)
    }

    @Test
    fun `roiTargetType null is preserved`() {
        val entity = createEntity(roiTargetType = null)
        assertNull(entity.roiTargetType)
    }

    @Test
    fun `softwareResult null is preserved`() {
        val entity = createEntity(softwareResult = null)
        assertNull(entity.softwareResult)
    }

    @Test
    fun `roiNormalizedRect is valid JSON`() {
        val entity = createEntity()
        val obj = JSONObject(entity.roiNormalizedRect)
        assertEquals(0.1, obj.getDouble("left"), 0.001)
        assertEquals(0.2, obj.getDouble("top"), 0.001)
        assertEquals(0.3, obj.getDouble("right"), 0.001)
        assertEquals(0.4, obj.getDouble("bottom"), 0.001)
    }

    @Test
    fun `roiPixelRect is valid JSON`() {
        val entity = createEntity()
        val obj = JSONObject(entity.roiPixelRect)
        assertEquals(100, obj.getInt("left"))
        assertEquals(200, obj.getInt("top"))
        assertEquals(300, obj.getInt("right"))
        assertEquals(400, obj.getInt("bottom"))
    }

    @Test
    fun `confirmTime is positive`() {
        val entity = createEntity()
        assertTrue(entity.confirmTime > 0)
    }

    @Test
    fun `overallConfirmTime is positive`() {
        val entity = createEntity()
        assertTrue(entity.overallConfirmTime > 0)
    }

    @Test
    fun `NG result does not prevent entity creation`() {
        // 验证 NG 结果可以正常创建实体，不会被丢弃
        val entity = createEntity(humanResult = "NG", overallResult = "NG")
        assertEquals("NG", entity.humanResult)
        assertEquals("NG", entity.overallResult)
        assertTrue(entity.id >= 0)
    }

    @Test
    fun `mixed OK NG results are independent`() {
        val roiOkOverallNg = createEntity(humanResult = "OK", overallResult = "NG")
        assertEquals("OK", roiOkOverallNg.humanResult)
        assertEquals("NG", roiOkOverallNg.overallResult)

        val roiNgOverallOk = createEntity(humanResult = "NG", overallResult = "OK")
        assertEquals("NG", roiNgOverallOk.humanResult)
        assertEquals("OK", roiNgOverallOk.overallResult)
    }
}
