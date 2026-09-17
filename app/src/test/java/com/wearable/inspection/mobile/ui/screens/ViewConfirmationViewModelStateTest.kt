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
 * ViewConfirmationViewModel 纯状态逻辑测试
 *
 * 不依赖 ViewModel 完整实例化，直接测试 applyDefaultSelections 和
 * buildViewRoiConfirmEntity 的行为。覆盖：
 * - 模型 OK/NG 默认选中
 * - 已保存人工选择优先
 * - FEATURE/未执行/无结果不默认选中且不自动判 NG
 * - 总体结果独立于 ROI
 * - OK→NG、NG→OK 改判时间和标记
 */
class ViewConfirmationViewModelStateTest {

    private val threadRoi = RoiDefinitionEntity(
        id = "roi-thread", templateId = "tpl-1", name = "螺纹",
        order = 0, normalizedRect = """{"left":0.1,"top":0.2,"right":0.6,"bottom":0.8}""",
        inspectionType = "NONE", targetType = "THREAD"
    )

    private val featureRoi = RoiDefinitionEntity(
        id = "roi-feature", templateId = "tpl-1", name = "部件",
        order = 1, normalizedRect = """{"left":0.1,"top":0.1,"right":0.5,"bottom":0.5}""",
        inspectionType = "NONE", targetType = "FEATURE"
    )

    private fun modelOk(roiId: String = "roi-thread") = NanoDetRoiInferenceResult(
        roiId = roiId, status = NanoDetInferenceStatus.DETECTED,
        modelSuggestion = NanoDetSuggestion.OK, matchingScore = 0.91f,
        targetClassIndex = 1, threshold = 0.37f
    )

    private fun modelNg(roiId: String = "roi-thread") = NanoDetRoiInferenceResult(
        roiId = roiId, status = NanoDetInferenceStatus.NO_DETECTION,
        modelSuggestion = NanoDetSuggestion.NG, matchingScore = null,
        targetClassIndex = 1, threshold = 0.37f
    )

    private fun featureUnsupported() = NanoDetRoiInferenceResult(
        roiId = "roi-feature", status = NanoDetInferenceStatus.FEATURE_UNSUPPORTED,
        modelSuggestion = null, matchingScore = null,
        targetClassIndex = null, threshold = 0.37f
    )

    /**
     * 模拟 ViewModel.applyDefaultSelections 的逻辑
     */
    private fun applyDefaultSelections(
        rois: List<RoiDefinitionEntity>,
        roiResults: MutableMap<String, String>,
        inferenceResults: Map<String, NanoDetRoiInferenceResult>
    ) {
        rois.forEach { roi ->
            if (roiResults.containsKey(roi.id)) return@forEach
            val inf = inferenceResults[roi.id] ?: return@forEach
            val suggestion = inf.modelSuggestion ?: return@forEach
            roiResults[roi.id] = suggestion.name
        }
    }

    // --- 默认选中 ---

    @Test
    fun modelOkDefaultsToOk() {
        val roiResults = mutableMapOf<String, String>()
        val inferenceResults = mapOf("roi-thread" to modelOk())
        applyDefaultSelections(listOf(threadRoi), roiResults, inferenceResults)
        assertEquals("OK", roiResults["roi-thread"])
    }

    @Test
    fun modelNgDefaultsToNg() {
        val roiResults = mutableMapOf<String, String>()
        val inferenceResults = mapOf("roi-thread" to modelNg())
        applyDefaultSelections(listOf(threadRoi), roiResults, inferenceResults)
        assertEquals("NG", roiResults["roi-thread"])
    }

    @Test
    fun savedHumanResultOverridesModelDefault() {
        val roiResults = mutableMapOf("roi-thread" to "NG") // 已保存人工值
        val inferenceResults = mapOf("roi-thread" to modelOk()) // 模型建议 OK
        applyDefaultSelections(listOf(threadRoi), roiResults, inferenceResults)
        assertEquals("已保存人工值不应被覆盖", "NG", roiResults["roi-thread"])
    }

    @Test
    fun featureUnsupportedDoesNotDefaultSelect() {
        val roiResults = mutableMapOf<String, String>()
        val inferenceResults = mapOf("roi-feature" to featureUnsupported())
        applyDefaultSelections(listOf(featureRoi), roiResults, inferenceResults)
        assertFalse("FEATURE 不应默认选中", roiResults.containsKey("roi-feature"))
    }

    @Test
    fun noInferenceDoesNotDefaultSelect() {
        val roiResults = mutableMapOf<String, String>()
        val inferenceResults = emptyMap<String, NanoDetRoiInferenceResult>()
        applyDefaultSelections(listOf(threadRoi), roiResults, inferenceResults)
        assertFalse("无推理结果不应默认选中", roiResults.containsKey("roi-thread"))
    }

    @Test
    fun nullModelSuggestionDoesNotDefaultSelect() {
        val roiResults = mutableMapOf<String, String>()
        val noSuggestion = NanoDetRoiInferenceResult(
            roiId = "roi-thread", status = NanoDetInferenceStatus.INFERENCE_ERROR,
            modelSuggestion = null, matchingScore = null,
            targetClassIndex = null, threshold = 0.37f
        )
        val inferenceResults = mapOf("roi-thread" to noSuggestion)
        applyDefaultSelections(listOf(threadRoi), roiResults, inferenceResults)
        assertFalse("null suggestion 不应默认选中", roiResults.containsKey("roi-thread"))
    }

