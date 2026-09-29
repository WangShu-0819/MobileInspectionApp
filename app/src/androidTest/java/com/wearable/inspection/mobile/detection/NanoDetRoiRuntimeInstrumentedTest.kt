package com.wearable.inspection.mobile.detection

import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class NanoDetRoiRuntimeInstrumentedTest {
    @Test
    fun savedFullImageRoiMatchesDesktopNcnnForBothRegressionPhotos() {
        assertTrue("OpenCV local runtime failed to load", OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext
        val workspace = File(targetContext.cacheDir, "nanodet_roi_parity").apply { mkdirs() }
        val parity = JSONObject(
            testContext.assets.open("ncnn_smoke/exp09_parity_results.json").bufferedReader().use { it.readText() }
        )
        val service = NanoDetRoiInferenceService(targetContext, partId = LEGACY_PART_ID)
        val comparisons = JSONObject()

        try {
            for (filename in IMAGE_NAMES) {
                val imageFile = File(workspace, filename)
                testContext.assets.open("ncnn_smoke/images/$filename").use { input ->
                    imageFile.outputStream().use(input::copyTo)
                }
                val roi = fullImageRoi("full-$filename", "THREAD")
                val result = service.inferSavedPhoto(
                    imageFile.absolutePath,
                    listOf(roi),
                    ExifInterface.ORIENTATION_NORMAL
                ).getValue(roi.id)
                val expectedImage = parity.getJSONObject("images").getJSONObject(filename)
                val expected = expectedImage.getJSONObject("top_detections_at_0_05").getJSONObject("ncnn")
                val classReport = JSONObject()
                var maxScoreDiff = 0.0
                var maxBoxDiff = 0.0

                assertEquals(NanoDetModelContract.VERSION, result.modelVersion)
                assertEquals(NanoDetModelContract.PARAM_SHA256, result.modelParamSha256)
                assertEquals(NanoDetModelContract.MODEL_SHA256, result.modelSha256)
                assertEquals("[1,3,416,416]", result.inputShape)
                assertEquals("out0", result.outputBlob)
                assertEquals("[0,0,${result.imageWidth},${result.imageHeight}]", result.roiBounds.toString().replace(" ", ""))
                assertEquals(0.50f, result.threshold)
                assertEquals(0.05f, result.candidateThreshold)
                val expectedTotalCount = CLASS_NAMES.sumOf { expected.getJSONArray(it).length() }
                assertEquals(expectedTotalCount, result.detections.size)

                for (className in CLASS_NAMES) {
                    val expectedItems = expected.getJSONArray(className)
                    val actualItems = result.detections.filter { it.className == className }
                    assertEquals("$filename $className count", expectedItems.length(), actualItems.size)
                    val expectedByPoint = (0 until expectedItems.length()).associate { index ->
                        val item = expectedItems.getJSONObject(index)
                        item.getInt("point") to item
                    }
                    val rows = org.json.JSONArray()
                    for (detection in actualItems) {
                        val expectedItem = expectedByPoint[detection.point]
                            ?: throw AssertionError("$filename unexpected $className point ${detection.point}")
                        val expectedBox = expectedItem.getJSONArray("box")
                        val scoreDiff = abs(detection.score - expectedItem.getDouble("score"))
                        val boxDiffs = listOf(
                            abs(detection.imageBox.left - expectedBox.getDouble(0)),
                            abs(detection.imageBox.top - expectedBox.getDouble(1)),
                            abs(detection.imageBox.right - expectedBox.getDouble(2)),
                            abs(detection.imageBox.bottom - expectedBox.getDouble(3))
                        )
                        val boxDiff = boxDiffs.maxOrNull() ?: 0.0
                        maxScoreDiff = maxOf(maxScoreDiff, scoreDiff)
                        maxBoxDiff = maxOf(maxBoxDiff, boxDiff)
                        assertTrue("$filename $className score diff $scoreDiff", scoreDiff <= MAX_SCORE_DIFF)
                        assertTrue("$filename $className box diff $boxDiff", boxDiff <= MAX_BOX_DIFF_PX)
                        rows.put(
                            JSONObject()
                                .put("point", detection.point)
                                .put("score", detection.score.toDouble())
                                .put("imageBox", org.json.JSONArray(listOf(
                                    detection.imageBox.left,
                                    detection.imageBox.top,
                                    detection.imageBox.right,
                                    detection.imageBox.bottom
                                )))
                                .put("maxBoxDiffPx", boxDiff)
                        )
                    }
                    classReport.put(className, JSONObject().put("count", actualItems.size).put("detections", rows))
                }

                val expectedThresholds = expectedImage.getJSONObject("detection_counts_after_nms_by_threshold")
                val thresholdCounts = JSONObject()
                for (threshold in THRESHOLDS) {
                    val actualAtThreshold = result.detections.filter { it.score >= threshold }
                    val actualCounts = CLASS_NAMES.associateWith { className ->
                        actualAtThreshold.count { it.className == className }
                    }
                    val expectedCounts = expectedThresholds.getJSONObject(threshold.toString()).getJSONObject("ncnn")
                    for (className in CLASS_NAMES) {
                        val expectedCount = if (expectedCounts.has(className)) expectedCounts.getInt(className) else 0
                        assertEquals("$filename $threshold $className", expectedCount, actualCounts.getValue(className))
                    }
                    val thresholdJson = JSONObject()
                    for (className in CLASS_NAMES) thresholdJson.put(className, actualCounts.getValue(className))
                    thresholdCounts.put(threshold.toString(), thresholdJson)
                }

                val expectedThread = expected.getJSONArray("thread")
                val bestThreadScore = (0 until expectedThread.length())
                    .map { expectedThread.getJSONObject(it).getDouble("score") }
                    .maxOrNull()
                val expectedSuggestion = if (bestThreadScore != null && bestThreadScore >= 0.50) {
                    NanoDetSuggestion.OK
                } else {
                    NanoDetSuggestion.NG
                }
                assertEquals(expectedSuggestion, result.modelSuggestion)
                assertTrue(result.elapsedMs >= 0)
                comparisons.put(
                    filename,
                    JSONObject()
                        .put("imageSize", "${result.imageWidth}x${result.imageHeight}")
                        .put("partId", LEGACY_PART_ID)
                        .put("route", ModelRoute.EXP09.name)
                        .put("modelVersion", result.modelVersion)
                        .put("modelParamSha256", result.modelParamSha256)
                        .put("modelSha256", result.modelSha256)
                        .put("photoSha256", sha256(imageFile))
                        .put("runtime", "NanoDetRoiInferenceService -> NanoDetNcnnNative -> libnanodet_ncnn_runtime.so")
                        .put("exifOrientation", result.exifOrientation)
                        .put("outputShape", "[3598,36]")
                        .put("status", result.status.name)
                        .put("suggestion", result.modelSuggestion?.name)
                        .put("matchingScore", result.matchingScore?.toDouble())
                        .put("elapsedMs", result.elapsedMs)
                        .put("maxScoreAbsDiff", maxScoreDiff)
                        .put("maxBoxAbsDiffPx", maxBoxDiff)
                        .put("detections", classReport)
                        .put("countsByThreshold", thresholdCounts)
                )
            }
        } finally {
            service.close()
        }
        android.util.Log.i("NanoDetServiceRoute", comparisons.toString())
    }

    // ── 有效推理状态集合 ──

    private val VALID_FULL_IMAGE_STATUSES = setOf(
        NanoDetInferenceStatus.DETECTED,
        NanoDetInferenceStatus.NO_DETECTION,
    )
    private val VALID_ROI_STATUSES = setOf(
        NanoDetInferenceStatus.DETECTED,
        NanoDetInferenceStatus.DETECTED_BELOW_THRESHOLD,
        NanoDetInferenceStatus.NO_DETECTION,
    )

    // ── exp22 Black: 全图推理 ──

    @Test
    fun exp22BlackServiceRoutesAndInfersFullImage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext
        val imageFile = File(targetContext.cacheDir, "nanodet_roi_parity/${IMAGE_NAMES.first()}")
        imageFile.parentFile?.mkdirs()
        testContext.assets.open("ncnn_smoke/images/${IMAGE_NAMES.first()}").use { input ->
            imageFile.outputStream().use(input::copyTo)
        }
        val photoSha = sha256(imageFile)
        val partId = "Black_test_001"

        val service = NanoDetRoiInferenceService(targetContext, partId = partId)
        try {
            val result = service.inferFullImage(imageFile.absolutePath)
            assertTrue("exp22 full-image status ${result.status} 应为有效推理状态",
                result.status in VALID_FULL_IMAGE_STATUSES)
            assertExp22Identity(result.modelVersion, result.modelParamSha256, result.modelSha256)

            val evidence = JSONObject()
                .put("test", "exp22BlackServiceRoutesAndInfersFullImage")
                .put("partId", partId)
                .put("route", ModelRoute.EXP22_BLACK.name)
                .put("modelVersion", result.modelVersion)
                .put("modelParamSha256", result.modelParamSha256)
                .put("modelSha256", result.modelSha256)
                .put("status", result.status.name)
                .put("detectionCount", result.detections.size)
                .put("elapsedMs", result.elapsedMs)
                .put("photoSha256", photoSha)
            android.util.Log.i("NanoDetServiceRoute", evidence.toString())
        } finally {
            service.close()
        }
    }

    // ── exp22 Black: ROI 推理，全部 4 类 ──

    @Test
    fun exp22BlackServiceRoutesAllFourClasses() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext
        val imageFile = File(targetContext.cacheDir, "nanodet_roi_parity/${IMAGE_NAMES.first()}")
        imageFile.parentFile?.mkdirs()
        testContext.assets.open("ncnn_smoke/images/${IMAGE_NAMES.first()}").use { input ->
            imageFile.outputStream().use(input::copyTo)
        }
        val photoSha = sha256(imageFile)
        val partId = "Black_cls_test"

        // exp22 黑件类别映射：THREAD→0, NUTSERT→1, NUT→2, BOLT→3
        val classTests = listOf(
            Triple("THREAD", 0, "exp22-roi-thread"),
            Triple("NUTSERT", 1, "exp22-roi-nutsert"),
            Triple("NUT", 2, "exp22-roi-nut"),
            Triple("BOLT", 3, "exp22-roi-bolt"),
        )

        val service = NanoDetRoiInferenceService(targetContext, partId = partId)
        try {
            for ((targetType, expectedClassIdx, roiId) in classTests) {
                val roi = fullImageRoi(roiId, targetType)
                val result = service.inferSavedPhoto(
                    imageFile.absolutePath, listOf(roi), ExifInterface.ORIENTATION_NORMAL
                ).getValue(roi.id)
                assertTrue("exp22 $targetType status ${result.status} 应为有效推理状态",
                    result.status in VALID_ROI_STATUSES)
                assertExp22Identity(result.modelVersion, result.modelParamSha256, result.modelSha256)
                assertEquals("exp22 $targetType 应映射到 class $expectedClassIdx",
                    expectedClassIdx, result.targetClassIndex)

                val evidence = JSONObject()
                    .put("test", "exp22BlackServiceRoutesAllFourClasses")
                    .put("partId", partId)
                    .put("route", ModelRoute.EXP22_BLACK.name)
                    .put("targetType", targetType)
                    .put("targetClassIndex", result.targetClassIndex)
                    .put("modelVersion", result.modelVersion)
                    .put("modelParamSha256", result.modelParamSha256)
                    .put("modelSha256", result.modelSha256)
                    .put("status", result.status.name)
                    .put("matchingScore", result.matchingScore?.toDouble())
                    .put("detectionCount", result.detections.size)
                    .put("elapsedMs", result.elapsedMs)
                    .put("photoSha256", photoSha)
                android.util.Log.i("NanoDetServiceRoute", evidence.toString())
            }
        } finally {
            service.close()
        }
    }

    // ── exp23 White: 全图推理 ──

    @Test
    fun exp23WhiteServiceRoutesAndInfersFullImage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext
        val imageFile = File(targetContext.cacheDir, "nanodet_roi_parity/${IMAGE_NAMES.first()}")
        imageFile.parentFile?.mkdirs()
        testContext.assets.open("ncnn_smoke/images/${IMAGE_NAMES.first()}").use { input ->
            imageFile.outputStream().use(input::copyTo)
        }
        val photoSha = sha256(imageFile)
        val partId = "White_test_001"

        val service = NanoDetRoiInferenceService(targetContext, partId = partId)
        try {
            val result = service.inferFullImage(imageFile.absolutePath)
            assertTrue("exp23 full-image status ${result.status} 应为有效推理状态",
                result.status in VALID_FULL_IMAGE_STATUSES)
            assertExp23Identity(result.modelVersion, result.modelParamSha256, result.modelSha256)

            val evidence = JSONObject()
                .put("test", "exp23WhiteServiceRoutesAndInfersFullImage")
                .put("partId", partId)
                .put("route", ModelRoute.EXP23_WHITE.name)
                .put("modelVersion", result.modelVersion)
                .put("modelParamSha256", result.modelParamSha256)
                .put("modelSha256", result.modelSha256)
                .put("status", result.status.name)
                .put("detectionCount", result.detections.size)
                .put("elapsedMs", result.elapsedMs)
                .put("photoSha256", photoSha)
            android.util.Log.i("NanoDetServiceRoute", evidence.toString())
        } finally {
            service.close()
        }
    }

    // ── exp23 White: ROI 推理，NUT→0, THREAD→1 ──

    @Test
    fun exp23WhiteServiceRoutesSupportedClasses() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext
        val imageFile = File(targetContext.cacheDir, "nanodet_roi_parity/${IMAGE_NAMES.first()}")
        imageFile.parentFile?.mkdirs()
        testContext.assets.open("ncnn_smoke/images/${IMAGE_NAMES.first()}").use { input ->
            imageFile.outputStream().use(input::copyTo)
        }
        val photoSha = sha256(imageFile)
        val partId = "White_cls_test"

        // exp23 白件类别映射：NUT→0, THREAD→1
        val classTests = listOf(
            Pair("NUT", 0),
            Pair("THREAD", 1),
        )

        val service = NanoDetRoiInferenceService(targetContext, partId = partId)
        try {
            for ((targetType, expectedClassIdx) in classTests) {
                val roi = fullImageRoi("exp23-roi-${targetType.lowercase()}", targetType)
                val result = service.inferSavedPhoto(
                    imageFile.absolutePath, listOf(roi), ExifInterface.ORIENTATION_NORMAL
                ).getValue(roi.id)
                assertTrue("exp23 $targetType status ${result.status} 应为有效推理状态",
                    result.status in VALID_ROI_STATUSES)
                assertExp23Identity(result.modelVersion, result.modelParamSha256, result.modelSha256)
                assertEquals("exp23 $targetType 应映射到 class $expectedClassIdx",
                    expectedClassIdx, result.targetClassIndex)

                val evidence = JSONObject()
                    .put("test", "exp23WhiteServiceRoutesSupportedClasses")
                    .put("partId", partId)
                    .put("route", ModelRoute.EXP23_WHITE.name)
                    .put("targetType", targetType)
                    .put("targetClassIndex", result.targetClassIndex)
                    .put("modelVersion", result.modelVersion)
                    .put("modelParamSha256", result.modelParamSha256)
                    .put("modelSha256", result.modelSha256)
                    .put("status", result.status.name)
                    .put("matchingScore", result.matchingScore?.toDouble())
                    .put("detectionCount", result.detections.size)
                    .put("elapsedMs", result.elapsedMs)
                    .put("photoSha256", photoSha)
                android.util.Log.i("NanoDetServiceRoute", evidence.toString())
            }
        } finally {
            service.close()
        }
    }

    // ── exp23 White: BOLT/NUTSERT → MODEL_TARGET_UNSUPPORTED，不回退到 exp09 ──

    @Test
    fun exp23WhiteBoltNutsertReturnsModelTargetUnsupported() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext
        val imageFile = File(targetContext.cacheDir, "nanodet_roi_parity/${IMAGE_NAMES.first()}")
        imageFile.parentFile?.mkdirs()
        testContext.assets.open("ncnn_smoke/images/${IMAGE_NAMES.first()}").use { input ->
            imageFile.outputStream().use(input::copyTo)
        }
        val photoSha = sha256(imageFile)
        val partId = "White_unsupported_test"

        val unsupportedTests = listOf("BOLT", "NUTSERT")

        val service = NanoDetRoiInferenceService(targetContext, partId = partId)
        try {
            for (targetType in unsupportedTests) {
                val roi = fullImageRoi("exp23-unsup-$targetType", targetType)
                val result = service.inferSavedPhoto(
                    imageFile.absolutePath, listOf(roi), ExifInterface.ORIENTATION_NORMAL
                ).getValue(roi.id)
                assertEquals("exp23 $targetType 应返回 MODEL_TARGET_UNSUPPORTED",
                    NanoDetInferenceStatus.MODEL_TARGET_UNSUPPORTED, result.status)
                // 模型身份应为 exp23，不应回退到 exp09
                assertExp23Identity(result.modelVersion, result.modelParamSha256, result.modelSha256)
                assertNotEquals("不应为 exp09 版本", NanoDetModelContract.VERSION, result.modelVersion)
                assertNull("targetClassIndex 应为 null（不支持的类型）", result.targetClassIndex)

                val evidence = JSONObject()
                    .put("test", "exp23WhiteBoltNutsertReturnsModelTargetUnsupported")
                    .put("partId", partId)
                    .put("route", ModelRoute.EXP23_WHITE.name)
                    .put("targetType", targetType)
                    .put("targetClassIndex", result.targetClassIndex)
                    .put("modelVersion", result.modelVersion)
                    .put("modelParamSha256", result.modelParamSha256)
                    .put("modelSha256", result.modelSha256)
                    .put("status", result.status.name)
                    .put("elapsedMs", result.elapsedMs)
                    .put("photoSha256", photoSha)
                android.util.Log.i("NanoDetServiceRoute", evidence.toString())
            }
        } finally {
            service.close()
        }
    }

    // ── 无前缀旧零件 → exp09，使用有实际内容的旧 ID ──

    @Test
    fun legacyPartStillRoutesToExp09() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext
        val imageFile = File(targetContext.cacheDir, "nanodet_roi_parity/${IMAGE_NAMES.first()}")
        imageFile.parentFile?.mkdirs()
        testContext.assets.open("ncnn_smoke/images/${IMAGE_NAMES.first()}").use { input ->
            imageFile.outputStream().use(input::copyTo)
        }
        val photoSha = sha256(imageFile)
        val partId = LEGACY_PART_ID // "legacy_T2_service_route" — 有实际内容的无前缀旧 ID

        // 全图推理
        val fullService = NanoDetRoiInferenceService(targetContext, partId = partId)
        try {
            val fullResult = fullService.inferFullImage(imageFile.absolutePath)
            assertTrue("legacy full-image status ${fullResult.status} 应为有效推理状态",
                fullResult.status in VALID_FULL_IMAGE_STATUSES)
            assertEquals(NanoDetModelContract.VERSION, fullResult.modelVersion)
            assertEquals(NanoDetModelContract.PARAM_SHA256, fullResult.modelParamSha256)
            assertEquals(NanoDetModelContract.MODEL_SHA256, fullResult.modelSha256)

            val evidence = JSONObject()
                .put("test", "legacyPartStillRoutesToExp09_fullImage")
                .put("partId", partId)
                .put("route", ModelRoute.EXP09.name)
                .put("modelVersion", fullResult.modelVersion)
                .put("modelParamSha256", fullResult.modelParamSha256)
                .put("modelSha256", fullResult.modelSha256)
                .put("status", fullResult.status.name)
                .put("detectionCount", fullResult.detections.size)
                .put("elapsedMs", fullResult.elapsedMs)
                .put("photoSha256", photoSha)
            android.util.Log.i("NanoDetServiceRoute", evidence.toString())
        } finally {
            fullService.close()
        }

        // ROI 推理（NUT → exp09 class 0）
        val roiService = NanoDetRoiInferenceService(targetContext, partId = partId)
        try {
            val roi = fullImageRoi("legacy-roi-nut", "NUT")
            val roiResult = roiService.inferSavedPhoto(
                imageFile.absolutePath, listOf(roi), ExifInterface.ORIENTATION_NORMAL
            ).getValue(roi.id)
            assertTrue("legacy ROI status ${roiResult.status} 应为有效推理状态",
                roiResult.status in VALID_ROI_STATUSES)
            assertEquals(NanoDetModelContract.VERSION, roiResult.modelVersion)
            assertEquals(NanoDetModelContract.PARAM_SHA256, roiResult.modelParamSha256)
            assertEquals(NanoDetModelContract.MODEL_SHA256, roiResult.modelSha256)
            assertEquals("exp09 NUT 应映射到 class 0", 0, roiResult.targetClassIndex)

            val roiEvidence = JSONObject()
                .put("test", "legacyPartStillRoutesToExp09_roi")
                .put("partId", partId)
                .put("route", ModelRoute.EXP09.name)
                .put("targetType", "NUT")
                .put("targetClassIndex", roiResult.targetClassIndex)
                .put("modelVersion", roiResult.modelVersion)
                .put("modelParamSha256", roiResult.modelParamSha256)
                .put("modelSha256", roiResult.modelSha256)
                .put("status", roiResult.status.name)
                .put("matchingScore", roiResult.matchingScore?.toDouble())
                .put("detectionCount", roiResult.detections.size)
                .put("elapsedMs", roiResult.elapsedMs)
                .put("photoSha256", photoSha)
            android.util.Log.i("NanoDetServiceRoute", roiEvidence.toString())
        } finally {
            roiService.close()
        }
    }

    @Test
    fun exifRotationMatchesUprightPixelCoordinates() {
        assertTrue(OpenCVLoader.initLocal())
        val raw = Mat(2, 3, CvType.CV_8UC1)
        raw.put(0, 0, byteArrayOf(1, 2, 3, 4, 5, 6))
        val upright = orientBgrMat(raw, ExifInterface.ORIENTATION_ROTATE_90)
        try {
            assertEquals(2, upright.cols())
            assertEquals(3, upright.rows())
            val pixels = ByteArray(6)
            upright.get(0, 0, pixels)
            assertEquals(listOf(4, 1, 5, 2, 6, 3), pixels.map { it.toInt() and 0xff })
        } finally {
            if (upright !== raw) upright.release()
            raw.release()
        }
    }

    @Test
    fun jpegExifIsReadFromRawRasterBeforeManualOrientation() {
        assertTrue(OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val photo = File(instrumentation.targetContext.cacheDir, "nanodet_exif_${System.nanoTime()}.jpg")
        val source = Mat(2, 3, CvType.CV_8UC3)
        source.put(0, 0, ByteArray(18) { (it * 11).toByte() })
        assertTrue(Imgcodecs.imwrite(photo.absolutePath, source))
        source.release()
        androidx.exifinterface.media.ExifInterface(photo.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val geometry = com.wearable.inspection.mobile.ui.screens.RoiCoordinateMapper
            .getImageGeometry(photo.absolutePath)!!
        assertEquals(3, geometry.rawWidth)
        assertEquals(2, geometry.rawHeight)
        assertEquals(2, geometry.width)
        assertEquals(3, geometry.height)

        val raw = Imgcodecs.imread(
            photo.absolutePath,
            Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION
        )
        try {
            assertEquals(3, raw.cols())
            assertEquals(2, raw.rows())
            val upright = orientBgrMat(raw, geometry.exifOrientation)
            try {
                assertEquals(geometry.width, upright.cols())
                assertEquals(geometry.height, upright.rows())
            } finally {
                if (upright !== raw) upright.release()
            }
        } finally {
            raw.release()
            photo.delete()
        }
    }

    @Test
    fun runtimeAndInferenceFailuresRemainNonSuccessStates() {
        assertTrue(OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext
        val imageFile = File(targetContext.cacheDir, "nanodet_roi_parity/frame_00106_f1060.jpg")
        testContext.assets.open("ncnn_smoke/images/frame_00106_f1060.jpg").use { input ->
            imageFile.parentFile?.mkdirs()
            imageFile.outputStream().use(input::copyTo)
        }
        val roi = fullImageRoi("failure-roi", "NUT")

        val brokenRuntime = NanoDetRoiInferenceService(
            targetContext,
            runtimeFactory = NanoDetTensorRuntimeFactory { _, _, _ ->
                object : NanoDetTensorRuntime {
                    override fun infer(inputNchw: FloatArray): FloatArray = error("injected inference failure")
                    override fun close() = Unit
                }
            }
        )
        try {
            val result = brokenRuntime.inferSavedPhoto(imageFile.absolutePath, listOf(roi), ExifInterface.ORIENTATION_NORMAL)
                .getValue(roi.id)
            assertEquals(NanoDetInferenceStatus.INFERENCE_ERROR, result.status)
            assertNull(result.modelSuggestion)
            assertNull(result.matchingScore)
        } finally {
            brokenRuntime.close()
        }

        val missingRuntime = NanoDetRoiInferenceService(
            targetContext,
            runtimeFactory = NanoDetTensorRuntimeFactory { _, _, _ -> error("model open failed") }
        )
        try {
            val result = missingRuntime.inferSavedPhoto(imageFile.absolutePath, listOf(roi), ExifInterface.ORIENTATION_NORMAL)
                .getValue(roi.id)
            assertEquals(NanoDetInferenceStatus.MODEL_UNAVAILABLE, result.status)
            assertNull(result.modelSuggestion)
        } finally {
            missingRuntime.close()
        }

        val unavailableRuntime = NanoDetRoiInferenceService(
            targetContext,
            runtimeFactory = NanoDetTensorRuntimeFactory { _, _, _ -> throw UnsatisfiedLinkError("runtime missing") }
        )
        try {
            val result = unavailableRuntime.inferSavedPhoto(
                imageFile.absolutePath,
                listOf(roi),
                ExifInterface.ORIENTATION_NORMAL
            ).getValue(roi.id)
            assertEquals(NanoDetInferenceStatus.RUNTIME_UNAVAILABLE, result.status)
            assertNull(result.modelSuggestion)
            assertNull(result.matchingScore)
        } finally {
            unavailableRuntime.close()
        }
    }

    private fun assertExp22Identity(modelVersion: String?, paramSha: String?, modelSha: String?) {
        assertEquals(NanoDetModelContractExp22.VERSION, modelVersion)
        assertEquals(NanoDetModelContractExp22.PARAM_SHA256, paramSha)
        assertEquals(NanoDetModelContractExp22.MODEL_SHA256, modelSha)
    }

    private fun assertExp23Identity(modelVersion: String?, paramSha: String?, modelSha: String?) {
        assertEquals(NanoDetModelContractExp23.VERSION, modelVersion)
        assertEquals(NanoDetModelContractExp23.PARAM_SHA256, paramSha)
        assertEquals(NanoDetModelContractExp23.MODEL_SHA256, modelSha)
    }

    private fun fullImageRoi(id: String, targetType: String) =
        com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity(
            id = id,
            templateId = "regression-template",
            name = id,
            order = 0,
            normalizedRect = """{"left":0,"top":0,"right":1,"bottom":1}""",
            inspectionType = "PRESENCE",
            targetType = targetType
        )

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val LEGACY_PART_ID = "legacy_T2_service_route"
        val IMAGE_NAMES = listOf("frame_00106_f1060.jpg", "frame_00045_f450.jpg")
        val CLASS_NAMES = listOf("nut", "thread", "bolt", "nutsert")
        val THRESHOLDS = listOf(0.05f, 0.10f, 0.15f, 0.20f, 0.25f, 0.30f, 0.37f, 0.50f)
        const val MAX_SCORE_DIFF = 1e-5
        const val MAX_BOX_DIFF_PX = 0.01
    }
}
