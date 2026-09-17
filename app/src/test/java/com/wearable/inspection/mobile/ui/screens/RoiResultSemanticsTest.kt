package com.wearable.inspection.mobile.ui.screens

import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.detection.NanoDetInferenceStatus
import com.wearable.inspection.mobile.detection.NanoDetRoiInferenceResult
import com.wearable.inspection.mobile.detection.NanoDetSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ROI 最终结果语义测试
 *
 * 覆盖：
 * - overrideTime / roiEvidencePath 默认值
 * - 有模型结果时默认选中语义
 * - humanChangedModel 正确计算
 * - FEATURE/未执行不伪装改判
 * - 总体结果独立于 ROI
 */
class RoiResultSemanticsTest {

    private val threadRoi = RoiDefinitionEntity(
        id = "roi-t1", templateId = "tpl-1", name = "螺纹区域",
        order = 0, normalizedRect = """{"left":0.1,"top":0.2,"right":0.6,"bottom":0.8}""",
        inspectionType = "NONE", targetType = "THREAD"
    )

    private val featureRoi = RoiDefinitionEntity(
        id = "roi-f1", templateId = "tpl-1", name = "部件区域",
        order = 1, normalizedRect = """{"left":0.1,"top":0.1,"right":0.5,"bottom":0.5}""",
        inspectionType = "NONE", targetType = "FEATURE"
    )

    private fun modelOk() = NanoDetRoiInferenceResult(
        roiId = "roi-t1", status = NanoDetInferenceStatus.DETECTED,
        modelSuggestion = NanoDetSuggestion.OK, matchingScore = 0.91f,
        targetClassIndex = 1, threshold = 0.37f
    )

    private fun modelNg() = NanoDetRoiInferenceResult(
        roiId = "roi-t1", status = NanoDetInferenceStatus.NO_DETECTION,
        modelSuggestion = NanoDetSuggestion.NG, matchingScore = null,
        targetClassIndex = 1, threshold = 0.37f
    )

    private fun featureUnsupported() = NanoDetRoiInferenceResult(
        roiId = "roi-f1", status = NanoDetInferenceStatus.FEATURE_UNSUPPORTED,
        modelSuggestion = null, matchingScore = null,
        targetClassIndex = null, threshold = 0.37f
    )

    // --- Entity 新字段默认值 ---

    @Test
    fun `entity overrideTime defaults to null`() {
        val entity = buildViewRoiConfirmEntity(
            batchId = "b1", photoId = 1, photoPath = "/p.jpg", viewIndex = 0,
            templateId = "tpl-1", templateName = "V1", roi = threadRoi,
            roiPixelRect = "{}", inference = modelOk(), humanResult = "OK",
            overallResult = "OK", confirmedAt = 1000L
        )
        assertNull(entity.overrideTime)
        assertNull(entity.roiEvidencePath)
    }

    @Test
    fun `entity stores overrideTime and roiEvidencePath when provided`() {
        val entity = buildViewRoiConfirmEntity(
            batchId = "b1", photoId = 1, photoPath = "/p.jpg", viewIndex = 0,
            templateId = "tpl-1", templateName = "V1", roi = threadRoi,
            roiPixelRect = "{}", inference = modelOk(), humanResult = "NG",
            overallResult = "OK", confirmedAt = 1000L,
            overrideTime = 2000L, roiEvidencePath = "/roi_evidence/roi.jpg"
        )
        assertEquals(2000L, entity.overrideTime!!)
        assertEquals("/roi_evidence/roi.jpg", entity.roiEvidencePath)
    }

    // --- humanChangedModel 计算 ---

    @Test
    fun `no change when human matches model OK`() {
        val entity = buildViewRoiConfirmEntity(
            batchId = "b1", photoId = 1, photoPath = "/p.jpg", viewIndex = 0,
            templateId = "tpl-1", templateName = "V1", roi = threadRoi,
            roiPixelRect = "{}", inference = modelOk(), humanResult = "OK",
            overallResult = "OK", confirmedAt = 1000L
        )
        assertFalse(entity.humanChangedModel)
        assertEquals("OK", entity.softwareResult)
        assertEquals("OK", entity.humanResult)
    }

    @Test
    fun `change detected when human overrides model OK to NG`() {
        val entity = buildViewRoiConfirmEntity(
            batchId = "b1", photoId = 1, photoPath = "/p.jpg", viewIndex = 0,
            templateId = "tpl-1", templateName = "V1", roi = threadRoi,
            roiPixelRect = "{}", inference = modelOk(), humanResult = "NG",
            overallResult = "OK", confirmedAt = 1000L
        )
        assertTrue(entity.humanChangedModel)
        assertEquals("OK", entity.softwareResult)
        assertEquals("NG", entity.humanResult)
    }

    @Test
    fun `change detected when human overrides model NG to OK`() {
        val entity = buildViewRoiConfirmEntity(
            batchId = "b1", photoId = 1, photoPath = "/p.jpg", viewIndex = 0,
            templateId = "tpl-1", templateName = "V1", roi = threadRoi,
            roiPixelRect = "{}", inference = modelNg(), humanResult = "OK",
            overallResult = "NG", confirmedAt = 1000L
        )
        assertTrue(entity.humanChangedModel)
        assertEquals("NG", entity.softwareResult)
        assertEquals("OK", entity.humanResult)
    }

