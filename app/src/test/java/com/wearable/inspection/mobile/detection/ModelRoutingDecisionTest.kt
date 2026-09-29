package com.wearable.inspection.mobile.detection

import com.wearable.inspection.mobile.data.entity.RoiTargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T1 模型路由决策测试
 *
 * 覆盖：各模型的 decideWithPolicy 行为，包括不支持类别和 FEATURE。
 */
class ModelRoutingDecisionTest {

    private fun candidate(classIndex: Int, score: Float) = NanoDetCandidate(
        classIndex = classIndex,
        className = "class_$classIndex",
        score = score,
        box = NanoDetBox(0.0, 0.0, 20.0, 20.0),
        point = 0
    )

    // ── exp09 路由决策 ──

    @Test
    fun `exp09 NUT detected at threshold`() {
        val decision = NanoDetDecisionPolicy.decideWithPolicy(
            RoiTargetType.NUT,
            listOf(candidate(0, 0.50f)),
            0.50f,
            Exp09ClassPolicy
        )
        assertEquals(NanoDetInferenceStatus.DETECTED, decision.status)
        assertEquals(NanoDetSuggestion.OK, decision.suggestion)
        assertEquals(0, decision.targetClassIndex)
    }

    @Test
    fun `exp09 THREAD below threshold`() {
        val decision = NanoDetDecisionPolicy.decideWithPolicy(
            RoiTargetType.THREAD,
            listOf(candidate(1, 0.30f)),
            0.50f,
            Exp09ClassPolicy
        )
        assertEquals(NanoDetInferenceStatus.DETECTED_BELOW_THRESHOLD, decision.status)
        assertEquals(NanoDetSuggestion.NG, decision.suggestion)
        assertEquals(1, decision.targetClassIndex)
    }

    // ── exp22 黑件路由决策 ──

    @Test
    fun `exp22 THREAD maps to class 0`() {
        val decision = NanoDetDecisionPolicy.decideWithPolicy(
            RoiTargetType.THREAD,
            listOf(candidate(0, 0.80f)),
            0.50f,
            Exp22BlackClassPolicy
        )
        assertEquals(NanoDetInferenceStatus.DETECTED, decision.status)
        assertEquals(0, decision.targetClassIndex)
    }

    @Test
    fun `exp22 NUT maps to class 2`() {
        val decision = NanoDetDecisionPolicy.decideWithPolicy(
            RoiTargetType.NUT,
            listOf(candidate(2, 0.70f)),
            0.50f,
            Exp22BlackClassPolicy
        )
        assertEquals(NanoDetInferenceStatus.DETECTED, decision.status)
        assertEquals(2, decision.targetClassIndex)
    }

    @Test
    fun `exp22 BOLT maps to class 3`() {
        val decision = NanoDetDecisionPolicy.decideWithPolicy(
            RoiTargetType.BOLT,
            listOf(candidate(3, 0.60f)),
            0.50f,
            Exp22BlackClassPolicy
        )
        assertEquals(NanoDetInferenceStatus.DETECTED, decision.status)
        assertEquals(3, decision.targetClassIndex)
    }

    @Test
    fun `exp22 NUTSERT maps to class 1`() {
        val decision = NanoDetDecisionPolicy.decideWithPolicy(
            RoiTargetType.NUTSERT,
            listOf(candidate(1, 0.55f)),
            0.50f,
            Exp22BlackClassPolicy
        )
        assertEquals(NanoDetInferenceStatus.DETECTED, decision.status)
        assertEquals(1, decision.targetClassIndex)
    }

    // ── exp23 白件路由决策 ──

    @Test
    fun `exp23 NUT maps to class 0`() {
        val decision = NanoDetDecisionPolicy.decideWithPolicy(
            RoiTargetType.NUT,
            listOf(candidate(0, 0.90f)),
            0.50f,
            Exp23WhiteClassPolicy
        )
        assertEquals(NanoDetInferenceStatus.DETECTED, decision.status)
        assertEquals(0, decision.targetClassIndex)
    }

    @Test
    fun `exp23 THREAD maps to class 1`() {
        val decision = NanoDetDecisionPolicy.decideWithPolicy(
            RoiTargetType.THREAD,
            listOf(candidate(1, 0.85f)),
            0.50f,
            Exp23WhiteClassPolicy
        )
        assertEquals(NanoDetInferenceStatus.DETECTED, decision.status)
        assertEquals(1, decision.targetClassIndex)
    }

    @Test
    fun `exp23 BOLT returns MODEL_TARGET_UNSUPPORTED`() {
        val decision = NanoDetDecisionPolicy.decideWithPolicy(
            RoiTargetType.BOLT,
            listOf(candidate(0, 0.95f)),
            0.50f,
            Exp23WhiteClassPolicy
        )
        assertEquals(NanoDetInferenceStatus.MODEL_TARGET_UNSUPPORTED, decision.status)
        assertNull(decision.suggestion)
        assertNull(decision.targetClassIndex)
    }

