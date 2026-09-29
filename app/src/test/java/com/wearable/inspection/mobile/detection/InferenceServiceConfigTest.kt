package com.wearable.inspection.mobile.detection

import com.wearable.inspection.mobile.data.entity.RoiTargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1 推理服务配置回归测试
 *
 * 覆盖：路由器 fail-closed、资产路径隔离、MODEL_UNAVAILABLE 不携带 exp09 元数据。
 * 不依赖 native runtime 或 OpenCV，纯 JVM 逻辑测试。
 */
class InferenceServiceConfigTest {

    // ── 路由器正向路径：资产已验证时返回 Success ──

    @Test
    fun `White prefix routes to EXP23 with verified assets`() {
        val route = PartModelRouter.resolveModelConfig("White_BOLT_001")
        assertTrue("exp23 assetsVerified=true 应返回 Success",
            route is ModelRouteResult.Success)
        val success = route as ModelRouteResult.Success
        assertEquals(ModelRoute.EXP23_WHITE, success.route)
        assertEquals(NanoDetModelContractExp22.CONFIG.version, NanoDetModelContractExp22.CONFIG.version) // sanity
        assertEquals(NanoDetModelContractExp23.CONFIG.version, success.config.version)
        assertEquals(Exp23WhiteClassPolicy, success.classPolicy)
    }

    @Test
    fun `Black prefix routes to EXP22 with verified assets`() {
        val route = PartModelRouter.resolveModelConfig("Black_NUT_002")
        assertTrue("exp22 assetsVerified=true 应返回 Success",
            route is ModelRouteResult.Success)
        val success = route as ModelRouteResult.Success
        assertEquals(ModelRoute.EXP22_BLACK, success.route)
        assertEquals(NanoDetModelContractExp22.CONFIG.version, success.config.version)
        assertEquals(Exp22BlackClassPolicy, success.classPolicy)
    }

    @Test
    fun `old ID without prefix returns Success for exp09`() {
        val route = PartModelRouter.resolveModelConfig("part_001")
        assertTrue("旧 ID 应路由到 exp09",
            route is ModelRouteResult.Success)
        val success = route as ModelRouteResult.Success
        assertEquals(ModelRoute.EXP09, success.route)
        assertEquals(NanoDetModelContract.CONFIG, success.config)
        assertEquals(Exp09ClassPolicy, success.classPolicy)
    }

    // ── 资产路径隔离：各模型使用独立目录 ──

    @Test
    fun `exp09 uses nanodet-ncnn model directory`() {
        val config = NanoDetModelContract.CONFIG
        assertEquals("nanodet-ncnn-exp09-4class", config.modelDirName)
        assertEquals("nanodet/nanodet.ncnn.param", config.assetParamPath)
        assertEquals("nanodet/nanodet.ncnn.bin", config.assetModelPath)
    }

    @Test
    fun `exp22 uses exp22 model directory`() {
        val config = NanoDetModelContractExp22.CONFIG
        assertEquals(NanoDetModelContractExp22.VERSION, config.modelDirName)
        assertEquals("nanodet/exp22/nanodet.ncnn.param", config.assetParamPath)
        assertEquals("nanodet/exp22/nanodet.ncnn.bin", config.assetModelPath)
    }

    @Test
    fun `exp23 uses exp23 model directory`() {
        val config = NanoDetModelContractExp23.CONFIG
        assertEquals(NanoDetModelContractExp23.VERSION, config.modelDirName)
        assertEquals("nanodet/exp23/nanodet.ncnn.param", config.assetParamPath)
        assertEquals("nanodet/exp23/nanodet.ncnn.bin", config.assetModelPath)
    }

    @Test
    fun `three models use three distinct model directories`() {
        val dirs = setOf(
            NanoDetModelContract.CONFIG.modelDirName,
            NanoDetModelContractExp22.CONFIG.modelDirName,
            NanoDetModelContractExp23.CONFIG.modelDirName,
        )
        assertEquals("三个模型应使用三个不同目录", 3, dirs.size)
    }

    // ── MODEL_UNAVAILABLE 不携带 exp09 元数据 ──

    @Test
    fun `White_ route with verified assets returns Success with config`() {
        val route = PartModelRouter.resolveModelConfig("White_X")
        assertTrue("exp23 已验证应返回 Success", route is ModelRouteResult.Success)
        val success = route as ModelRouteResult.Success
        assertNotNull("Success 应包含 config", success.config)
        assertNotNull("Success 应包含 classPolicy", success.classPolicy)
    }

    @Test
    fun `exp23 config assetsVerified is true`() {
        val config = NanoDetModelContractExp23.CONFIG
        assertEquals(true, config.assetsVerified)
    }

    @Test
    fun `exp22 config assetsVerified is true`() {
        val config = NanoDetModelContractExp22.CONFIG
        assertEquals(true, config.assetsVerified)
    }

    @Test
    fun `exp09 config assetsVerified is true`() {
        val config = NanoDetModelContract.CONFIG
        assertEquals(true, config.assetsVerified)
    }

    // ── 类别策略独立 ──

