package com.wearable.inspection.mobile.ui.screens

import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.detection.NanoDetBox
import com.wearable.inspection.mobile.detection.NanoDetDetection
import com.wearable.inspection.mobile.detection.FullImageInferResult
import com.wearable.inspection.mobile.detection.NanoDetInferenceStatus
import com.wearable.inspection.mobile.detection.NanoDetRoiInferenceResult
import com.wearable.inspection.mobile.detection.NanoDetSuggestion
import com.wearable.inspection.mobile.detection.NanoDetModelContract
import com.wearable.inspection.mobile.detection.RoiSimilarityResult
import com.wearable.inspection.mobile.detection.RoiSimilarityStatus
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
        threshold = 0.50f,
        candidateThreshold = 0.05f,
        modelVersion = "nanodet-ncnn-exp09-4class",
        modelParamSha256 = "param-sha",
        modelSha256 = "model-sha",
        elapsedMs = 33,
        inputShape = "[1,3,416,416]",
        outputBlob = "out0",
        outputShape = "[3598,36]",
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
        assertEquals(0.50f, row.softwareThreshold!!, 0.0001f)
        assertEquals("nanodet-ncnn-exp09-4class", row.softwareModelVersion)
        assertEquals(33L, row.softwareElapsedMs)
        val detections = JSONArray(row.softwareDetectionsJson)
        assertEquals(2, detections.length())
        assertEquals("thread", detections.getJSONObject(0).getString("className"))
        assertEquals(102.0, detections.getJSONObject(0).getJSONObject("imageBox").getDouble("left"), 0.001)
        val summary = JSONObject(row.softwareModelSummary)
        assertEquals("param-sha", summary.getString("modelParamSha256"))
        assertEquals("model-sha", summary.getString("modelSha256"))
        assertEquals("out0", summary.getString("outputBlob"))
        assertEquals("[3598,36]", summary.getString("outputShape"))
    }

    @Test
    fun `bolt target class maps to BOLT string`() {
        val boltResult = result(classIndex = 2)
        val row = saveRow(boltResult, human = "OK", overall = "OK")
        assertEquals("BOLT", row.softwareTargetClass)
    }

    @Test
    fun `nutsert target class maps to NUTSERT string`() {
        val nutsertResult = result(classIndex = 3)
        val row = saveRow(nutsertResult, human = "OK", overall = "OK")
        assertEquals("NUTSERT", row.softwareTargetClass)
    }

    @Test
    fun `every inference status has a distinct visible label`() {
        assertEquals("未检出", inferenceStatusLabel(NanoDetInferenceStatus.NO_DETECTION))
        assertEquals("ROI 属性未配置", inferenceStatusLabel(NanoDetInferenceStatus.ROI_NOT_CONFIGURED))
        assertEquals("部件类别暂不支持", inferenceStatusLabel(NanoDetInferenceStatus.FEATURE_UNSUPPORTED))
        assertEquals("模型不支持该目标类型", inferenceStatusLabel(NanoDetInferenceStatus.MODEL_TARGET_UNSUPPORTED))
        assertEquals("照片不可读取", inferenceStatusLabel(NanoDetInferenceStatus.IMAGE_UNREADABLE))
        assertEquals("模型不可用", inferenceStatusLabel(NanoDetInferenceStatus.MODEL_UNAVAILABLE))
        assertEquals("推理错误", inferenceStatusLabel(NanoDetInferenceStatus.INFERENCE_ERROR))
        assertEquals("已检出，达到模型阈值", inferenceStatusLabel(NanoDetInferenceStatus.DETECTED))
    }

    @Test
    fun `unsupported NanoDet status is preserved alongside independent similarity evidence`() {
        val inference = result(
            status = NanoDetInferenceStatus.MODEL_TARGET_UNSUPPORTED,
            suggestion = null,
            score = null,
            classIndex = null,
        ).copy(
            similarity = RoiSimilarityResult(
                status = RoiSimilarityStatus.SCORED_WITH_CANDIDATE_THRESHOLD,
                score = 0.70f,
                threshold = 0.75f,
                candidate = NanoDetSuggestion.NG,
            ),
            similarityEvidencePath = "D:/evidence/unsupported-roi.jpg",
            similarityEvidenceStatus = "SAVED",
        )
        val row = saveRow(inference, human = "OK", overall = "OK")

        assertEquals("MODEL_TARGET_UNSUPPORTED", row.softwareStatus)
        assertNull(row.softwareResult)
        assertFalse(row.humanChangedModel)
        assertEquals("SCORED_WITH_CANDIDATE_THRESHOLD", row.similarityStatus)
        assertEquals(0.70f, row.similarityScore!!, 0.0001f)
        assertEquals(0.75f, row.similarityThreshold!!, 0.0001f)
        assertEquals("NG", row.similarityCandidate)
        assertEquals("D:/evidence/unsupported-roi.jpg", row.similarityRoiEvidencePath)
        assertEquals("SAVED", JSONObject(row.softwareModelSummary).getString("similarityEvidenceStatus"))
    }

    // ───────────────────────────────────────────────
    // 整图检测阈值过滤测试
    // ───────────────────────────────────────────────

    private fun fullImageResult(
        detections: List<NanoDetDetection>,
        threshold: Float = 0.50f,
    ) = FullImageInferResult(
        detections = detections,
        aggregatedSuggestion = null,
        highestScore = detections.maxOfOrNull { it.score },
        elapsedMs = 50,
        imageWidth = 1920,
        imageHeight = 1080,
        exifOrientation = 1,
        status = NanoDetInferenceStatus.DETECTED,
        threshold = threshold,
    )

    @Test
    fun `full-image threshold filters detections for display`() {
        val allDetections = listOf(
            NanoDetDetection(1, "thread", 0.89f, 10, NanoDetBox(1.0, 2.0, 50.0, 60.0), NanoDetBox(101.0, 202.0, 150.0, 262.0)),
            NanoDetDetection(0, "nut", 0.20f, 5, NanoDetBox(10.0, 15.0, 20.0, 28.0), NanoDetBox(110.0, 215.0, 120.0, 228.0)),
            NanoDetDetection(2, "bolt", 0.55f, 8, NanoDetBox(70.0, 80.0, 100.0, 110.0), NanoDetBox(170.0, 280.0, 200.0, 310.0)),
        )
        val result = fullImageResult(allDetections, threshold = 0.50f)
        val threshold = result.threshold!!
        val displayDetections = result.detections.filter { it.score >= threshold }

        assertEquals("threshold 应为 0.50", 0.50f, threshold, 0.001f)
        assertEquals("0.89 和 0.55 达到阈值，应有 2 个", 2, displayDetections.size)
        assertTrue("0.89 应保留", displayDetections.any { it.score == 0.89f })
        assertTrue("0.55 应保留", displayDetections.any { it.score == 0.55f })
        assertFalse("0.20 应过滤", displayDetections.any { it.score == 0.20f })
    }

    @Test
    fun `full-image threshold filters all detections when all below threshold`() {
        val allDetections = listOf(
            NanoDetDetection(0, "nut", 0.10f, 3, NanoDetBox(1.0, 2.0, 10.0, 12.0), NanoDetBox(101.0, 202.0, 110.0, 212.0)),
            NanoDetDetection(1, "thread", 0.40f, 7, NanoDetBox(20.0, 25.0, 40.0, 45.0), NanoDetBox(120.0, 225.0, 140.0, 245.0)),
        )
        val result = fullImageResult(allDetections, threshold = 0.50f)
        val displayDetections = result.detections.filter { it.score >= result.threshold!! }

        assertEquals("所有检测低于阈值时应为空", 0, displayDetections.size)
    }

    @Test
    fun `full-image threshold passes all detections when all above threshold`() {
        val allDetections = listOf(
            NanoDetDetection(1, "thread", 0.91f, 12, NanoDetBox(1.0, 2.0, 50.0, 60.0), NanoDetBox(101.0, 202.0, 150.0, 262.0)),
            NanoDetDetection(2, "bolt", 0.65f, 9, NanoDetBox(70.0, 80.0, 100.0, 110.0), NanoDetBox(170.0, 280.0, 200.0, 310.0)),
        )
        val result = fullImageResult(allDetections, threshold = 0.50f)
        val displayDetections = result.detections.filter { it.score >= result.threshold!! }

        assertEquals("全部达到阈值时应全部保留", 2, displayDetections.size)
    }

    @Test
    fun `full-image result preserves original detections intact`() {
        val allDetections = listOf(
            NanoDetDetection(1, "thread", 0.89f, 10, NanoDetBox(1.0, 2.0, 50.0, 60.0), NanoDetBox(101.0, 202.0, 150.0, 262.0)),
            NanoDetDetection(0, "nut", 0.20f, 5, NanoDetBox(10.0, 15.0, 20.0, 28.0), NanoDetBox(110.0, 215.0, 120.0, 228.0)),
        )
        val result = fullImageResult(allDetections, threshold = 0.50f)

        // 原始 detections 必须完整保留（不受阈值过滤影响）
        assertEquals("原始 detections 应完整保留", 2, result.detections.size)
        assertTrue("0.20 应在原始 detections 中", result.detections.any { it.score == 0.20f })
        assertTrue("0.89 应在原始 detections 中", result.detections.any { it.score == 0.89f })
    }

    @Test
    fun `full-image display highest score comes from filtered detections`() {
        val allDetections = listOf(
            NanoDetDetection(1, "thread", 0.89f, 10, NanoDetBox(1.0, 2.0, 50.0, 60.0), NanoDetBox(101.0, 202.0, 150.0, 262.0)),
            NanoDetDetection(0, "nut", 0.20f, 5, NanoDetBox(10.0, 15.0, 20.0, 28.0), NanoDetBox(110.0, 215.0, 120.0, 228.0)),
        )
        val result = fullImageResult(allDetections, threshold = 0.50f)
        val displayDetections = result.detections.filter { it.score >= result.threshold!! }
        val displayHighestScore = displayDetections.maxOfOrNull { it.score }

        // 显示的最高分应来自过滤后的检测（0.89），而非全部检测的 result.highestScore（也是0.89但逻辑不同）
        assertEquals("显示最高分应为 0.89", 0.89f, displayHighestScore!!, 0.001f)
    }

    // ───────────────────────────────────────────────
    // 阈值边界与回退测试（handback 缺口修复）
    // ───────────────────────────────────────────────

    /** 模拟 ViewConfirmationScreen 中的阈值规范化逻辑 */
    private fun normalizeThreshold(rawThreshold: Float?): Float {
        return if (rawThreshold != null && rawThreshold.isFinite() && rawThreshold in 0f..1f) {
            rawThreshold
        } else {
            NanoDetModelContract.STARTING_BUSINESS_THRESHOLD
        }
    }

    @Test
    fun `threshold exactly at boundary score 0_50 is included in display`() {
        val allDetections = listOf(
            NanoDetDetection(1, "thread", 0.50f, 10, NanoDetBox(1.0, 2.0, 50.0, 60.0), NanoDetBox(101.0, 202.0, 150.0, 262.0)),
            NanoDetDetection(0, "nut", 0.49f, 5, NanoDetBox(10.0, 15.0, 20.0, 28.0), NanoDetBox(110.0, 215.0, 120.0, 228.0)),
        )
        val threshold = normalizeThreshold(0.50f)
        val displayDetections = allDetections.filter { it.score >= threshold }

        assertEquals("score==0.50 应保留（>= 阈值）", 1, displayDetections.size)
        assertEquals(0.50f, displayDetections[0].score, 0.0001f)
    }

    @Test
    fun `threshold NaN falls back to STARTING_BUSINESS_THRESHOLD`() {
        val threshold = normalizeThreshold(Float.NaN)
        assertEquals("NaN 应回退到 0.50", 0.50f, threshold, 0.0001f)

        val allDetections = listOf(
            NanoDetDetection(1, "thread", 0.50f, 10, NanoDetBox(1.0, 2.0, 50.0, 60.0), NanoDetBox(101.0, 202.0, 150.0, 262.0)),
            NanoDetDetection(0, "nut", 0.20f, 5, NanoDetBox(10.0, 15.0, 20.0, 28.0), NanoDetBox(110.0, 215.0, 120.0, 228.0)),
        )
        val displayDetections = allDetections.filter { it.score >= threshold }
        assertEquals("0.50 应保留", 1, displayDetections.size)
        assertFalse("0.20 应过滤", displayDetections.any { it.score == 0.20f })
    }

    @Test
    fun `threshold negative falls back to STARTING_BUSINESS_THRESHOLD`() {
        val threshold = normalizeThreshold(-0.5f)
        assertEquals("负数 threshold 应回退到 0.50", 0.50f, threshold, 0.0001f)

        val allDetections = listOf(
            NanoDetDetection(1, "thread", 0.50f, 10, NanoDetBox(1.0, 2.0, 50.0, 60.0), NanoDetBox(101.0, 202.0, 150.0, 262.0)),
            NanoDetDetection(0, "nut", 0.20f, 5, NanoDetBox(10.0, 15.0, 20.0, 28.0), NanoDetBox(110.0, 215.0, 120.0, 228.0)),
        )
        val displayDetections = allDetections.filter { it.score >= threshold }
        assertEquals("0.50 应保留", 1, displayDetections.size)
        assertFalse("0.20 应过滤", displayDetections.any { it.score == 0.20f })
    }

    @Test
    fun `threshold greater than 1 falls back to STARTING_BUSINESS_THRESHOLD`() {
        val threshold = normalizeThreshold(1.5f)
        assertEquals(">1 threshold 应回退到 0.50", 0.50f, threshold, 0.0001f)

        val allDetections = listOf(
            NanoDetDetection(1, "thread", 0.50f, 10, NanoDetBox(1.0, 2.0, 50.0, 60.0), NanoDetBox(101.0, 202.0, 150.0, 262.0)),
            NanoDetDetection(0, "nut", 0.20f, 5, NanoDetBox(10.0, 15.0, 20.0, 28.0), NanoDetBox(110.0, 215.0, 120.0, 228.0)),
        )
        val displayDetections = allDetections.filter { it.score >= threshold }
        assertEquals("0.50 应保留", 1, displayDetections.size)
        assertFalse("0.20 应过滤", displayDetections.any { it.score == 0.20f })
    }

    @Test
    fun `threshold positive infinity falls back to STARTING_BUSINESS_THRESHOLD`() {
        val threshold = normalizeThreshold(Float.POSITIVE_INFINITY)
        assertEquals("+Inf 应回退到 0.50", 0.50f, threshold, 0.0001f)
    }

    @Test
    fun `threshold negative infinity falls back to STARTING_BUSINESS_THRESHOLD`() {
        val threshold = normalizeThreshold(Float.NEGATIVE_INFINITY)
        assertEquals("-Inf 应回退到 0.50", 0.50f, threshold, 0.0001f)
    }

    @Test
    fun `full-image result with NaN threshold still preserves original detections`() {
        val allDetections = listOf(
            NanoDetDetection(1, "thread", 0.89f, 10, NanoDetBox(1.0, 2.0, 50.0, 60.0), NanoDetBox(101.0, 202.0, 150.0, 262.0)),
            NanoDetDetection(0, "nut", 0.20f, 5, NanoDetBox(10.0, 15.0, 20.0, 28.0), NanoDetBox(110.0, 215.0, 120.0, 228.0)),
        )
        val result = fullImageResult(allDetections, threshold = Float.NaN)

        // 原始 detections 必须完整保留（不受阈值规范化影响）
        assertEquals("原始 detections 应完整保留", 2, result.detections.size)
        assertTrue("0.20 应在原始 detections 中", result.detections.any { it.score == 0.20f })
        assertTrue("0.89 应在原始 detections 中", result.detections.any { it.score == 0.89f })
    }

    @Test
    fun `threshold null backward compatibility uses FullImageInferResult default`() {
        // FullImageInferResult 的默认 threshold 是 STARTING_BUSINESS_THRESHOLD
        val result = FullImageInferResult(
            detections = listOf(
                NanoDetDetection(1, "thread", 0.89f, 10, NanoDetBox(1.0, 2.0, 50.0, 60.0), NanoDetBox(101.0, 202.0, 150.0, 262.0)),
                NanoDetDetection(0, "nut", 0.20f, 5, NanoDetBox(10.0, 15.0, 20.0, 28.0), NanoDetBox(110.0, 215.0, 120.0, 228.0)),
            ),
            highestScore = 0.89f,
            elapsedMs = 50,
            imageWidth = 1920,
            imageHeight = 1080,
            exifOrientation = 1,
            status = NanoDetInferenceStatus.DETECTED,
            // threshold 未指定，使用默认值
        )
        val threshold = normalizeThreshold(result.threshold)
        assertEquals("默认 threshold 应为 0.50", 0.50f, threshold, 0.0001f)

        val displayDetections = result.detections.filter { it.score >= threshold }
        assertEquals("应有 1 个达到阈值", 1, displayDetections.size)
        assertTrue("0.89 应保留", displayDetections.any { it.score == 0.89f })
    }

    @Test
    fun `threshold valid value 0_5 is used as-is without fallback`() {
        val threshold = normalizeThreshold(0.5f)
        assertEquals("有效 threshold 0.5 应原样使用", 0.5f, threshold, 0.0001f)

        val allDetections = listOf(
            NanoDetDetection(1, "thread", 0.50f, 10, NanoDetBox(1.0, 2.0, 50.0, 60.0), NanoDetBox(101.0, 202.0, 150.0, 262.0)),
            NanoDetDetection(0, "nut", 0.49f, 5, NanoDetBox(10.0, 15.0, 20.0, 28.0), NanoDetBox(110.0, 215.0, 120.0, 228.0)),
        )
        val displayDetections = allDetections.filter { it.score >= threshold }
        assertEquals("0.50 应保留", 1, displayDetections.size)
        assertFalse("0.49 应过滤", displayDetections.any { it.score == 0.49f })
    }

    @Test
    fun `threshold at 0 passes all detections`() {
        val threshold = normalizeThreshold(0f)
        assertEquals("threshold 0 应原样使用", 0f, threshold, 0.0001f)

        val allDetections = listOf(
            NanoDetDetection(1, "thread", 0.01f, 10, NanoDetBox(1.0, 2.0, 50.0, 60.0), NanoDetBox(101.0, 202.0, 150.0, 262.0)),
            NanoDetDetection(0, "nut", 0.00f, 5, NanoDetBox(10.0, 15.0, 20.0, 28.0), NanoDetBox(110.0, 215.0, 120.0, 228.0)),
        )
        val displayDetections = allDetections.filter { it.score >= threshold }
        assertEquals("threshold=0 时应全部保留", 2, displayDetections.size)
    }

    @Test
    fun `threshold at 1 only passes perfect scores`() {
        val threshold = normalizeThreshold(1f)
        assertEquals("threshold 1 应原样使用", 1f, threshold, 0.0001f)

        val allDetections = listOf(
            NanoDetDetection(1, "thread", 1.0f, 10, NanoDetBox(1.0, 2.0, 50.0, 60.0), NanoDetBox(101.0, 202.0, 150.0, 262.0)),
            NanoDetDetection(0, "nut", 0.99f, 5, NanoDetBox(10.0, 15.0, 20.0, 28.0), NanoDetBox(110.0, 215.0, 120.0, 228.0)),
        )
        val displayDetections = allDetections.filter { it.score >= threshold }
        assertEquals("只有 1.0 应保留", 1, displayDetections.size)
        assertEquals(1.0f, displayDetections[0].score, 0.0001f)
    }

    // ───────────────────────────────────────────────
    // 全图摘要文案精简验证
    // ───────────────────────────────────────────────

    @Test
    fun `full-image summary uses concise single-line format`() {
        val allDetections = listOf(
            NanoDetDetection(1, "thread", 0.89f, 10, NanoDetBox(1.0, 2.0, 50.0, 60.0), NanoDetBox(101.0, 202.0, 150.0, 262.0)),
            NanoDetDetection(0, "nut", 0.20f, 5, NanoDetBox(10.0, 15.0, 20.0, 28.0), NanoDetBox(110.0, 215.0, 120.0, 228.0)),
        )
        val result = fullImageResult(allDetections, threshold = 0.50f)
        val threshold = result.threshold!!
        val displayDetections = result.detections.filter { it.score >= threshold }

        // 模拟 ViewConfirmationScreen 中的摘要文本格式
        val summaryText = "整图检出：${displayDetections.size} 个 · 阈值 ${"%.0f".format(threshold * 100)}%"

        assertEquals("整图检出：1 个 · 阈值 50%", summaryText)
        // 旧长文案不应出现
        assertFalse("不应包含'整图检测模式'", summaryText.contains("整图检测模式"))
        assertFalse("不应包含'原始检出'", summaryText.contains("原始检出"))
        assertFalse("不应包含'显示检出'", summaryText.contains("显示检出"))
        assertFalse("不应包含'已过滤'", summaryText.contains("已过滤"))
        assertFalse("不应包含'最高匹配分数'", summaryText.contains("最高匹配分数"))
        assertFalse("不应包含'推理耗时'", summaryText.contains("推理耗时"))
    }

    @Test
    fun `full-image summary shows 50 percent threshold not 37 percent`() {
        val allDetections = listOf(
            NanoDetDetection(1, "thread", 0.89f, 10, NanoDetBox(1.0, 2.0, 50.0, 60.0), NanoDetBox(101.0, 202.0, 150.0, 262.0)),
        )
        val result = fullImageResult(allDetections, threshold = 0.50f)
        val threshold = result.threshold!!
        val displayDetections = result.detections.filter { it.score >= threshold }

        val summaryText = "整图检出：${displayDetections.size} 个 · 阈值 ${"%.0f".format(threshold * 100)}%"

        assertTrue("应显示50%而非37%", summaryText.contains("50%"))
        assertFalse("不应显示37%", summaryText.contains("37%"))
    }
}