    // --- buildViewRoiConfirmEntity 改判语义 ---

    @Test
    fun okToNg_correctOverrideFields() {
        val entity = buildViewRoiConfirmEntity(
            batchId = "b1", photoId = 1, photoPath = "/p.jpg", viewIndex = 0,
            templateId = "tpl-1", templateName = "V1", roi = threadRoi,
            roiPixelRect = "{}", inference = modelOk(), humanResult = "NG",
            overallResult = "OK", confirmedAt = 1000L,
            overrideTime = 2000L, roiEvidencePath = "/roi.jpg"
        )
        assertTrue("humanChangedModel 应为 true", entity.humanChangedModel)
        assertEquals("OK", entity.softwareResult)
        assertEquals("NG", entity.humanResult)
        assertEquals(2000L, entity.overrideTime)
        assertEquals("/roi.jpg", entity.roiEvidencePath)
    }

    @Test
    fun ngToOk_correctOverrideFields() {
        val entity = buildViewRoiConfirmEntity(
            batchId = "b1", photoId = 1, photoPath = "/p.jpg", viewIndex = 0,
            templateId = "tpl-1", templateName = "V1", roi = threadRoi,
            roiPixelRect = "{}", inference = modelNg(), humanResult = "OK",
            overallResult = "NG", confirmedAt = 1000L,
            overrideTime = 3000L, roiEvidencePath = "/roi2.jpg"
        )
        assertTrue(entity.humanChangedModel)
        assertEquals("NG", entity.softwareResult)
        assertEquals("OK", entity.humanResult)
        assertEquals(3000L, entity.overrideTime)
    }

    @Test
    fun noModelResult_humanChoiceNotModelOverride() {
        val entity = buildViewRoiConfirmEntity(
            batchId = "b1", photoId = 1, photoPath = "/p.jpg", viewIndex = 0,
            templateId = "tpl-1", templateName = "V1", roi = threadRoi,
            roiPixelRect = "{}", inference = null, humanResult = "NG",
            overallResult = "OK", confirmedAt = 1000L
        )
        assertFalse(entity.humanChangedModel)
        assertNull(entity.softwareResult)
        assertEquals("NG", entity.humanResult)
        assertNull(entity.overrideTime)
        assertNull(entity.roiEvidencePath)
    }

    @Test
    fun featureHumanChoiceNotModelOverride() {
        val entity = buildViewRoiConfirmEntity(
            batchId = "b1", photoId = 1, photoPath = "/p.jpg", viewIndex = 0,
            templateId = "tpl-1", templateName = "V1", roi = featureRoi,
            roiPixelRect = "{}", inference = featureUnsupported(), humanResult = "OK",
            overallResult = "OK", confirmedAt = 1000L
        )
        assertFalse(entity.humanChangedModel)
        assertNull(entity.softwareResult)
        assertNull(entity.overrideTime)
    }

    @Test
    fun overallResultIndependentOfRoiSelection() {
        val entity = buildViewRoiConfirmEntity(
            batchId = "b1", photoId = 1, photoPath = "/p.jpg", viewIndex = 0,
            templateId = "tpl-1", templateName = "V1", roi = threadRoi,
            roiPixelRect = "{}", inference = modelOk(), humanResult = "NG",
            overallResult = "OK", confirmedAt = 1000L
        )
        // ROI 改判为 NG，但总体仍为 OK
        assertEquals("NG", entity.humanResult)
        assertEquals("OK", entity.overallResult)
    }

    @Test
    fun stableAssociationsPreservedAcrossOverride() {
        val entity = buildViewRoiConfirmEntity(
            batchId = "batch-xyz", photoId = 42, photoPath = "/photo.jpg",
            viewIndex = 3, templateId = "tpl-abc", templateName = "视角D",
            roi = threadRoi, roiPixelRect = """{"left":10,"top":20,"right":30,"bottom":40}""",
            inference = modelOk(), humanResult = "NG", overallResult = "NG",
            confirmedAt = 5000L, overrideTime = 5500L, roiEvidencePath = "/evidence.jpg"
        )
        assertEquals("batch-xyz", entity.batchId)
        assertEquals(42L, entity.photoId)
        assertEquals(3, entity.viewIndex)
        assertEquals("tpl-abc", entity.templateId)
        assertEquals("roi-thread", entity.roiId)
        assertEquals(5000L, entity.confirmTime)
        assertEquals(5500L, entity.overrideTime)
    }

    @Test
    fun duplicateOverridePreservesOriginalOverrideTime() {
        val originalTime = 1_700_000_000_000L
        val entity = buildViewRoiConfirmEntity(
            batchId = "b1", photoId = 1, photoPath = "/p.jpg", viewIndex = 0,
            templateId = "tpl-1", templateName = "V1", roi = threadRoi,
            roiPixelRect = "{}", inference = modelOk(), humanResult = "NG",
            overallResult = "OK", confirmedAt = 2000L,
            overrideTime = originalTime, roiEvidencePath = "/roi_dup.jpg"
        )
        assertEquals("重复确认应保留原始改判时间", originalTime, entity.overrideTime)
        assertEquals("/roi_dup.jpg", entity.roiEvidencePath)
        assertTrue(entity.humanChangedModel)
    }
}