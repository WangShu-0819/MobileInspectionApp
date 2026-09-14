package com.wearable.inspection.mobile.ncnn

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
class NcnnRuntimeSmokeInstrumentedTest {
    private data class Detection(
        val className: String,
        val score: Float,
        val box: List<Double>,
        val point: Int
    )

    private data class Transform(
        val width: Int,
        val height: Int,
        val resizedWidth: Int,
        val resizedHeight: Int,
        val scaleX: Double,
        val scaleY: Double
    )

    @Test
    fun loadsModelRunsBothImagesAndMatchesDesktopNcnnDetections() {
        assertTrue("OpenCV local runtime failed to load", OpenCVLoader.initLocal())

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext
        val workspace = File(targetContext.cacheDir, "ncnn_runtime_smoke").apply { mkdirs() }
        val modelDir = File(workspace, "model").apply { mkdirs() }
        copyAsset(testContext, "ncnn_smoke/model/nanodet.ncnn.param", File(modelDir, "nanodet.ncnn.param"))
        copyAsset(testContext, "ncnn_smoke/model/nanodet.ncnn.bin", File(modelDir, "nanodet.ncnn.bin"))
        val parity = JSONObject(testContext.assets.open("ncnn_smoke/parity_results.json").bufferedReader().use { it.readText() })

        val runReport = JSONObject()
            .put("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull())
            .put("inputBlob", "in0")
            .put("inputShape", "[1,3,416,416]")
            .put("outputBlob", "out0")
            .put("outputShape", "[3598,34]")
            .put("ncnnLibrary", "libncnn.so (androidTest arm64-v8a)")
        val images = JSONObject()

        for (filename in IMAGE_NAMES) {
            val imageFile = File(workspace, filename)
            copyAsset(testContext, "ncnn_smoke/images/$filename", imageFile)
            val (source, input, transform) = preprocessBgr(imageFile)
            val output = NcnnSmokeNative.run(
                File(modelDir, "nanodet.ncnn.param").absolutePath,
                File(modelDir, "nanodet.ncnn.bin").absolutePath,
                input
            )
            assertEquals("NCNN output float count for $filename", 3598 * 34, output.size)
            assertTrue("NCNN output contains non-finite values for $filename", output.all(Float::isFinite))

            val expectedImage = parity.getJSONObject("images").getJSONObject(filename)
            val expectedDetections = expectedImage.getJSONObject("top_detections_at_0_05").getJSONObject("ncnn")
            val actualByClass = decode(output, transform, 0.05f)
            val perImage = JSONObject()
                .put("sourceSize", "${transform.width}x${transform.height}")
                .put("resized", "${transform.resizedWidth}x${transform.resizedHeight}")
                .put("padding", "top-left; right=${416 - transform.resizedWidth}, bottom=${416 - transform.resizedHeight}")
                .put("inputShape", "[1,3,416,416]")
                .put("outputBlob", "out0")
                .put("outputShape", "[3598,34]")
            val detectionsJson = JSONObject()
            val differences = JSONObject()
            val thresholdCounts = JSONObject()
            val expectedThresholds = expectedImage.getJSONObject("detection_counts_after_nms_by_threshold")

            for (className in CLASS_NAMES) {
                val expectedArray = expectedDetections.getJSONArray(className)
                val actual = actualByClass.getValue(className)
                assertEquals("$filename $className detection count", expectedArray.length(), actual.size)
                val classJson = org.json.JSONArray()
                val classDiffs = org.json.JSONArray()
                val expectedByPoint = (0 until expectedArray.length()).associate { index ->
                    val expected = expectedArray.getJSONObject(index)
                    expected.getInt("point") to expected
                }
                val comparedPoints = mutableSetOf<Int>()
                for (actualDetection in actual) {
                    classJson.put(
                        JSONObject()
                            .put("point", actualDetection.point)
                            .put("class", actualDetection.className)
                            .put("score", actualDetection.score.toDouble())
                            .put("box", org.json.JSONArray(actualDetection.box))
                    )
                    val expected = expectedByPoint[actualDetection.point]
                        ?: throw AssertionError("$filename $className unexpected point ${actualDetection.point}")
                    comparedPoints.add(actualDetection.point)
                    val expectedBox = expected.getJSONArray("box")
                    val scoreDiff = kotlin.math.abs(actualDetection.score - expected.getDouble("score"))
                    val boxDiffs = (0..3).map { kotlin.math.abs(actualDetection.box[it] - expectedBox.getDouble(it)) }
                    val maxBoxDiff = boxDiffs.maxOrNull() ?: 0.0
                    assertTrue("$filename $className point ${actualDetection.point} score diff $scoreDiff", scoreDiff <= MAX_SCORE_DIFF)
                    assertTrue("$filename $className point ${actualDetection.point} box diff $maxBoxDiff px", maxBoxDiff <= MAX_BOX_DIFF_PX)
                    classDiffs.put(
                        JSONObject()
                            .put("point", actualDetection.point)
                            .put("desktopMatch", true)
                            .put("scoreAbsDiff", scoreDiff)
                            .put("boxAbsDiffPx", org.json.JSONArray(boxDiffs))
                            .put("maxBoxAbsDiffPx", maxBoxDiff)
                    )
                }
                val unmatchedExpected = org.json.JSONArray()
                expectedByPoint.keys.filterNot(comparedPoints::contains).forEach(unmatchedExpected::put)
                detectionsJson.put(className, classJson)
                differences.put(
                    className,
                    JSONObject()
                        .put("desktopCount", expectedArray.length())
                        .put("androidCount", actual.size)
                        .put("countDelta", actual.size - expectedArray.length())
                        .put("comparisons", classDiffs)
                        .put("desktopPointsMissingOnAndroid", unmatchedExpected)
                )
            }
            for (threshold in THRESHOLDS) {
                val atThreshold = decode(output, transform, threshold)
                val expectedAtThreshold = expectedThresholds.getJSONObject(threshold.toString()).getJSONObject("ncnn")
                assertEquals("$filename $threshold nut count", expectedAtThreshold.getInt("nut"), atThreshold.getValue("nut").size)
                assertEquals("$filename $threshold thread count", expectedAtThreshold.getInt("thread"), atThreshold.getValue("thread").size)
                thresholdCounts.put(
                    threshold.toString(),
                    JSONObject()
                        .put("android", JSONObject().put("nut", atThreshold.getValue("nut").size).put("thread", atThreshold.getValue("thread").size))
                        .put("desktopNcnn", expectedThresholds.getJSONObject(threshold.toString()).getJSONObject("ncnn"))
                )
            }
            perImage.put("detectionsAt0_05", detectionsJson)
                .put("differencesVsDesktopNcnn", differences)
                .put("countsByThreshold", thresholdCounts)
            images.put(filename, perImage)
            Log.i(LOG_TAG, JSONObject().put(filename, perImage).toString())
            println("$LOG_TAG:${JSONObject().put(filename, perImage)}")
            source.release()
        }

        runReport.put("images", images)
            .put("rawTensorElementwiseComparison", "not performed: parity_results.json does not contain complete raw tensors")
        val report = runReport.toString()
        Log.i(LOG_TAG, report)
        println("$LOG_TAG:$report")
    }

    private fun copyAsset(context: android.content.Context, asset: String, destination: File) {
        context.assets.open(asset).use { input -> destination.outputStream().use(input::copyTo) }
    }

    private fun preprocessBgr(imageFile: File): Triple<Mat, FloatArray, Transform> {
        val source = Imgcodecs.imread(imageFile.absolutePath, Imgcodecs.IMREAD_COLOR)
        assertTrue("OpenCV could not decode ${imageFile.name}", !source.empty())
        val width = source.cols()
        val height = source.rows()
        val scale = min(416.0 / width, 416.0 / height)
        val resizedWidth = max(1, (width * scale).toInt())
        val resizedHeight = max(1, (height * scale).toInt())
        val resized = Mat()
        Imgproc.resize(source, resized, Size(resizedWidth.toDouble(), resizedHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)

        val canvas = Mat.zeros(416, 416, CvType.CV_8UC3)
        resized.copyTo(canvas.submat(Rect(0, 0, resizedWidth, resizedHeight)))
        val floatCanvas = Mat()
        canvas.convertTo(floatCanvas, CvType.CV_32FC3)
        val normalized = Mat()
        Core.subtract(floatCanvas, Scalar(103.53, 116.28, 123.675), normalized)
        Core.divide(normalized, Scalar(57.375, 57.12, 58.395), normalized)
        if (resizedWidth < 416) normalized.submat(Rect(resizedWidth, 0, 416 - resizedWidth, 416)).setTo(Scalar(0.0, 0.0, 0.0))
        if (resizedHeight < 416) normalized.submat(Rect(0, resizedHeight, 416, 416 - resizedHeight)).setTo(Scalar(0.0, 0.0, 0.0))

        val tensor = FloatArray(3 * 416 * 416)
        val row = FloatArray(416 * 3)
        for (y in 0 until 416) {
            normalized.get(y, 0, row)
            for (x in 0 until 416) {
                val pixel = x * 3
                val planar = y * 416 + x
                tensor[planar] = row[pixel]
                tensor[416 * 416 + planar] = row[pixel + 1]
                tensor[2 * 416 * 416 + planar] = row[pixel + 2]
            }
        }
        resized.release()
        canvas.release()
        floatCanvas.release()
        normalized.release()
        return Triple(source, tensor, Transform(width, height, resizedWidth, resizedHeight, resizedWidth.toDouble() / width, resizedHeight.toDouble() / height))
    }

    private fun decode(output: FloatArray, transform: Transform, scoreThreshold: Float): Map<String, List<Detection>> {
        val candidates = CLASS_NAMES.associateWith { mutableListOf<Detection>() }
        var offset = 0
        for (stride in STRIDES) {
            val featureSize = ceil(416.0 / stride).toInt()
            for (y in 0 until featureSize) for (x in 0 until featureSize) {
                val point = offset + y * featureSize + x
                val cx = x * stride
                val cy = y * stride
                if (cx >= transform.resizedWidth || cy >= transform.resizedHeight) continue
                val row = point * OUTPUT_WIDTH
                val classIndex = if (output[row + 1] > output[row]) 1 else 0
                val score = output[row + classIndex]
                if (score < scoreThreshold) continue
                val distances = DoubleArray(4)
                for (side in 0 until 4) {
                    val base = row + 2 + side * 8
                    var maxLogit = Float.NEGATIVE_INFINITY
                    for (i in 0 until 8) maxLogit = max(maxLogit, output[base + i])
                    val probabilities = FloatArray(8)
                    var sum = 0f
                    for (i in 0 until 8) {
                        probabilities[i] = exp((output[base + i] - maxLogit).toDouble()).toFloat()
                        sum += probabilities[i]
                    }
                    var expectation = 0f
                    for (i in 0 until 8) expectation += probabilities[i] / sum * i
                    distances[side] = (expectation * stride).toDouble()
                }
                val box = listOf(
                    clamp((cx - distances[0]) / transform.scaleX, transform.width.toDouble()),
                    clamp((cy - distances[1]) / transform.scaleY, transform.height.toDouble()),
                    clamp((cx + distances[2]) / transform.scaleX, transform.width.toDouble()),
                    clamp((cy + distances[3]) / transform.scaleY, transform.height.toDouble())
                )
                candidates.getValue(CLASS_NAMES[classIndex]).add(Detection(CLASS_NAMES[classIndex], score, box, point))
            }
            offset += featureSize * featureSize
        }
        return candidates.mapValues { (_, boxes) -> nms(boxes) }
    }

    private fun nms(boxes: List<Detection>): List<Detection> {
        val kept = mutableListOf<Detection>()
        for (box in boxes.sortedByDescending { it.score }) {
            val suppressed = kept.any { previous -> iouInclusive(box.box, previous.box) > NMS_THRESHOLD }
            if (!suppressed) kept.add(box)
            if (kept.size == MAX_DETECTIONS) break
        }
        return kept
    }

    private fun iouInclusive(a: List<Double>, b: List<Double>): Double {
        val x1 = max(a[0], b[0])
        val y1 = max(a[1], b[1])
        val x2 = min(a[2], b[2])
        val y2 = min(a[3], b[3])
        val intersection = max(0.0, x2 - x1 + 1.0) * max(0.0, y2 - y1 + 1.0)
        val areaA = (a[2] - a[0] + 1.0) * (a[3] - a[1] + 1.0)
        val areaB = (b[2] - b[0] + 1.0) * (b[3] - b[1] + 1.0)
        return intersection / max(areaA + areaB - intersection, 1e-12)
    }

    private fun clamp(value: Double, limit: Double) = max(0.0, min(value, limit))

    private companion object {
        const val LOG_TAG = "NCNN_SMOKE"
        const val OUTPUT_WIDTH = 34
        const val NMS_THRESHOLD = 0.6
        const val MAX_DETECTIONS = 100
        const val MAX_SCORE_DIFF = 1e-5
        const val MAX_BOX_DIFF_PX = 0.01
        val IMAGE_NAMES = listOf("frame_00106_f1060.jpg", "frame_00045_f450.jpg")
        val CLASS_NAMES = listOf("nut", "thread")
        val STRIDES = listOf(8, 16, 32, 64)
        val THRESHOLDS = listOf(0.05f, 0.25f, 0.37f, 0.50f)
    }
}
