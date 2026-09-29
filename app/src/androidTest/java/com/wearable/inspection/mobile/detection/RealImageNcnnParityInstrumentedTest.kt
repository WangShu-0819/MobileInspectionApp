package com.wearable.inspection.mobile.detection

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wearable.inspection.mobile.ncnn.NcnnSmokeNative
import com.wearable.inspection.mobile.ui.screens.RoiCoordinateMapper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.time.Instant
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

/** Runs the fixed image through production decode/orientation, preprocessing and decoding. */
@RunWith(AndroidJUnit4::class)
class RealImageNcnnParityInstrumentedTest {

    @Test
    fun exp22RealImageMatchesDesktopNcnn() = runParity(
        label = "exp22",
        config = NanoDetModelContractExp22.CONFIG,
        classNames = Exp22BlackClassPolicy.classNames,
    )

    @Test
    fun exp23RealImageMatchesDesktopNcnn() = runParity(
        label = "exp23",
        config = NanoDetModelContractExp23.CONFIG,
        classNames = Exp23WhiteClassPolicy.classNames,
    )

    private fun runParity(label: String, config: ModelConfig, classNames: Array<String>) {
        val apkIdentity = installedApkIdentityJson
            ?: failedInstalledApkIdentity(IllegalStateException("@BeforeClass did not capture installed APK identity"))
                .toString()
        Log.i(LOG_TAG, "APK_IDENTITY_JSON:$apkIdentity")
        println("APK_IDENTITY_JSON:$apkIdentity")
        // assetsVerified 已翻转为 true（T2 资产验证通过）
        assertTrue("$label assetsVerified should be true after T2 enablement", config.assetsVerified)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext
        val imageFile = File(targetContext.cacheDir, "exp_parity/real_image/frame_00000_f0.jpg")
        imageFile.parentFile?.mkdirs()
        testContext.assets.open(IMAGE_ASSET).use { input ->
            imageFile.outputStream().use { output -> input.copyTo(output) }
        }

        val imageSha256 = sha256(imageFile.readBytes())
        assertEquals("fixed image SHA-256", IMAGE_SHA256, imageSha256)

        val reference = JSONObject(
            testContext.assets.open(REFERENCE_ASSET).bufferedReader().use { it.readText() }
        )
        assertEquals("desktop reference status", "READY", reference.getString("status"))
        val referenceImage = reference.getJSONObject("image")
        assertEquals("desktop image SHA-256", imageSha256, referenceImage.getString("sha256"))
        assertEquals("desktop image width", 720, referenceImage.getInt("width"))
        assertEquals("desktop image height", 1280, referenceImage.getInt("height"))

        assertTrue("OpenCV local runtime must initialize", OpenCVLoader.initLocal())
        val geometry = checkNotNull(RoiCoordinateMapper.getImageGeometry(imageFile.absolutePath))
        assertEquals("raw image width", 720, geometry.rawWidth)
        assertEquals("raw image height", 1280, geometry.rawHeight)
        assertEquals("EXIF orientation", 1, geometry.exifOrientation)
        val raw = Imgcodecs.imread(
            imageFile.absolutePath,
            Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION,
        )
        assertTrue("fixed image must decode into a nonempty BGR Mat", !raw.empty())

        val upright = orientBgrMat(raw, geometry.exifOrientation)
        try {
            assertEquals("upright image width", geometry.width, upright.cols())
            assertEquals("upright image height", geometry.height, upright.rows())

            val preprocessed = NanoDetImagePreprocessor.preprocess(upright)
            val transform = preprocessed.transform
            assertEquals("resized width", 234, transform.resizedWidth)
            assertEquals("resized height", 416, transform.resizedHeight)
            val inputBytes = floatBytes(preprocessed.tensorNchw)
            val inputSha256 = sha256(inputBytes)

            val desktopPreprocessing = reference.getJSONObject("preprocessing")
            val comparisonPolicy = reference.getJSONObject("comparisonPolicy")
            assertEquals("input size", 416, desktopPreprocessing.getInt("inputSize"))
            assertEquals("color order", "BGR", desktopPreprocessing.getString("color"))
            assertEquals("resize method", "OpenCV INTER_LINEAR", desktopPreprocessing.getString("resize"))
            val expectedMean = floatArrayOf(103.53f, 116.28f, 123.675f)
            val expectedStd = floatArrayOf(57.375f, 57.12f, 58.395f)
            val referenceMean = desktopPreprocessing.getJSONArray("mean")
            val referenceStd = desktopPreprocessing.getJSONArray("std")
            expectedMean.indices.forEach { channel ->
                assertEquals("mean[$channel]", expectedMean[channel].toDouble(), referenceMean.getDouble(channel), 0.0)
                assertEquals("std[$channel]", expectedStd[channel].toDouble(), referenceStd.getDouble(channel), 0.0)
            }
            assertEquals(
                "letterbox and normalized padding",
                "top-left; padding normalized tensor values are zero",
                desktopPreprocessing.getString("letterbox"),
            )
            assertEquals(
                "input tensor tolerance matches its documented basis",
                INPUT_PIXEL_ROUNDING_TOLERANCE,
                comparisonPolicy.getDouble("inputTensorMaxAbsoluteDifferenceTolerance"),
                1e-12,
            )
            assertEquals("raw tensor tolerance", TENSOR_ABS_TOLERANCE, comparisonPolicy.getDouble("rawTensorMaxAbsoluteDifference"), 0.0)
            assertEquals("score tolerance", SCORE_ABS_TOLERANCE, comparisonPolicy.getDouble("scoreMaxAbsoluteDifference"), 0.0)
            assertEquals("box tolerance", BOX_COORDINATE_TOLERANCE_PX, comparisonPolicy.getDouble("boxCoordinateMaxAbsoluteDifferencePx"), 0.0)

            val desktopResult = reference.getJSONObject("results").getJSONObject(label)
            assertEquals("contract version", config.version, desktopResult.getString("contractVersion"))
            assertStringArrayEquals("model class order", classNames, desktopResult.getJSONArray("classNames"))
            assertEquals("param SHA-256", config.paramSha256.lowercase(), desktopResult.getString("paramSha256"))
            assertEquals("model SHA-256", config.modelSha256.lowercase(), desktopResult.getString("modelSha256"))

            val modelDirectory = File(targetContext.cacheDir, "exp_parity/real_image/$label/model")
                .apply { mkdirs() }
            val paramFile = File(modelDirectory, "nanodet.ncnn.param")
            val modelFile = File(modelDirectory, "nanodet.ncnn.bin")
            copyTargetAsset(targetContext, config.assetParamPath, paramFile)
            copyTargetAsset(targetContext, config.assetModelPath, modelFile)
            assertEquals("copied param SHA-256", config.paramSha256.lowercase(), sha256(paramFile.readBytes()))
            assertEquals("copied model SHA-256", config.modelSha256.lowercase(), sha256(modelFile.readBytes()))

            val productionOutput = try {
                runProductionNcnn(
                    paramFile.absolutePath,
                    modelFile.absolutePath,
                    preprocessed.tensorNchw,
                    config.outputWidth,
                )
            } catch (error: IllegalStateException) {
                val failed = baseReport(label, imageSha256, geometry, transform, inputSha256)
                    .put("status", "PRODUCTION_INFERENCE_FAILED")
                    .put("productionRuntime", JSONObject().put("library", "libnanodet_ncnn_runtime.so"))
                    .put("error", error.message)
                saveReport(targetContext, label, failed)
                throw AssertionError("$label production NanoDetNcnnNative inference failed", error)
            }
            assertEquals(
                "production JNI float[] element count",
                config.outputWidth * NanoDetModelContract.OUTPUT_HEIGHT,
                productionOutput.size,
            )
            assertTrue("production NCNN output must be finite", productionOutput.all(Float::isFinite))

            // This test-only bridge exposes the actual NCNN Mat fields; its outputWidth argument is only the shape guard.
            val testOnlyActualMat = IntArray(4)
            val testOnlyOutput = try {
                NcnnSmokeNative.runWithWidthAndMatInfo(
                    paramFile.absolutePath,
                    modelFile.absolutePath,
                    preprocessed.tensorNchw,
                    config.outputWidth,
                    testOnlyActualMat,
                )
            } catch (error: IllegalStateException) {
                val failed = baseReport(label, imageSha256, geometry, transform, inputSha256)
                    .put("status", "TEST_ONLY_MAT_PROBE_FAILED")
                    .put("testOnlyMatProbe", JSONObject().put("actualMatDimsWHElemsize", matJson(testOnlyActualMat)))
                    .put("error", error.message)
                saveReport(targetContext, label, failed)
                throw AssertionError(
                    "$label test-only NCNN Mat probe failed; actual Mat=${testOnlyActualMat.contentToString()}",
                    error,
                )
            }

            val actualMatJson = matJson(testOnlyActualMat)
            val expectedMat = desktopResult.getJSONObject("outputMat")
            assertEquals("test-only actual NCNN Mat.dims", expectedMat.getInt("dims"), testOnlyActualMat[0])
            assertEquals("test-only actual NCNN Mat.w", expectedMat.getInt("w"), testOnlyActualMat[1])
            assertEquals("test-only actual NCNN Mat.h", expectedMat.getInt("h"), testOnlyActualMat[2])
            assertEquals("test-only actual NCNN Mat.elemsize", expectedMat.getInt("elemsize"), testOnlyActualMat[3])
            assertEquals(
                "test-only NCNN output element count",
                config.outputWidth * NanoDetModelContract.OUTPUT_HEIGHT,
                testOnlyOutput.size,
            )
            assertTrue("test-only NCNN output must be finite", testOnlyOutput.all(Float::isFinite))

            val inputReference = desktopPreprocessing.getJSONObject("inputTensor")
            assertEquals("desktop input tensor hash metadata", inputReference.getString("sha256"), desktopResult.getString("inputTensorSha256"))
            val desktopInputBytes = testContext.assets.open("ncnn_parity/${inputReference.getString("path")}")
                .use { it.readBytes() }
            val inputComparison = compareFloatTensors(
                preprocessed.tensorNchw,
                desktopInputBytes,
                "desktop input tensor",
                maxAbsTolerance = INPUT_PIXEL_ROUNDING_TOLERANCE,
            )
            inputComparison.put("actualSha256", inputSha256)
                .put("expectedSha256", inputReference.getString("sha256"))
                .put("exactHashMatch", inputSha256 == inputReference.getString("sha256"))

            val productionVsMatProbeComparison = compareFloatTensors(
                testOnlyOutput,
                floatBytes(productionOutput),
                "test-only NCNN output vs production NanoDetNcnnNative output",
                maxAbsTolerance = TENSOR_ABS_TOLERANCE,
            )

            val outputReference = desktopResult.getJSONObject("outputTensor")
            val desktopOutputBytes = testContext.assets.open("ncnn_parity/${outputReference.getString("path")}")
                .use { it.readBytes() }
            assertEquals("desktop raw tensor SHA-256", outputReference.getString("sha256"), sha256(desktopOutputBytes))
            val tensorComparison = compareFloatTensors(
                productionOutput,
                desktopOutputBytes,
                "desktop raw output tensor",
                maxAbsTolerance = TENSOR_ABS_TOLERANCE,
            )
            tensorComparison.put("actualSha256", sha256(floatBytes(productionOutput)))
                .put("expectedSha256", outputReference.getString("sha256"))

            val candidates = NanoDetOutputDecoder.decode(productionOutput, transform, classNames)
            val candidateComparison = compareCandidates(candidates, desktopResult.getJSONArray("candidates"))
            val coordinatesWithinImage = candidates.all { candidate ->
                candidate.box.left in 0.0..geometry.width.toDouble() &&
                    candidate.box.right in 0.0..geometry.width.toDouble() &&
                    candidate.box.top in 0.0..geometry.height.toDouble() &&
                    candidate.box.bottom in 0.0..geometry.height.toDouble()
            }

            val passed = inputComparison.getBoolean("passed") &&
                productionVsMatProbeComparison.getBoolean("passed") &&
                tensorComparison.getBoolean("passed") &&
                candidateComparison.getBoolean("passed") && coordinatesWithinImage
            val report = baseReport(label, imageSha256, geometry, transform, inputSha256)
                .put("status", if (passed) "PASSED" else "FAILED")
                .put("assetsVerified", false)
                .put("runtime", JSONObject()
                    .put("abi", Build.SUPPORTED_ABIS.firstOrNull())
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("ncnnPackage", "ncnn-20260526-android-shared"))
                .put("productionRuntime", JSONObject()
                    .put("library", "libnanodet_ncnn_runtime.so")
                    .put("outputElementCount", productionOutput.size)
                    .put("actualMatMetadataReturned", false)
                    .put("nativeShapeGuard", "checks actual NCNN Mat dims/w/h/elemsize against create-time outputWidth, 3598 and float32; Mat metadata is not returned to Kotlin"))
                .put("testOnlyMatProbe", JSONObject()
                    .put("library", "libncnn_smoke.so")
                    .put("actualMatDimsWHElemsize", actualMatJson)
                    .put("outputElementCount", testOnlyOutput.size))
                .put("inputTensorComparison", inputComparison)
                .put("productionVsMatProbeTensorComparison", productionVsMatProbeComparison)
                .put("rawTensorComparison", tensorComparison)
                .put("decoderCandidateCount", candidates.size)
                .put("candidateComparison", candidateComparison)
                .put("coordinatesWithinImage", coordinatesWithinImage)
            saveReport(targetContext, label, report)

            assertTrue("$label input preprocessing parity failed: $inputComparison", inputComparison.getBoolean("passed"))
            assertTrue(
                "$label production runtime vs Mat probe tensor parity failed: $productionVsMatProbeComparison",
                productionVsMatProbeComparison.getBoolean("passed"),
            )
            assertTrue("$label raw tensor parity failed: $tensorComparison", tensorComparison.getBoolean("passed"))
            assertTrue("$label decoder/NMS/coordinates parity failed: $candidateComparison", candidateComparison.getBoolean("passed"))
            assertTrue("$label decoder returned coordinates outside the original image", coordinatesWithinImage)
        } finally {
            if (upright !== raw) upright.release()
            raw.release()
        }
    }