    @Test
    fun `exp09 class policy has 4 classes`() {
        assertEquals(4, Exp09ClassPolicy.classNames.size)
        assertEquals("nut", Exp09ClassPolicy.classNames[0])
        assertEquals("thread", Exp09ClassPolicy.classNames[1])
        assertEquals("bolt", Exp09ClassPolicy.classNames[2])
        assertEquals("nutsert", Exp09ClassPolicy.classNames[3])
    }

    @Test
    fun `exp22 class policy has 4 classes with black prefix`() {
        assertEquals(4, Exp22BlackClassPolicy.classNames.size)
        assertEquals("Black Thread", Exp22BlackClassPolicy.classNames[0])
        assertEquals("Black Nutsert", Exp22BlackClassPolicy.classNames[1])
        assertEquals("Black Nut", Exp22BlackClassPolicy.classNames[2])
        assertEquals("Black Bolt", Exp22BlackClassPolicy.classNames[3])
    }

    @Test
    fun `exp23 class policy has 2 classes with white prefix`() {
        assertEquals(2, Exp23WhiteClassPolicy.classNames.size)
        assertEquals("White Nut", Exp23WhiteClassPolicy.classNames[0])
        assertEquals("White Thread", Exp23WhiteClassPolicy.classNames[1])
    }

    // ── decoder 输出宽度与类别数一致 ──

    @Test
    fun `exp09 output width equals classCount plus 32`() {
        val expected = NanoDetModelContract.CONFIG.classCount + 4 * (7 + 1)
        assertEquals(36, expected)
        assertEquals(expected, NanoDetModelContract.CONFIG.outputWidth)
    }

    @Test
    fun `exp22 output width equals classCount plus 32`() {
        val expected = NanoDetModelContractExp22.CONFIG.classCount + 4 * (7 + 1)
        assertEquals(36, expected)
        assertEquals(expected, NanoDetModelContractExp22.CONFIG.outputWidth)
    }

    @Test
    fun `exp23 output width equals classCount plus 32`() {
        val expected = NanoDetModelContractExp23.CONFIG.classCount + 4 * (7 + 1)
        assertEquals(34, expected)
        assertEquals(expected, NanoDetModelContractExp23.CONFIG.outputWidth)
    }

    // ── 路由前缀不区分大小写验证 ──

    @Test
    fun `lowercase white does not match White prefix`() {
        val route = PartModelRouter.resolveModelConfig("white_BOLT_001")
        // 小写 white 不匹配 White_ 前缀，应路由到 exp09
        assertTrue("小写 white 应路由到 exp09",
            route is ModelRouteResult.Success)
        assertEquals(ModelRoute.EXP09, (route as ModelRouteResult.Success).route)
    }

    @Test
    fun `lowercase black does not match Black prefix`() {
        val route = PartModelRouter.resolveModelConfig("black_NUT_002")
        assertTrue("小写 black 应路由到 exp09",
            route is ModelRouteResult.Success)
        assertEquals(ModelRoute.EXP09, (route as ModelRouteResult.Success).route)
    }

    // ── MODEL_TARGET_UNSUPPORTED vs FEATURE_UNSUPPORTED 区分 ──

    @Test
    fun `exp23 BOLT returns MODEL_TARGET_UNSUPPORTED not FEATURE_UNSUPPORTED`() {
        val decision = NanoDetDecisionPolicy.decideWithPolicy(
            RoiTargetType.BOLT,
            listOf(NanoDetCandidate(0, "White Nut", 0.95f, NanoDetBox(0.0, 0.0, 20.0, 20.0), 0)),
            0.50f,
            Exp23WhiteClassPolicy
        )
        assertEquals(NanoDetInferenceStatus.MODEL_TARGET_UNSUPPORTED, decision.status)
        // 确保不是 FEATURE_UNSUPPORTED
        assertTrue("应为 MODEL_TARGET_UNSUPPORTED 而非 FEATURE_UNSUPPORTED",
            decision.status != NanoDetInferenceStatus.FEATURE_UNSUPPORTED)
    }

    // ── 路由派生：verified 路径产生正确的 activeConfig ──

    @Test
    fun `White partId route derivation produces exp23 activeConfig`() {
        val route = PartModelRouter.resolveModelConfig("White_BOLT_001")
        assertTrue("exp23 已验证应返回 Success", route is ModelRouteResult.Success)
        val activeConfig: ModelConfig? = when (route) {
            is ModelRouteResult.Success -> route.config
            is ModelRouteResult.ModelUnavailable -> null
        }
        assertNotNull("activeConfig 应非 null（Success 路径）", activeConfig)
        assertEquals(NanoDetModelContractExp23.VERSION, activeConfig!!.version)
        assertEquals(NanoDetModelContractExp23.PARAM_SHA256, activeConfig.paramSha256)
        assertEquals(NanoDetModelContractExp23.MODEL_SHA256, activeConfig.modelSha256)
        assertNotEquals("不应为 exp09 版本", NanoDetModelContract.VERSION, activeConfig.version)
    }

