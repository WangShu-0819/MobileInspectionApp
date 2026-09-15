package com.wearable.inspection.mobile.ui.screens

import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.detection.NanoDetBox
import com.wearable.inspection.mobile.detection.NanoDetDetection
import com.wearable.inspection.mobile.detection.NanoDetInferenceStatus
import com.wearable.inspection.mobile.detection.NanoDetRoiInferenceResult
import com.wearable.inspection.mobile.detection.NanoDetSuggestion
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewConfirmationModelResultTest {
    private val roi = RoiDefinitionEntity(
        id = "roi-thread-17",
        templateId = "template-3",
        name = "螺纹区域",
        order = 0,
        normalizedRect = """{"left":0.1,"top":0.2,"right":0.6,"bottom":0.8}""",
        inspectionType = "NUT",
        targetType = "THREAD"
    )

    private fun result(
        status: NanoDetInferenceStatus = NanoDetInferenceStatus.DETECTED,
        suggestion: NanoDetSuggestion? = NanoDetSuggestion.OK,
        score: Float? = 0.91f,
        classIndex: Int? = 1,
        detections: List<NanoDetDetection> = listOf(
            NanoDetDetection(
                classIndex = 1,
                className = "thread",
                score = 0.91f,
                point = 42,
                roiBox = NanoDetBox(2.0, 3.0, 40.0, 50.0),
                imageBox = NanoDetBox(102.0, 203.0, 140.0, 250.0)
            ),
            NanoDetDetection(
                classIndex = 0,
                className = "nut",
                score = 0.20f,
                point = 43,
                roiBox = NanoDetBox(10.0, 15.0, 20.0, 28.0),
                imageBox = NanoDetBox(110.0, 215.0, 120.0, 228.0)
            )
        )
    ) = NanoDetRoiInferenceResult(
        roiId = roi.id,
        status = status,
        modelSuggestion = suggestion,
        matchingScore = score,
        targetClassIndex = classIndex,
        threshold = 0.37f,
        candidateThreshold = 0.05f,
        modelVersion = "nanodet-ncnn-20260526-opt2",
        modelParamSha256 = "param-sha",
        modelSha256 = "model-sha",
        elapsedMs = 33,
        inputShape = "[1,3,416,416]",
        outputBlob = "out0",
        outputShape = "[3598,34]",
        imageWidth = 1920,
        imageHeight = 1080,
        exifOrientation = 6,
        roiBounds = listOf(100, 200, 200, 300),
        detections = detections
    )

    private fun saveRow(
        inference: NanoDetRoiInferenceResult?,
        human: String,
        overall: String
    ) = buildViewRoiConfirmEntity(
        batchId = "batch-stable-9",
        photoId = 503L,
        photoPath = "D:/captures/photo-503.jpg",
        viewIndex = 2,
        templateId = "template-3",
        templateName = "侧面",
        roi = roi,
        roiPixelRect = """{"left":100,"top":200,"right":200,"bottom":300}""",
        inference = inference,
        humanResult = human,
        overallResult = overall,
        confirmedAt = 1_800_000_123L
    )

    @Test
    fun `model OK remains separate when human changes it to NG`() {
        val row = saveRow(result(), human = "NG", overall = "OK")

        assertEquals("OK", row.softwareResult)
        assertEquals("NG", row.humanResult)
        assertTrue(row.humanChangedModel)
        assertEquals("OK", row.overallResult)
    }

    @Test
    fun `model NG remains separate when human changes it to OK`() {
        val row = saveRow(
            result(status = NanoDetInferenceStatus.DETECTED_BELOW_THRESHOLD, suggestion = NanoDetSuggestion.NG, score = 0.21f),
            human = "OK",
            overall = "NG"
        )

        assertEquals("NG", row.softwareResult)
        assertEquals("OK", row.humanResult)
        assertTrue(row.humanChangedModel)
        assertEquals("NG", row.overallResult)
    }

    @Test
    fun `overall manual result is independent of model and ROI final results`() {
        val row = saveRow(result(suggestion = NanoDetSuggestion.NG, score = null), human = "OK", overall = "OK")

        assertEquals("NG", row.softwareResult)
        assertEquals("OK", row.humanResult)
        assertEquals("OK", row.overallResult)
        assertTrue(row.humanChangedModel)
    }

    @Test
    fun `no detection stores NG suggestion with null score and empty boxes`() {
        val row = saveRow(
            result(
                status = NanoDetInferenceStatus.NO_DETECTION,
                suggestion = NanoDetSuggestion.NG,
                score = null,
                detections = emptyList()
            ),
            human = "NG",
            overall = "OK"
        )

        assertEquals("NO_DETECTION", row.softwareStatus)
        assertEquals("NG", row.softwareResult)
        assertNull(row.softwareScore)
        assertEquals(0, JSONArray(row.softwareDetectionsJson).length())
        assertFalse(row.humanChangedModel)
    }

    @Test
    fun `error statuses never produce a model decision`() {
        listOf(
            NanoDetInferenceStatus.MODEL_UNAVAILABLE,
            NanoDetInferenceStatus.FEATURE_UNSUPPORTED,
            NanoDetInferenceStatus.ROI_NOT_CONFIGURED,
            NanoDetInferenceStatus.PHOTO_ASSOCIATION_ERROR
        ).forEach { status ->
            val row = saveRow(
                result(status = status, suggestion = null, score = null, classIndex = null, detections = emptyList()),
                human = "NG",
                overall = "NG"
            )
            assertEquals(status.name, row.softwareStatus)
            assertNull(row.softwareResult)
            assertNull(row.softwareScore)
            assertNull(row.softwareThreshold)
            assertEquals("NG", row.humanResult)
            assertFalse(row.humanChangedModel)
        }
    }

    @Test
    fun `not executed leaves all model fields null`() {
        val row = saveRow(inference = null, human = "OK", overall = "NG")

        assertNull(row.softwareResult)
        assertNull(row.softwareTargetClass)
        assertNull(row.softwareScore)
        assertNull(row.softwareThreshold)
        assertNull(row.softwareDetectionsJson)
        assertNull(row.softwareStatus)
        assertNull(row.softwareModelVersion)
        assertNull(row.softwareModelSummary)
        assertNull(row.softwareElapsedMs)
        assertFalse(row.humanChangedModel)
        assertEquals("OK", row.humanResult)
        assertEquals("NG", row.overallResult)
    }

    @Test
    fun `snapshot preserves stable associations model metadata and every box`() {
        val row = saveRow(result(), human = "OK", overall = "NG")

        assertEquals("batch-stable-9", row.batchId)
        assertEquals(503L, row.photoId)
        assertEquals("D:/captures/photo-503.jpg", row.photoPath)
        assertEquals(2, row.viewIndex)
        assertEquals("template-3", row.templateId)
        assertEquals("roi-thread-17", row.roiId)
        assertEquals(1_800_000_123L, row.confirmTime)
        assertEquals(1_800_000_123L, row.overallConfirmTime)
        assertEquals("THREAD", row.softwareTargetClass)
        assertEquals(0.91f, row.softwareScore!!, 0.0001f)
        assertEquals(0.37f, row.softwareThreshold!!, 0.0001f)
        assertEquals("nanodet-ncnn-20260526-opt2", row.softwareModelVersion)
        assertEquals(33L, row.softwareElapsedMs)
        val detections = JSONArray(row.softwareDetectionsJson)
        assertEquals(2, detections.length())
        assertEquals("thread", detections.getJSONObject(0).getString("className"))
        assertEquals(102.0, detections.getJSONObject(0).getJSONObject("imageBox").getDouble("left"), 0.001)
        val summary = JSONObject(row.softwareModelSummary)
        assertEquals("param-sha", summary.getString("modelParamSha256"))
        assertEquals("model-sha", summary.getString("modelSha256"))
        assertEquals("out0", summary.getString("outputBlob"))
        assertEquals("[3598,34]", summary.getString("outputShape"))
    }

    @Test
    fun `every inference status has a distinct visible label`() {
        assertEquals("未检出", inferenceStatusLabel(NanoDetInferenceStatus.NO_DETECTION))
        assertEquals("ROI 属性未配置", inferenceStatusLabel(NanoDetInferenceStatus.ROI_NOT_CONFIGURED))
        assertEquals("部件类别暂不支持", inferenceStatusLabel(NanoDetInferenceStatus.FEATURE_UNSUPPORTED))
        assertEquals("照片不可读取", inferenceStatusLabel(NanoDetInferenceStatus.IMAGE_UNREADABLE))
        assertEquals("模型不可用", inferenceStatusLabel(NanoDetInferenceStatus.MODEL_UNAVAILABLE))
        assertEquals("推理错误", inferenceStatusLabel(NanoDetInferenceStatus.INFERENCE_ERROR))
        assertEquals("已检出，达到模型阈值", inferenceStatusLabel(NanoDetInferenceStatus.DETECTED))
    }
}