    @Test
    fun `exp23 NUTSERT returns MODEL_TARGET_UNSUPPORTED`() {
        val decision = NanoDetDecisionPolicy.decideWithPolicy(
            RoiTargetType.NUTSERT,
            listOf(candidate(0, 0.95f)),
            0.50f,
            Exp23WhiteClassPolicy
        )
        assertEquals(NanoDetInferenceStatus.MODEL_TARGET_UNSUPPORTED, decision.status)
        assertNull(decision.suggestion)
        assertNull(decision.targetClassIndex)
    }

    // ── FEATURE 不受模型影响 ──

    @Test
    fun `FEATURE returns FEATURE_UNSUPPORTED for all models`() {
        listOf(Exp09ClassPolicy, Exp22BlackClassPolicy, Exp23WhiteClassPolicy).forEach { policy ->
            val decision = NanoDetDecisionPolicy.decideWithPolicy(
                RoiTargetType.FEATURE,
                listOf(candidate(0, 0.95f)),
                0.50f,
                policy
            )
            assertEquals("FEATURE unsupported for ${policy::class.simpleName}", NanoDetInferenceStatus.FEATURE_UNSUPPORTED, decision.status)
        }
    }

    // ── FEATURE vs MODEL_TARGET_UNSUPPORTED 区分 ──

    @Test
    fun `FEATURE and model-unsupported are distinct statuses`() {
        // FEATURE → FEATURE_UNSUPPORTED
        val featureDecision = NanoDetDecisionPolicy.decideWithPolicy(
            RoiTargetType.FEATURE, emptyList(), 0.50f, Exp23WhiteClassPolicy
        )
        assertEquals(NanoDetInferenceStatus.FEATURE_UNSUPPORTED, featureDecision.status)

        // White BOLT → MODEL_TARGET_UNSUPPORTED（不是 FEATURE_UNSUPPORTED）
        val boltDecision = NanoDetDecisionPolicy.decideWithPolicy(
            RoiTargetType.BOLT, emptyList(), 0.50f, Exp23WhiteClassPolicy
        )
        assertEquals(NanoDetInferenceStatus.MODEL_TARGET_UNSUPPORTED, boltDecision.status)

        // 两者状态不同
        assertNotEquals(featureDecision.status, boltDecision.status)
    }

    // ── null targetType ──

    @Test
    fun `null targetType returns ROI_NOT_CONFIGURED for all models`() {
        listOf(Exp09ClassPolicy, Exp22BlackClassPolicy, Exp23WhiteClassPolicy).forEach { policy ->
            val decision = NanoDetDecisionPolicy.decideWithPolicy(
                null,
                listOf(candidate(0, 0.95f)),
                0.50f,
                policy
            )
            assertEquals("null targetType for ${policy::class.simpleName}", NanoDetInferenceStatus.ROI_NOT_CONFIGURED, decision.status)
        }
    }

    // ── 路由前缀 + 决策组合 ──
    // 注意：exp22/exp23 资产未验证时，路由器返回 ModelUnavailable。
    // 这些测试直接使用 classPolicy 验证类别映射，不依赖路由器的资产验证状态。

    @Test
    fun `exp22 class policy THREAD maps to class 0`() {
        val decision = NanoDetDecisionPolicy.decideWithPolicy(
            RoiTargetType.THREAD,
            listOf(candidate(0, 0.80f)),
            0.50f,
            Exp22BlackClassPolicy
        )
        assertEquals(NanoDetInferenceStatus.DETECTED, decision.status)
        assertEquals(0, decision.targetClassIndex)
    }

    @Test
    fun `exp23 class policy NUT maps to class 0`() {
        val decision = NanoDetDecisionPolicy.decideWithPolicy(
            RoiTargetType.NUT,
            listOf(candidate(0, 0.90f)),
            0.50f,
            Exp23WhiteClassPolicy
        )
        assertEquals(NanoDetInferenceStatus.DETECTED, decision.status)
        assertEquals(0, decision.targetClassIndex)
    }

    @Test
    fun `exp23 class policy BOLT returns MODEL_TARGET_UNSUPPORTED`() {
        val decision = NanoDetDecisionPolicy.decideWithPolicy(
            RoiTargetType.BOLT,
            listOf(candidate(0, 0.95f)),
            0.50f,
            Exp23WhiteClassPolicy
        )
        assertEquals(NanoDetInferenceStatus.MODEL_TARGET_UNSUPPORTED, decision.status)
    }

    @Test
    fun `old ID BOLT routes to exp09 class 2`() {
        val route = PartModelRouter.resolveModelConfig("old_part_001") as ModelRouteResult.Success
        val decision = NanoDetDecisionPolicy.decideWithPolicy(
            RoiTargetType.BOLT,
            listOf(candidate(2, 0.70f)),
            0.50f,
            route.classPolicy
        )
        assertEquals(NanoDetInferenceStatus.DETECTED, decision.status)
        assertEquals(2, decision.targetClassIndex)
    }

    // ── 无匹配候选 ──

    @Test
    fun `no matching candidate returns NO_DETECTION`() {
        val decision = NanoDetDecisionPolicy.decideWithPolicy(
            RoiTargetType.NUT,
            listOf(candidate(1, 0.90f)), // class 1, not class 0
            0.50f,
            Exp09ClassPolicy
        )
        assertEquals(NanoDetInferenceStatus.NO_DETECTION, decision.status)
        assertEquals(NanoDetSuggestion.NG, decision.suggestion)
    }
}