package com.wearable.inspection.mobile.detection

import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
            testContext.assets.open("ncnn_smoke/parity_results.json").bufferedReader().use { it.readText() }
        )
        val service = NanoDetRoiInferenceService(targetContext)
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
                assertEquals("[1,3,416,416]", result.inputShape)
                assertEquals("out0", result.outputBlob)
                assertEquals("[0,0,${result.imageWidth},${result.imageHeight}]", result.roiBounds.toString().replace(" ", ""))
                assertEquals(0.37f, result.threshold)
                assertEquals(0.05f, result.candidateThreshold)
                assertEquals(expected.getJSONArray("nut").length() + expected.getJSONArray("thread").length(), result.detections.size)

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
                    val actualNut = actualAtThreshold.count { it.classIndex == 0 }
                    val actualThread = actualAtThreshold.count { it.classIndex == 1 }
                    val expectedCounts = expectedThresholds.getJSONObject(threshold.toString()).getJSONObject("ncnn")
                    assertEquals("$filename $threshold nut", expectedCounts.getInt("nut"), actualNut)
                    assertEquals("$filename $threshold thread", expectedCounts.getInt("thread"), actualThread)
                    thresholdCounts.put(threshold.toString(), JSONObject().put("nut", actualNut).put("thread", actualThread))
                }

                val expectedThread = expected.getJSONArray("thread")
                val bestThreadScore = (0 until expectedThread.length())
                    .map { expectedThread.getJSONObject(it).getDouble("score") }
                    .maxOrNull()
                val expectedSuggestion = if (bestThreadScore != null && bestThreadScore >= 0.37) {
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
                        .put("exifOrientation", result.exifOrientation)
                        .put("outputShape", "[3598,34]")
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
        println("NANODET_ROI_PARITY:${comparisons}")
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
            runtimeFactory = NanoDetTensorRuntimeFactory { _, _ ->
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
            runtimeFactory = NanoDetTensorRuntimeFactory { _, _ -> error("model open failed") }
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
            runtimeFactory = NanoDetTensorRuntimeFactory { _, _ -> throw UnsatisfiedLinkError("runtime missing") }
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

    private companion object {
        val IMAGE_NAMES = listOf("frame_00106_f1060.jpg", "frame_00045_f450.jpg")
        val CLASS_NAMES = listOf("nut", "thread")
        val THRESHOLDS = listOf(0.05f, 0.25f, 0.37f, 0.50f)
        const val MAX_SCORE_DIFF = 1e-5
        const val MAX_BOX_DIFF_PX = 0.01
    }
}
