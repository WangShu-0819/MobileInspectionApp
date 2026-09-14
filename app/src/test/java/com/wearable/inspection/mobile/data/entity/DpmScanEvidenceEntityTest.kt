package com.wearable.inspection.mobile.data.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DpmScanEvidenceEntity 单元测试
 *
 * 覆盖：
 * - SUCCESS 实体字段正确性
 * - NO_READ 实体字段正确性
 * - decodeSource 可空（NO_READ 时为 null）
 * - roiImagePath 可空（无 ROI 时为 null）
 * - 自动 createdAt 正性
 */
class DpmScanEvidenceEntityTest {

    private fun createSuccessEntity() = DpmScanEvidenceEntity(
        id = 1,
        scanSessionId = "sess-abc-123",
        frameTimeMs = 1726000000000L,
        frameSource = "CAMERA",
        decodedContent = "L0549630AE092212080057",
        status = "SUCCESS",
        decodeSource = "ZXING",
        originalImagePath = "/data/files/dpm_evidence/dpm_abc-12_ok_1726000000_frame.jpg",
        roiImagePath = "/data/files/dpm_evidence/dpm_abc-12_ok_1726000000_roi.jpg",
    )

    private fun createNoReadEntity() = DpmScanEvidenceEntity(
        id = 2,
        scanSessionId = "sess-def-456",
        frameTimeMs = 1726000001000L,
        frameSource = "CAMERA",
        decodedContent = null,
        status = "NO_READ",
        decodeSource = null,
        originalImagePath = "/data/files/dpm_evidence/dpm_def-45_nr_1726000001_frame.jpg",
        roiImagePath = null,
    )

    @Test
    fun `SUCCESS entity has correct field values`() {
        val entity = createSuccessEntity()
        assertEquals("sess-abc-123", entity.scanSessionId)
        assertEquals(1726000000000L, entity.frameTimeMs)
        assertEquals("CAMERA", entity.frameSource)
        assertEquals("L0549630AE092212080057", entity.decodedContent)
        assertEquals("SUCCESS", entity.status)
        assertEquals("ZXING", entity.decodeSource)
        assertTrue(entity.originalImagePath.endsWith("_frame.jpg"))
        assertTrue(entity.roiImagePath!!.endsWith("_roi.jpg"))
    }

    @Test
    fun `NO_READ entity has null decodedContent and decodeSource`() {
        val entity = createNoReadEntity()
        assertNull(entity.decodedContent)
        assertNull(entity.decodeSource)
        assertEquals("NO_READ", entity.status)
    }

    @Test
    fun `roiImagePath can be null for no-ROI frames`() {
        val entity = createNoReadEntity()
        assertNull(entity.roiImagePath)
    }

    @Test
    fun `decodedContent can be null for NO_READ`() {
        val entity = createNoReadEntity()
        assertNull(entity.decodedContent)
    }

    @Test
    fun `decodeSource ZXING is preserved`() {
        val entity = createSuccessEntity().copy(decodeSource = "ZXING")
        assertEquals("ZXING", entity.decodeSource)
    }

    @Test
    fun `decodeSource ML_KIT is preserved`() {
        val entity = createSuccessEntity().copy(decodeSource = "ML_KIT")
        assertEquals("ML_KIT", entity.decodeSource)
    }

    @Test
    fun `decodeSource GRID is preserved`() {
        val entity = createSuccessEntity().copy(decodeSource = "GRID")
        assertEquals("GRID", entity.decodeSource)
    }

    @Test
    fun `createdAt defaults to positive timestamp`() {
        val entity = DpmScanEvidenceEntity(
            scanSessionId = "test",
            frameTimeMs = System.currentTimeMillis(),
            frameSource = "CAMERA",
            status = "NO_READ",
            originalImagePath = "/tmp/test.jpg",
        )
        assertTrue("createdAt should be positive", entity.createdAt > 0)
    }

    @Test
    fun `status SUCCESS does not prevent entity creation`() {
        val entity = createSuccessEntity()
        assertEquals("SUCCESS", entity.status)
        assertTrue(entity.id >= 0)
    }

    @Test
    fun `status NO_READ does not prevent entity creation`() {
        val entity = createNoReadEntity()
        assertEquals("NO_READ", entity.status)
        assertTrue(entity.id >= 0)
    }
}