    @Test
    fun `FEATURE unsupported human choice is not model override`() {
        val entity = buildViewRoiConfirmEntity(
            batchId = "b1", photoId = 1, photoPath = "/p.jpg", viewIndex = 0,
            templateId = "tpl-1", templateName = "V1", roi = featureRoi,
            roiPixelRect = "{}", inference = featureUnsupported(), humanResult = "OK",
            overallResult = "OK", confirmedAt = 1000L
        )
        assertFalse(entity.humanChangedModel)
        assertNull(entity.softwareResult)
        assertEquals("OK", entity.humanResult)
    }

    @Test
    fun `not executed human choice is not model override`() {
        val entity = buildViewRoiConfirmEntity(
            batchId = "b1", photoId = 1, photoPath = "/p.jpg", viewIndex = 0,
            templateId = "tpl-1", templateName = "V1", roi = threadRoi,
            roiPixelRect = "{}", inference = null, humanResult = "NG",
            overallResult = "OK", confirmedAt = 1000L
        )
        assertFalse(entity.humanChangedModel)
        assertNull(entity.softwareResult)
        assertNull(entity.overrideTime)
        assertNull(entity.roiEvidencePath)
    }

    // --- 总体结果独立 ---

    @Test
    fun `overall result independent of ROI selection`() {
        val entity = buildViewRoiConfirmEntity(
            batchId = "b1", photoId = 1, photoPath = "/p.jpg", viewIndex = 0,
            templateId = "tpl-1", templateName = "V1", roi = threadRoi,
            roiPixelRect = "{}", inference = modelOk(), humanResult = "NG",
            overallResult = "NG", confirmedAt = 1000L
        )
        assertEquals("NG", entity.humanResult)
        assertEquals("NG", entity.overallResult)
        assertTrue(entity.humanChangedModel)
    }

    @Test
    fun `ROI NG does not override overall OK`() {
        val entity = buildViewRoiConfirmEntity(
            batchId = "b1", photoId = 1, photoPath = "/p.jpg", viewIndex = 0,
            templateId = "tpl-1", templateName = "V1", roi = threadRoi,
            roiPixelRect = "{}", inference = modelNg(), humanResult = "NG",
            overallResult = "OK", confirmedAt = 1000L
        )
        assertEquals("NG", entity.humanResult)
        assertEquals("OK", entity.overallResult)
        assertFalse(entity.humanChangedModel)
    }

    // --- Override 参数传递 ---

    @Test
    fun `override parameters are passed through to entity`() {
        val entity = buildViewRoiConfirmEntity(
            batchId = "b1", photoId = 1, photoPath = "/p.jpg", viewIndex = 0,
            templateId = "tpl-1", templateName = "V1", roi = threadRoi,
            roiPixelRect = "{}", inference = modelOk(), humanResult = "NG",
            overallResult = "OK", confirmedAt = 1000L,
            overrideTime = 5000L, roiEvidencePath = "/managed/roi.jpg"
        )
        assertTrue(entity.humanChangedModel)
        assertEquals(5000L, entity.overrideTime)
        assertEquals("/managed/roi.jpg", entity.roiEvidencePath)
        assertEquals(1000L, entity.confirmTime)
    }

    @Test
    fun `no override when result unchanged preserves null override fields`() {
        val entity = buildViewRoiConfirmEntity(
            batchId = "b1", photoId = 1, photoPath = "/p.jpg", viewIndex = 0,
            templateId = "tpl-1", templateName = "V1", roi = threadRoi,
            roiPixelRect = "{}", inference = modelOk(), humanResult = "OK",
            overallResult = "OK", confirmedAt = 1000L,
            overrideTime = null, roiEvidencePath = null
        )
        assertFalse(entity.humanChangedModel)
        assertNull(entity.overrideTime)
        assertNull(entity.roiEvidencePath)
    }

    // --- 稳定关联不受影响 ---

    @Test
    fun `stable associations preserved with override fields`() {
        val entity = buildViewRoiConfirmEntity(
            batchId = "batch-stable", photoId = 42, photoPath = "/path/photo.jpg",
            viewIndex = 3, templateId = "tpl-xyz", templateName = "视角4",
            roi = threadRoi, roiPixelRect = """{"left":10,"top":20,"right":30,"bottom":40}""",
            inference = modelOk(), humanResult = "NG", overallResult = "OK",
            confirmedAt = 9999L, overrideTime = 8888L, roiEvidencePath = "/roi.jpg"
        )
        assertEquals("batch-stable", entity.batchId)
        assertEquals(42L, entity.photoId)
        assertEquals("/path/photo.jpg", entity.photoPath)
        assertEquals(3, entity.viewIndex)
        assertEquals("tpl-xyz", entity.templateId)
        assertEquals("roi-t1", entity.roiId)
        assertEquals(9999L, entity.confirmTime)
        assertEquals(8888L, entity.overrideTime)
    }
}