    private fun baseReport(
        label: String,
        imageSha256: String,
        geometry: RoiCoordinateMapper.PhotoGeometry,
        transform: NanoDetInputTransform,
        inputSha256: String,
    ) = JSONObject()
        .put("model", label)
        .put("apkIdentity", JSONObject(installedApkIdentityJson
            ?: failedInstalledApkIdentity(IllegalStateException("@BeforeClass did not capture installed APK identity")).toString()))
        .put("imageSha256", imageSha256)
        .put("imageWidth", geometry.width)
        .put("imageHeight", geometry.height)
        .put("exifOrientation", geometry.exifOrientation)
        .put("preprocessing", JSONObject()
            .put("resizedWidth", transform.resizedWidth)
            .put("resizedHeight", transform.resizedHeight)
            .put("scaleX", transform.scaleX)
            .put("scaleY", transform.scaleY)
            .put("color", "BGR")
            .put("resize", "OpenCV INTER_LINEAR")
            .put("inputTensorSha256", inputSha256))

    private fun runProductionNcnn(
        paramPath: String,
        modelPath: String,
        inputNchw: FloatArray,
        outputWidth: Int,
    ): FloatArray {
        val handle = NanoDetNcnnNative.create(paramPath, modelPath, outputWidth)
        assertTrue("production NCNN create returned an invalid handle", handle != 0L)
        return try {
            NanoDetNcnnNative.infer(handle, inputNchw)
        } finally {
            NanoDetNcnnNative.destroy(handle)
        }
    }

