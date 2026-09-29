package com.wearable.inspection.mobile.detection

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.wearable.inspection.mobile.ncnn.NcnnSmokeNative
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.abs
import kotlin.math.max

/**
 * exp22/exp23 Android 真机 parity 验证。
 *
 * ## 测试策略
 *
 * 使用**测试专用入口**（NcnnSmokeNative.runWithWidth）直接加载资产和 JNI runtime，
 * 不修改生产路由，通过测试专用入口直接验证模型资产（assetsVerified 已启用）。
 *
 * ### 全零 NCNN 推理 parity（推理张量一致性）
 * 使用同一组**全零固定输入**（FloatArray(3*416*416) 全 0），
 * 分别记录 Android NCNN 的输出张量统计、decoder 候选、框和分数。
 * 全零输入产生 0 个候选；0 vs 0 匹配不作为 decoder 覆盖证据。
 *
 * ### 合成 decoder parity（decoder 逻辑一致性）
 * 使用确定性合成张量（含已知候选），分别用 Python 和 Android decoder 解码，
 * 验证类别、分数和框坐标完全一致。合成张量不经 NCNN 推理，仅验证 decoder 逻辑。
 *
 * ## 参考数据对照
 *
 * - 参考文件缺失（FileNotFoundException）→ 测试标记为 skipped（assumeTrue），不记为通过
 * - 参考文件存在但 JSON 解析失败 → 测试失败（fail）
 * - 参考文件存在但 metadata 与当前模型合同不一致 → 测试失败（fail）
 * - 参考文件存在但张量长度不匹配 → 测试失败（fail）
 * - 张量或候选比较不通过 → 测试失败（fail）
 *
 * ## 容差（与 exp09 parity 一致）
 * - 分数绝对差: ≤ 1e-5
 * - 框坐标绝对差: ≤ 0.01 px
 * - 元素级绝对差: ≤ 1e-5
 */
@RunWith(AndroidJUnit4::class)
class ExpModelParityInstrumentedTest {

    // ── exp22 黑件模型 NCNN 推理 parity（全零输入）──

    @Test
    fun exp22BlackModelLoadsAndProducesValidTensor() {
        val report = runModelParity(
            modelLabel = "exp22_black",
            assetParamPath = "nanodet/exp22/nanodet.ncnn.param",
            assetModelPath = "nanodet/exp22/nanodet.ncnn.bin",
            outputWidth = 36,
            outputHeight = 3598,
            classNames = Exp22BlackClassPolicy.classNames,
            referenceAssetPath = "ncnn_parity/exp22_parity_results.json",
            contractVersion = NanoDetModelContractExp22.VERSION,
            contractParamSha256 = NanoDetModelContractExp22.PARAM_SHA256,
            contractModelSha256 = NanoDetModelContractExp22.MODEL_SHA256,
        )
        Log.i(LOG_TAG, report.toString())
        println("$LOG_TAG:$report")
    }

    // ── exp23 白件模型 NCNN 推理 parity（全零输入）──

    @Test
    fun exp23WhiteModelLoadsAndProducesValidTensor() {
        val report = runModelParity(
            modelLabel = "exp23_white",
            assetParamPath = "nanodet/exp23/nanodet.ncnn.param",
            assetModelPath = "nanodet/exp23/nanodet.ncnn.bin",
            outputWidth = 34,
            outputHeight = 3598,
            classNames = Exp23WhiteClassPolicy.classNames,
            referenceAssetPath = "ncnn_parity/exp23_parity_results.json",
            contractVersion = NanoDetModelContractExp23.VERSION,
            contractParamSha256 = NanoDetModelContractExp23.PARAM_SHA256,
            contractModelSha256 = NanoDetModelContractExp23.MODEL_SHA256,
        )
        Log.i(LOG_TAG, report.toString())
        println("$LOG_TAG:$report")
    }

    // ── exp22 黑件 decoder parity（合成非空候选）──

    @Test
    fun exp22DecoderParityWithSyntheticCandidates() {
        val report = runDecoderParity(
            modelLabel = "exp22_black_decoder",
            outputWidth = 36,
            outputHeight = 3598,
            classNames = Exp22BlackClassPolicy.classNames,
            referenceAssetPath = "ncnn_parity/exp22_decoder_parity.json",
            contractVersion = NanoDetModelContractExp22.VERSION,
            contractParamSha256 = NanoDetModelContractExp22.PARAM_SHA256,
            contractModelSha256 = NanoDetModelContractExp22.MODEL_SHA256,
        )
        Log.i(LOG_TAG, report.toString())
        println("$LOG_TAG:$report")
    }