    @Test
    fun `Black partId route derivation produces exp22 activeConfig`() {
        val route = PartModelRouter.resolveModelConfig("Black_NUT_002")
        assertTrue("exp22 已验证应返回 Success", route is ModelRouteResult.Success)
        val activeConfig: ModelConfig? = when (route) {
            is ModelRouteResult.Success -> route.config
            is ModelRouteResult.ModelUnavailable -> null
        }
        assertNotNull("activeConfig 应非 null（Success 路径）", activeConfig)
        assertEquals(NanoDetModelContractExp22.VERSION, activeConfig!!.version)
        assertEquals(NanoDetModelContractExp22.PARAM_SHA256, activeConfig.paramSha256)
        assertEquals(NanoDetModelContractExp22.MODEL_SHA256, activeConfig.modelSha256)
        assertNotEquals("不应为 exp09 版本", NanoDetModelContract.VERSION, activeConfig.version)
    }

    @Test
    fun `White_ Success path carries exp23 metadata not exp09`() {
        val route = PartModelRouter.resolveModelConfig("White_BOLT_001")
        val activeConfig = (route as ModelRouteResult.Success).config
        val result = NanoDetRoiInferenceResult(
            roiId = "test-roi",
            status = NanoDetInferenceStatus.MODEL_TARGET_UNSUPPORTED,
            modelSuggestion = null,
            matchingScore = null,
            targetClassIndex = null,
            modelVersion = activeConfig.version,
            modelParamSha256 = activeConfig.paramSha256,
            modelSha256 = activeConfig.modelSha256,
        )
        assertEquals(NanoDetModelContractExp23.VERSION, result.modelVersion)
        assertEquals(NanoDetModelContractExp23.PARAM_SHA256, result.modelParamSha256)
        assertEquals(NanoDetModelContractExp23.MODEL_SHA256, result.modelSha256)
        assertNotEquals("不应为 exp09 版本", NanoDetModelContract.VERSION, result.modelVersion)
    }

    @Test
    fun `Black_ Success path carries exp22 metadata not exp09`() {
        val route = PartModelRouter.resolveModelConfig("Black_NUT_002")
        val activeConfig = (route as ModelRouteResult.Success).config
        val result = NanoDetRoiInferenceResult(
            roiId = "test-roi",
            status = NanoDetInferenceStatus.NO_DETECTION,
            modelSuggestion = NanoDetSuggestion.NG,
            matchingScore = null,
            targetClassIndex = 2,
            modelVersion = activeConfig.version,
            modelParamSha256 = activeConfig.paramSha256,
            modelSha256 = activeConfig.modelSha256,
        )
        assertEquals(NanoDetModelContractExp22.VERSION, result.modelVersion)
        assertEquals(NanoDetModelContractExp22.PARAM_SHA256, result.modelParamSha256)
        assertEquals(NanoDetModelContractExp22.MODEL_SHA256, result.modelSha256)
        assertNotEquals("不应为 exp09 版本", NanoDetModelContract.VERSION, result.modelVersion)
    }

    // ── 回归：FullImageInferResult 默认值安全检查 ──
    // FullImageInferResult 的默认 modelVersion/modelParamSha256/modelSha256 值是 exp09 的。
    // 当结果不需要模型元数据时，调用方必须显式传 null，不能依赖默认值。

    @Test
    fun `FullImageInferResult defaults are null - callers must set model metadata`() {
        // T2: 默认值为 null，避免 MODEL_UNAVAILABLE 泄露 exp09 元数据
        val defaultsOnly = FullImageInferResult(
            detections = emptyList(),
            highestScore = null,
            elapsedMs = 0,
            imageWidth = 0,
            imageHeight = 0,
            exifOrientation = null,
            status = NanoDetInferenceStatus.MODEL_UNAVAILABLE,
        )
        assertNull("默认 modelVersion 应为 null", defaultsOnly.modelVersion)
        assertNull("默认 modelParamSha256 应为 null", defaultsOnly.modelParamSha256)
        assertNull("默认 modelSha256 应为 null", defaultsOnly.modelSha256)
        // 调用方必须显式传入所选模型的元数据
        val withMetadata = defaultsOnly.copy(
            modelVersion = NanoDetModelContractExp22.VERSION,
            modelParamSha256 = NanoDetModelContractExp22.PARAM_SHA256,
            modelSha256 = NanoDetModelContractExp22.MODEL_SHA256,
        )
        assertEquals(NanoDetModelContractExp22.VERSION, withMetadata.modelVersion)
        assertEquals(NanoDetModelContractExp22.PARAM_SHA256, withMetadata.modelParamSha256)
        assertEquals(NanoDetModelContractExp22.MODEL_SHA256, withMetadata.modelSha256)
    }

    // ── exp23 verified config can reach decoder ──
    // 资产已验证时，路由返回 Success，服务可创建 runtime 并调用解码器。

    @Test
    fun `exp23 verified config enables modelAvailable`() {
        val route = PartModelRouter.resolveModelConfig("White_BOLT_001")
        assertTrue("exp23 已验证应返回 Success", route is ModelRouteResult.Success)
        val modelAvailable = route is ModelRouteResult.Success &&
            ((route as? ModelRouteResult.Success)?.config?.assetsVerified == true)
        assertEquals("modelAvailable 应为 true", true, modelAvailable)
    }
}