    private fun compareFloatTensors(
        actual: FloatArray,
        expectedBytes: ByteArray,
        label: String,
        maxAbsTolerance: Double,
    ): JSONObject {
        assertEquals("$label byte length", actual.size * Float.SIZE_BYTES, expectedBytes.size)
        val expected = FloatArray(actual.size)
        ByteBuffer.wrap(expectedBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(expected)
        var maxAbsDiff = 0.0
        var exceeding = 0
        for (i in actual.indices) {
            val diff = abs(actual[i].toDouble() - expected[i].toDouble())
            maxAbsDiff = maxOf(maxAbsDiff, diff)
            if (diff > maxAbsTolerance) exceeding++
        }
        return JSONObject()
            .put("comparedElements", actual.size)
            .put("maxAbsDiff", maxAbsDiff)
            .put("elementsExceedingTolerance", exceeding)
            .put("maxAbsTolerance", maxAbsTolerance)
            .put("passed", exceeding == 0)
    }

    private fun compareCandidates(actual: List<NanoDetCandidate>, expected: JSONArray): JSONObject {
        val expectedByKey = (0 until expected.length()).associate { index ->
            val candidate = expected.getJSONObject(index)
            (candidate.getInt("classIndex") to candidate.getInt("point")) to candidate
        }
        val actualByKey = actual.associateBy { it.classIndex to it.point }
        val keysMatch = actualByKey.keys == expectedByKey.keys
        var classNameMismatches = 0
        var maxScoreDiff = 0.0
        var maxBoxDiff = 0.0
        var numericMismatches = 0
        for ((key, reference) in expectedByKey) {
            val candidate = actualByKey[key] ?: continue
            if (candidate.className != reference.getString("className")) classNameMismatches++
            maxScoreDiff = maxOf(maxScoreDiff, abs(candidate.score.toDouble() - reference.getDouble("score")))
            val box = reference.getJSONArray("box")
            val diffs = listOf(
                abs(candidate.box.left - box.getDouble(0)),
                abs(candidate.box.top - box.getDouble(1)),
                abs(candidate.box.right - box.getDouble(2)),
                abs(candidate.box.bottom - box.getDouble(3)),
            )
            maxBoxDiff = maxOf(maxBoxDiff, diffs.maxOrNull() ?: 0.0)
            if (abs(candidate.score.toDouble() - reference.getDouble("score")) > SCORE_ABS_TOLERANCE ||
                diffs.any { it > BOX_COORDINATE_TOLERANCE_PX }) {
                numericMismatches++
            }
        }
        val passed = keysMatch && classNameMismatches == 0 && numericMismatches == 0
        return JSONObject()
            .put("actualCount", actual.size)
            .put("expectedCount", expected.length())
            .put("nmsSurvivorKeysMatch", keysMatch)
            .put("classNameMismatches", classNameMismatches)
            .put("numericMismatches", numericMismatches)
            .put("maxScoreAbsDiff", maxScoreDiff)
            .put("maxBoxCoordinateAbsDiffPx", maxBoxDiff)
            .put("scoreTolerance", SCORE_ABS_TOLERANCE)
            .put("boxCoordinateTolerancePx", BOX_COORDINATE_TOLERANCE_PX)
            .put("passed", passed)
    }

    private fun matJson(info: IntArray) = JSONArray()
        .put(info.getOrElse(0) { 0 })
        .put(info.getOrElse(1) { 0 })
        .put(info.getOrElse(2) { 0 })
        .put(info.getOrElse(3) { 0 })

    private fun copyTargetAsset(context: android.content.Context, asset: String, destination: File) {
        destination.parentFile?.mkdirs()
        context.assets.open(asset).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun saveReport(context: android.content.Context, label: String, report: JSONObject) {
        report.put("targetPackage", context.packageName)
        val reportDirectory = context.getExternalFilesDir("exp_parity") ?: File(context.cacheDir, "exp_parity")
        val reportFile = File(reportDirectory, "real_image_${label}_android_report.json")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report.toString(2))
        Log.i(LOG_TAG, report.toString())
        println("$LOG_TAG:${report}")
    }

    private fun floatBytes(values: FloatArray): ByteArray = ByteBuffer
        .allocate(values.size * Float.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply { values.forEach(::putFloat) }
        .array()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun assertStringArrayEquals(message: String, expected: Array<String>, actual: JSONArray) {
        assertEquals("$message length", expected.size, actual.length())
        expected.indices.forEach { assertEquals("$message[$it]", expected[it], actual.getString(it)) }
    }

    companion object {
        private const val LOG_TAG = "RealImageNcnnParity"
        private const val IMAGE_ASSET = "ncnn_parity/frame_00000_f0.jpg"
        private const val REFERENCE_ASSET = "ncnn_parity/real_image_desktop_reference.json"
        private const val IMAGE_SHA256 = "f9fcc75a2f4047db35fcd2b884e1bdf5bab3ce7addce8610c40a99469942ded6"
        private const val INPUT_PIXEL_ROUNDING_TOLERANCE = 1.0 / 57.12 + 1e-6
        private const val TENSOR_ABS_TOLERANCE = 1e-5
        private const val SCORE_ABS_TOLERANCE = 1e-5
        private const val BOX_COORDINATE_TOLERANCE_PX = 0.01
        @Volatile private var installedApkIdentityJson: String? = null

        @org.junit.BeforeClass
        @JvmStatic
        fun recordInstalledApkIdentity() {
            val identity = try {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val device = deviceIdentity()
                val contexts = JSONArray()
                    .put(installedApkIdentity("targetContext", instrumentation.targetContext))
                    .put(installedApkIdentity("instrumentationContext", instrumentation.context))
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("status", if (device.optString("status") == "FAILED" ||
                        (0 until contexts.length()).any { index -> contexts.getJSONObject(index).getString("status") != "OK" }) "FAILED" else "OK")
                    .put("runId", UUID.randomUUID().toString())
                    .put("capturedAtUtc", Instant.now().toString())
                    .put("testClass", RealImageNcnnParityInstrumentedTest::class.java.name)
                    .put("device", device)
                    .put("contexts", contexts)
            } catch (error: Exception) {
                failedInstalledApkIdentity(error)
            }.toString()
            installedApkIdentityJson = identity
            Log.i(LOG_TAG, "APK_IDENTITY_JSON:$identity")
            println("APK_IDENTITY_JSON:$identity")
        }

        private fun installedApkIdentity(contextName: String, context: android.content.Context): JSONObject {
            val errors = JSONArray()
            val result = JSONObject()
                .put("context", contextName)
                .put("status", "OK")
                .put("packageName", JSONObject.NULL)
                .put("versionCode", JSONObject.NULL)
                .put("versionName", JSONObject.NULL)
                .put("sourceDir", JSONObject.NULL)
                .put("splitSourceDirs", JSONArray())
                .put("installedApkFiles", JSONArray())
            try {
                result.put("packageName", context.packageName)
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                result.put("versionCode", packageInfo.versionCode)
                    .put("versionName", packageInfo.versionName ?: JSONObject.NULL)
            } catch (error: Exception) {
                errors.put(errorEntry("package/version", error))
            }
            try {
                val appInfo = context.applicationInfo
                result.put("sourceDir", appInfo.sourceDir ?: JSONObject.NULL)
                val apkFiles = result.getJSONArray("installedApkFiles")
                    .put(installedApkFile("base", appInfo.sourceDir))
                val splitDirs = JSONArray()
                appInfo.splitSourceDirs?.forEachIndexed { index, path ->
                    splitDirs.put(path ?: JSONObject.NULL)
                    apkFiles.put(installedApkFile("split[$index]", path))
                }
                result.put("splitSourceDirs", splitDirs)
            } catch (error: Exception) {
                errors.put(errorEntry("applicationInfo", error))
                result.getJSONArray("installedApkFiles").put(installedApkFile("base", null, error))
            }
            val apkFiles = result.getJSONArray("installedApkFiles")
            for (index in 0 until apkFiles.length()) {
                val apk = apkFiles.getJSONObject(index)
                if (apk.getString("status") != "OK") {
                    errors.put(JSONObject().put("stage", "${apk.optString("role")} APK")
                        .put("error", apk.optString("error", "APK identity collection failed")))
                }
            }
            if (errors.length() > 0) result.put("status", "FAILED").put("errors", errors)
            return result
        }

        private fun installedApkFile(role: String, path: String?, priorError: Exception? = null): JSONObject {
            val result = JSONObject()
                .put("role", role)
                .put("path", path ?: JSONObject.NULL)
            if (path.isNullOrBlank()) {
                return result
                    .put("status", "FAILED")
                    .put("byteCount", JSONObject.NULL)
                    .put("sha256", JSONObject.NULL)
                    .put("error", priorError?.let(::errorText) ?: "ApplicationInfo APK path is empty")
            }
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                var byteCount = 0L
                File(path).inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        byteCount += count
                    }
                }
                result
                    .put("status", "OK")
                    .put("byteCount", byteCount)
                    .put("sha256", digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
            } catch (error: Exception) {
                result
                    .put("status", "FAILED")
                    .put("byteCount", JSONObject.NULL)
                    .put("sha256", JSONObject.NULL)
                    .put("error", errorText(error))
            }
        }

        private fun deviceIdentity() = try {
            JSONObject()
                .put("status", "OK")
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("apiLevel", Build.VERSION.SDK_INT)
        } catch (error: Exception) {
            JSONObject()
                .put("status", "FAILED")
                .put("error", errorText(error))
        }

        private fun failedInstalledApkIdentity(error: Exception) = JSONObject()
            .put("schemaVersion", 1)
            .put("status", "FAILED")
            .put("runId", UUID.randomUUID().toString())
            .put("capturedAtUtc", Instant.now().toString())
            .put("testClass", RealImageNcnnParityInstrumentedTest::class.java.name)
            .put("device", deviceIdentity())
            .put("contexts", JSONArray()
                .put(failedContextIdentity("targetContext", error))
                .put(failedContextIdentity("instrumentationContext", error)))
            .put("error", errorText(error))

        private fun failedContextIdentity(contextName: String, error: Exception) = JSONObject()
            .put("context", contextName)
            .put("status", "FAILED")
            .put("packageName", JSONObject.NULL)
            .put("versionCode", JSONObject.NULL)
            .put("versionName", JSONObject.NULL)
            .put("sourceDir", JSONObject.NULL)
            .put("splitSourceDirs", JSONArray())
            .put("installedApkFiles", JSONArray().put(installedApkFile("base", null, error)))
            .put("errors", JSONArray().put(errorEntry("context", error)))

        private fun errorEntry(stage: String, error: Exception) = JSONObject()
            .put("stage", stage)
            .put("error", errorText(error))

        private fun errorText(error: Exception) = "${error.javaClass.name}: ${error.message ?: "(no message)"}"
    }
}