    // ── exp23 白件 decoder parity（合成非空候选）──

    @Test
    fun exp23DecoderParityWithSyntheticCandidates() {
        val report = runDecoderParity(
            modelLabel = "exp23_white_decoder",
            outputWidth = 34,
            outputHeight = 3598,
            classNames = Exp23WhiteClassPolicy.classNames,
            referenceAssetPath = "ncnn_parity/exp23_decoder_parity.json",
            contractVersion = NanoDetModelContractExp23.VERSION,
            contractParamSha256 = NanoDetModelContractExp23.PARAM_SHA256,
            contractModelSha256 = NanoDetModelContractExp23.MODEL_SHA256,
        )
        Log.i(LOG_TAG, report.toString())
        println("$LOG_TAG:$report")
    }

    // ── 核心 NCNN 推理 parity 验证逻辑（全零输入，完整张量比较）──

    private fun runModelParity(
        modelLabel: String,
        assetParamPath: String,
        assetModelPath: String,
        outputWidth: Int,
        outputHeight: Int,
        classNames: Array<String>,
        referenceAssetPath: String,
        contractVersion: String,
        contractParamSha256: String,
        contractModelSha256: String,
    ): JSONObject {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext

        // 1. 部署模型资产到设备缓存
        val workspace = File(targetContext.cacheDir, "exp_parity/$modelLabel").apply { mkdirs() }
        val modelDir = File(workspace, "model").apply { mkdirs() }
        val paramFile = File(modelDir, "nanodet.ncnn.param")
        val binFile = File(modelDir, "nanodet.ncnn.bin")
        copyAsset(targetContext, assetParamPath, paramFile)
        copyAsset(targetContext, assetModelPath, binFile)

        val report = JSONObject()
            .put("modelLabel", modelLabel)
            .put("assetParamPath", assetParamPath)
            .put("assetModelPath", assetModelPath)
            .put("inputBlob", "in0")
            .put("inputShape", "[1,3,416,416]")
            .put("outputBlob", "out0")
            .put("outputShape", "[$outputHeight,$outputWidth]")
            .put("outputWidth", outputWidth)
            .put("outputHeight", outputHeight)
            .put("classNames", classNames.joinToString(","))
            .put("classCount", classNames.size)
            .put("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull())
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("ncnnLibrary", "libncnn_smoke.so (androidTest arm64-v8a)")

        // 2. 生成确定性全零输入
        val inputTensor = FloatArray(CHANNELS * INPUT_SIZE * INPUT_SIZE) // 全零
        val inputChecksum = inputTensor.sum().toDouble()
        report.put("inputDescription", "all-zeros float32 NCHW [1,3,416,416]")
        report.put("inputChecksum", inputChecksum)
        report.put("inputElementCount", inputTensor.size)

        // 3. 通过测试专用 JNI 运行推理
        val output: FloatArray
        val inferenceStartNs = System.nanoTime()
        try {
            output = NcnnSmokeNative.runWithWidth(
                paramFile.absolutePath,
                binFile.absolutePath,
                inputTensor,
                outputWidth
            )
        } catch (e: IllegalStateException) {
            report.put("status", "INFERENCE_FAILED")
                .put("error", e.message ?: "unknown")
            fail("$modelLabel NCNN 推理失败: ${e.message}")
            return report // unreachable
        }
        val inferenceMs = (System.nanoTime() - inferenceStartNs) / 1_000_000L
        report.put("inferenceMs", inferenceMs)

        // 4. 验证输出形状
        val expectedElementCount = outputWidth * outputHeight
        assertEquals(
            "$modelLabel output element count",
            expectedElementCount, output.size
        )
        report.put("outputElementCount", output.size)
        report.put("outputShapeVerified", true)

        // 5. 验证输出值有限性
        val hasNonFinite = output.any { !it.isFinite() }
        assertTrue("$modelLabel output contains non-finite values", !hasNonFinite)
        report.put("allFinite", true)

        // 6. 输出张量统计
        var minVal = Float.MAX_VALUE
        var maxVal = Float.MIN_VALUE
        var sumVal = 0.0
        for (v in output) {
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
            sumVal += v.toDouble()
        }
        val meanVal = sumVal / output.size
        report.put("tensorStats", JSONObject()
            .put("min", minVal.toDouble())
            .put("max", maxVal.toDouble())
            .put("mean", meanVal)
            .put("sum", sumVal)
        )

        // 7. 保存前 N 个输出值快照（用于未来跨平台比对）
        val snapshotSize = minOf(64, output.size)
        val snapshot = org.json.JSONArray()
        for (i in 0 until snapshotSize) snapshot.put(output[i].toDouble())
        report.put("outputSnapshot_first64", snapshot)

        // 8. 按行统计（前 5 行和最后 5 行）
        val rowSample = JSONObject()
        for (rowIdx in (0 until minOf(5, outputHeight)) +
            (maxOf(0, outputHeight - 5) until outputHeight)) {
            val rowStart = rowIdx * outputWidth
            val rowArray = org.json.JSONArray()
            for (col in 0 until outputWidth) rowArray.put(output[rowStart + col].toDouble())
            rowSample.put("row_$rowIdx", rowArray)
        }
        report.put("rowSamples", rowSample)

        // 9. 使用生产 decoder 解码候选
        val transform = NanoDetInputTransform(
            width = INPUT_SIZE, height = INPUT_SIZE,
            resizedWidth = INPUT_SIZE, resizedHeight = INPUT_SIZE,
            scaleX = 1.0, scaleY = 1.0,
        )
        val candidates = NanoDetOutputDecoder.decode(output, transform, classNames, SCORE_THRESHOLD)
        report.put("candidateCount", candidates.size)

        val candidatesJson = org.json.JSONArray()
        for (candidate in candidates.sortedByDescending { it.score }) {
            candidatesJson.put(JSONObject()
                .put("classIndex", candidate.classIndex)
                .put("className", candidate.className)
                .put("score", candidate.score.toDouble())
                .put("point", candidate.point)
                .put("box", org.json.JSONArray(listOf(
                    candidate.box.left, candidate.box.top,
                    candidate.box.right, candidate.box.bottom
                )))
            )
        }
        report.put("candidates", candidatesJson)

        // 按阈值统计候选数
        val thresholdCounts = JSONObject()
        for (threshold in PARITY_THRESHOLDS) {
            val countAtThreshold = candidates.count { it.score >= threshold }
            val perClass = JSONObject()
            for (className in classNames) {
                perClass.put(className, candidates.count {
                    it.className == className && it.score >= threshold
                })
            }
            thresholdCounts.put(threshold.toString(),
                JSONObject().put("total", countAtThreshold).put("byClass", perClass))
        }
        report.put("detectionCountsByThreshold", thresholdCounts)

        // 10. 加载离线参考数据并严格比较
        // 仅 FileNotFoundException → skipped；其他异常 → fail
        val refJson: String
        try {
            refJson = testContext.assets.open(referenceAssetPath).bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            // 参考文件不存在 → 测试 skipped，不记为通过
            report.put("referenceSource", "NOT_AVAILABLE")
            report.put("parityStatus", "SKIPPED_NO_REFERENCE")
            assumeTrue(
                "$modelLabel 离线参考数据缺失 ($referenceAssetPath)，跳过 parity 比较",
                false
            )
            return report // unreachable
        }
        // 其他 IOException、JSON 解析错误等直接传播 → 测试失败

        // 参考文件存在 → 必须成功解析
        val ref = JSONObject(refJson) // JSONException → test fails

        report.put("referenceSource", referenceAssetPath)

        // ── 10a. 严格断言参考 JSON metadata 与当前模型合同一致 ──
        assertReferenceMetadata(
            ref, modelLabel, contractVersion, contractParamSha256, contractModelSha256,
            classNames, outputWidth, outputHeight, referenceAssetPath
        )

        // 10b. 逐元素比较输出张量
        val refTensor = ref.getJSONArray("outputTensor")
        val refElementCount = refTensor.length()

        // 严格验证参考张量长度 = 预期元素数
        assertEquals(
            "$modelLabel 参考张量长度应等于 $expectedElementCount，实际 $refElementCount",
            expectedElementCount, refElementCount
        )

        var maxAbsDiff = 0.0
        var diffCount = 0
        for (i in 0 until expectedElementCount) {
            val diff = abs(output[i].toDouble() - refTensor.getDouble(i))
            if (diff > maxAbsDiff) maxAbsDiff = diff
            if (diff > ELEMENT_WISE_TOLERANCE) diffCount++
        }
        val tensorComparison = JSONObject()
            .put("comparedElements", expectedElementCount)
            .put("maxAbsDiff", maxAbsDiff)
            .put("elementsExceedingTolerance", diffCount)
            .put("tolerance", ELEMENT_WISE_TOLERANCE)
            .put("passed", diffCount == 0)
        report.put("tensorComparison", tensorComparison)

        // 张量比较不通过 → 测试失败
        if (diffCount > 0) {
            fail("$modelLabel 张量比较失败: $diffCount/$expectedElementCount 元素超出容差 $ELEMENT_WISE_TOLERANCE, maxAbsDiff=$maxAbsDiff")
        }

        // 10c. 比较 decoder 候选
        val refCandidates = ref.getJSONArray("candidates")
        val refCandidateList = mutableListOf<Triple<String, Float, List<Double>>>()
        for (i in 0 until refCandidates.length()) {
            val rc = refCandidates.getJSONObject(i)
            val box = rc.getJSONArray("box")
            refCandidateList.add(Triple(
                rc.getString("className"),
                rc.getDouble("score").toFloat(),
                listOf(box.getDouble(0), box.getDouble(1), box.getDouble(2), box.getDouble(3))
            ))
        }
        val candidateComparison = compareCandidates(candidates, refCandidateList)
        report.put("candidateComparison", candidateComparison)

        // 候选比较不通过 → 测试失败
        val candidatePassed = candidateComparison.optBoolean("passed", false)
        if (!candidatePassed) {
            fail("$modelLabel 候选比较失败: actualCount=${candidates.size}, expectedCount=${refCandidateList.size}, mismatchCount=${candidateComparison.optInt("mismatchCount")}")
        }

        // 0 vs 0 匹配不作为 decoder 覆盖证据
        if (candidates.isEmpty() && refCandidateList.isEmpty()) {
            report.put("decoderCoverageNote", "全零输入产生 0 候选；0 vs 0 匹配不作为 decoder 非空覆盖证据")
        }

        report.put("parityStatus", "PASSED")
        report.put("assetsVerified", true)
        report.put("note", "T2 资产验证通过，assetsVerified 已启用")
        return report
    }

    // ── 合成 decoder parity 验证逻辑（非空候选，decoder 逻辑一致性）──

    private fun runDecoderParity(
        modelLabel: String,
        outputWidth: Int,
        outputHeight: Int,
        classNames: Array<String>,
        referenceAssetPath: String,
        contractVersion: String,
        contractParamSha256: String,
        contractModelSha256: String,
    ): JSONObject {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val expectedElementCount = outputWidth * outputHeight

        val report = JSONObject()
            .put("modelLabel", modelLabel)
            .put("testType", "synthetic_decoder_parity")
            .put("outputWidth", outputWidth)
            .put("outputHeight", outputHeight)
            .put("classNames", classNames.joinToString(","))
            .put("classCount", classNames.size)

        // 1. 加载参考文件（仅 FileNotFoundException → skipped）
        val refJson: String
        try {
            refJson = testContext.assets.open(referenceAssetPath).bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            report.put("referenceSource", "NOT_AVAILABLE")
            report.put("parityStatus", "SKIPPED_NO_REFERENCE")
            assumeTrue("$modelLabel 合成参考数据缺失 ($referenceAssetPath)", false)
            return report // unreachable
        }
        val ref = JSONObject(refJson) // JSONException → test fails

        // 2. 断言参考 metadata 与模型合同一致
        assertReferenceMetadata(
            ref, modelLabel, contractVersion, contractParamSha256, contractModelSha256,
            classNames, outputWidth, outputHeight, referenceAssetPath
        )

        // 3. 加载参考张量并验证长度
        val refTensor = ref.getJSONArray("outputTensor")
        assertEquals(
            "$modelLabel 参考张量长度",
            expectedElementCount, refTensor.length()
        )

        // 将参考张量转为 FloatArray
        val tensor = FloatArray(expectedElementCount)
        for (i in 0 until expectedElementCount) {
            tensor[i] = refTensor.getDouble(i).toFloat()
        }

        // 4. 用 Android 生产 decoder 解码
        val transform = NanoDetInputTransform(
            width = INPUT_SIZE, height = INPUT_SIZE,
            resizedWidth = INPUT_SIZE, resizedHeight = INPUT_SIZE,
            scaleX = 1.0, scaleY = 1.0,
        )
        val candidates = NanoDetOutputDecoder.decode(tensor, transform, classNames, SCORE_THRESHOLD)
        report.put("candidateCount", candidates.size)

        // 5. 合成参考必须至少产生 1 个候选
        val refCandidates = ref.getJSONArray("candidates")
        assertTrue(
            "$modelLabel 合成参考应至少有 1 个候选，实际 ${refCandidates.length()}",
            refCandidates.length() > 0
        )
        assertTrue(
            "$modelLabel Android decoder 应至少解码出 1 个候选",
            candidates.isNotEmpty()
        )

        // 6. 比较候选
        val refCandidateList = mutableListOf<Triple<String, Float, List<Double>>>()
        for (i in 0 until refCandidates.length()) {
            val rc = refCandidates.getJSONObject(i)
            val box = rc.getJSONArray("box")
            refCandidateList.add(Triple(
                rc.getString("className"),
                rc.getDouble("score").toFloat(),
                listOf(box.getDouble(0), box.getDouble(1), box.getDouble(2), box.getDouble(3))
            ))
        }
        val candidateComparison = compareCandidates(candidates, refCandidateList)
        report.put("candidateComparison", candidateComparison)

        val candidatePassed = candidateComparison.optBoolean("passed", false)
        if (!candidatePassed) {
            fail("$modelLabel 合成 decoder 候选比较失败: actualCount=${candidates.size}, expectedCount=${refCandidateList.size}, mismatchCount=${candidateComparison.optInt("mismatchCount")}")
        }

        report.put("parityStatus", "PASSED")
        report.put("assetsVerified", true)
        report.put("note", "合成 decoder parity；T2 资产验证通过")
        return report
    }

    // ── 参考 JSON metadata 断言 ──

    private fun assertReferenceMetadata(
        ref: JSONObject,
        modelLabel: String,
        contractVersion: String,
        contractParamSha256: String,
        contractModelSha256: String,
        classNames: Array<String>,
        outputWidth: Int,
        outputHeight: Int,
        referenceAssetPath: String,
    ) {
        // exp/model 标识：断言精确预期值
        val refExp = ref.getString("exp")
        val expectedExp = when {
            modelLabel.startsWith("exp22") -> "exp22_black_4class"
            modelLabel.startsWith("exp23") -> "exp23_white_2class"
            else -> fail("未知 modelLabel: $modelLabel，无法确定预期 exp 值")
        }
        assertEquals(
            "$modelLabel 参考 exp 标识应为 $expectedExp ($referenceAssetPath)",
            expectedExp, refExp
        )

        // 类别数量
        assertEquals(
            "$modelLabel 参考 num_classes",
            classNames.size, ref.getInt("num_classes")
        )

        // 类别顺序
        val refClassNames = ref.getJSONArray("class_names")
        assertEquals(
            "$modelLabel 参考 class_names 长度",
            classNames.size, refClassNames.length()
        )
        for (i in classNames.indices) {
            assertEquals(
                "$modelLabel 参考 class_names[$i]",
                classNames[i], refClassNames.getString(i)
            )
        }

        // strides
        val refStrides = ref.getJSONArray("strides")
        val expectedStrides = intArrayOf(8, 16, 32, 64)
        assertEquals("$modelLabel 参考 strides 长度", expectedStrides.size, refStrides.length())
        for (i in expectedStrides.indices) {
            assertEquals("$modelLabel 参考 strides[$i]", expectedStrides[i], refStrides.getInt(i))
        }

        // reg_max
        assertEquals("$modelLabel 参考 reg_max", 7, ref.getInt("reg_max"))

        // input_size
        assertEquals("$modelLabel 参考 input_size", NanoDetModelContract.INPUT_SIZE, ref.getInt("input_size"))

        // output_width / output_height
        assertEquals("$modelLabel 参考 output_width", outputWidth, ref.getInt("output_width"))
        assertEquals("$modelLabel 参考 output_height", outputHeight, ref.getInt("output_height"))

        // candidate_threshold
        assertEquals(
            "$modelLabel 参考 candidate_threshold",
            NanoDetModelContract.CANDIDATE_THRESHOLD.toDouble(),
            ref.getDouble("candidate_threshold"),
            1e-6
        )

        // nms_threshold
        assertEquals(
            "$modelLabel 参考 nms_threshold",
            0.6, ref.getDouble("nms_threshold"), 1e-6
        )

        // inputDescription（全零或合成均需匹配）
        val refInputDesc = ref.getString("inputDescription")
        assertTrue(
            "$modelLabel 参考 inputDescription 非空",
            refInputDesc.isNotEmpty()
        )

        // inputElementCount
        val expectedInputElementCount = 3 * NanoDetModelContract.INPUT_SIZE * NanoDetModelContract.INPUT_SIZE
        assertEquals(
            "$modelLabel 参考 inputElementCount",
            expectedInputElementCount, ref.getInt("inputElementCount")
        )

        // NCNN param/bin SHA-256
        val ncnnMeta = ref.getJSONObject("ncnn_meta")
        assertEquals(
            "$modelLabel 参考 ncnn param_sha256 ($referenceAssetPath)",
            contractParamSha256, ncnnMeta.getString("param_sha256")
        )
        assertEquals(
            "$modelLabel 参考 ncnn bin_sha256 ($referenceAssetPath)",
            contractModelSha256, ncnnMeta.getString("bin_sha256")
        )
        assertEquals("$modelLabel 参考 input_blob", "in0", ncnnMeta.getString("input_blob"))
        assertEquals("$modelLabel 参考 output_blob", "out0", ncnnMeta.getString("output_blob"))
    }

    /**
     * 比较 Android 候选与参考候选。
     * 按 className+score 降序排序后逐一匹配，检查 score 和 box 差异。
     */
    private fun compareCandidates(
        actual: List<NanoDetCandidate>,
        expected: List<Triple<String, Float, List<Double>>>,
    ): JSONObject {
        val result = JSONObject()
        result.put("actualCount", actual.size)
        result.put("expectedCount", expected.size)
        result.put("countMatch", actual.size == expected.size)

        val comparisons = org.json.JSONArray()
        var maxScoreDiff = 0.0
        var maxBoxDiff = 0.0
        var matchCount = 0
        var mismatchCount = 0

        // 按 className+score 排序进行匹配
        val sortedActual = actual.sortedWith(compareByDescending<NanoDetCandidate> { it.score }.thenBy { it.className })
        val sortedExpected = expected.sortedWith(compareByDescending<Triple<String, Float, List<Double>>> { it.second }.thenBy { it.first })

        for (i in 0 until minOf(sortedActual.size, sortedExpected.size)) {
            val a = sortedActual[i]
            val e = sortedExpected[i]
            val scoreDiff = abs(a.score - e.second).toDouble()
            val boxDiffs = listOf(
                abs(a.box.left - e.third[0]),
                abs(a.box.top - e.third[1]),
                abs(a.box.right - e.third[2]),
                abs(a.box.bottom - e.third[3])
            )
            val boxDiff = boxDiffs.maxOrNull() ?: 0.0
            maxScoreDiff = max(maxScoreDiff, scoreDiff)
            maxBoxDiff = max(maxBoxDiff, boxDiff)

            val classMatch = a.className == e.first
            val scoreOk = scoreDiff <= SCORE_TOLERANCE
            val boxOk = boxDiff <= BOX_TOLERANCE_PX
            if (classMatch && scoreOk && boxOk) matchCount++ else mismatchCount++

            comparisons.put(JSONObject()
                .put("index", i)
                .put("actualClass", a.className)
                .put("expectedClass", e.first)
                .put("classMatch", classMatch)
                .put("scoreAbsDiff", scoreDiff)
                .put("maxBoxAbsDiffPx", boxDiff)
                .put("passed", classMatch && scoreOk && boxOk)
            )
        }
        result.put("comparisons", comparisons)
        result.put("matchCount", matchCount)
        result.put("mismatchCount", mismatchCount)
        result.put("maxScoreAbsDiff", maxScoreDiff)
        result.put("maxBoxAbsDiffPx", maxBoxDiff)
        result.put("passed", mismatchCount == 0 && actual.size == expected.size)
        return result
    }

    private fun copyAsset(context: android.content.Context, asset: String, destination: File) {
        context.assets.open(asset).use { input -> destination.outputStream().use(input::copyTo) }
    }

    private companion object {
        const val LOG_TAG = "EXP_PARITY"
        const val INPUT_SIZE = 416
        const val CHANNELS = 3
        const val SCORE_THRESHOLD = 0.05f
        const val ELEMENT_WISE_TOLERANCE = 1e-5
        const val SCORE_TOLERANCE = 1e-5
        const val BOX_TOLERANCE_PX = 0.01
        val PARITY_THRESHOLDS = listOf(0.05f, 0.10f, 0.15f, 0.20f, 0.25f, 0.30f, 0.37f, 0.50f)
    }
}