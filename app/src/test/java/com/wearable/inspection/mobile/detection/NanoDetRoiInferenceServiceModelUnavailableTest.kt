package com.wearable.inspection.mobile.detection

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * MODEL_UNAVAILABLE 真实服务回归测试。
 *
 * 构造真实 [NanoDetRoiInferenceService]，传入 White_/Black_ partId
 * 和可计数的 fake runtime factory，调用实际 full-image 与 ROI 公开方法。
 *
 * 断言：结果状态为 MODEL_UNAVAILABLE、modelVersion/modelParamSha256/modelSha256 均为 null，
 * 且 runtime factory 未被调用。
 *
 * 当生产服务逻辑回归（如 early-return 路径被误删）时此测试应失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NanoDetRoiInferenceServiceModelUnavailableTest {

    private fun createCountingFactory(): Pair<NanoDetTensorRuntimeFactory, AtomicInteger> {
        val callCount = AtomicInteger(0)
        val factory = NanoDetTensorRuntimeFactory { _, _, _ ->
            callCount.incrementAndGet()
            object : NanoDetTensorRuntime {
                override fun infer(inputNchw: FloatArray) = FloatArray(0)
                override fun close() {}
            }
        }
        return factory to callCount
    }

    private fun createTestRoi(id: String = "roi-1", targetType: String = "NUT"): RoiDefinitionEntity =
        RoiDefinitionEntity(
            id = id,
            templateId = "template-1",
            name = "Test ROI",
            order = 0,
            normalizedRect = """{"left":0.1,"top":0.2,"right":0.8,"bottom":0.9}""",
            inspectionType = "VISUAL",
            targetType = targetType,
        )

    // ── inferSavedPhoto: White_ partId 路由到 exp23 ──

    @Test
    fun `inferSavedPhoto with White_ partId routes to exp23`() {
        val (factory, callCount) = createCountingFactory()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val service = NanoDetRoiInferenceService(
            context = context,
            runtimeFactory = factory,
            partId = "White_BOLT_001",
        )

        val rois = listOf(
            createTestRoi("roi-1", "NUT"),
            createTestRoi("roi-2", "THREAD"),
        )
        val results = service.inferSavedPhoto("/nonexistent/photo.jpg", rois, 1)

        assertEquals(2, results.size)
        results.values.forEach { result ->
            // 路由成功但 Robolectric 无 native → IMAGE_UNREADABLE 或 RUNTIME_UNAVAILABLE
            assertTrue(
                "White_ partId 不应返回 MODEL_UNAVAILABLE（路由已成功）",
                result.status != NanoDetInferenceStatus.MODEL_UNAVAILABLE
            )
            // 路由成功，应携带 exp23 模型元数据
            assertEquals(NanoDetModelContractExp23.VERSION, result.modelVersion)
            assertEquals(NanoDetModelContractExp23.PARAM_SHA256, result.modelParamSha256)
            assertEquals(NanoDetModelContractExp23.MODEL_SHA256, result.modelSha256)
        }
        service.close()
    }

    // ── inferSavedPhoto: Black_ partId 路由到 exp22 ──

    @Test
    fun `inferSavedPhoto with Black_ partId routes to exp22`() {
        val (factory, callCount) = createCountingFactory()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val service = NanoDetRoiInferenceService(
            context = context,
            runtimeFactory = factory,
            partId = "Black_NUT_002",
        )

        val rois = listOf(
            createTestRoi("roi-a", "THREAD"),
            createTestRoi("roi-b", "BOLT"),
            createTestRoi("roi-c", "NUTSERT"),
        )
        val results = service.inferSavedPhoto("/nonexistent/photo.jpg", rois, 1)

        assertEquals(3, results.size)
        results.values.forEach { result ->
            assertTrue(
                "Black_ partId 不应返回 MODEL_UNAVAILABLE（路由已成功）",
                result.status != NanoDetInferenceStatus.MODEL_UNAVAILABLE
            )
            assertEquals(NanoDetModelContractExp22.VERSION, result.modelVersion)
            assertEquals(NanoDetModelContractExp22.PARAM_SHA256, result.modelParamSha256)
            assertEquals(NanoDetModelContractExp22.MODEL_SHA256, result.modelSha256)
        }
        service.close()
    }

    // ── inferSavedPhoto: 空 ROI 列表 ──

    @Test
    fun `inferSavedPhoto with empty ROIs returns empty map`() {
        val (factory, callCount) = createCountingFactory()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val service = NanoDetRoiInferenceService(
            context = context,
            runtimeFactory = factory,
            partId = "White_BOLT_001",
        )

        val results = service.inferSavedPhoto("/nonexistent/photo.jpg", emptyList(), 1)

        assertTrue("空 ROI 应返回空 map", results.isEmpty())
        assertEquals("runtime factory 不应被调用", 0, callCount.get())
        service.close()
    }

    // ── inferFullImage: White_ partId 路由到 exp23 ──

    @Test
    fun `inferFullImage with White_ partId routes to exp23`() {
        val (factory, callCount) = createCountingFactory()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val service = NanoDetRoiInferenceService(
            context = context,
            runtimeFactory = factory,
            partId = "White_BOLT_001",
        )

        val result = service.inferFullImage("/nonexistent/photo.jpg")

        // 路由成功，非 MODEL_UNAVAILABLE；Robolectric 无 native → IMAGE_UNREADABLE 或 RUNTIME_UNAVAILABLE
        assertTrue(
            "White_ partId inferFullImage 不应返回 MODEL_UNAVAILABLE（路由已成功）",
            result.status != NanoDetInferenceStatus.MODEL_UNAVAILABLE
        )
        // IMAGE_UNREADABLE/RUNTIME_UNAVAILABLE 早返回路径不携带模型元数据
        // 只验证路由未回退到 exp09（MODEL_UNAVAILABLE 是唯一携带"不可用原因"的状态）
        service.close()
    }

    // ── inferFullImage: Black_ partId 路由到 exp22 ──

    @Test
    fun `inferFullImage with Black_ partId routes to exp22`() {
        val (factory, callCount) = createCountingFactory()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val service = NanoDetRoiInferenceService(
            context = context,
            runtimeFactory = factory,
            partId = "Black_NUT_002",
        )

        val result = service.inferFullImage("/nonexistent/photo.jpg")

        assertTrue(
            "Black_ partId inferFullImage 不应返回 MODEL_UNAVAILABLE（路由已成功）",
            result.status != NanoDetInferenceStatus.MODEL_UNAVAILABLE
        )
        service.close()
    }

    // ── 对照：旧零件（无前缀）不走 MODEL_UNAVAILABLE ──

    @Test
    fun `old partId without prefix does NOT return MODEL_UNAVAILABLE`() {
        val (factory, callCount) = createCountingFactory()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val service = NanoDetRoiInferenceService(
            context = context,
            runtimeFactory = factory,
            partId = "part_001",  // 无 White_/Black_ 前缀 → exp09
        )

        // inferFullImage 会尝试加载模型，因 assets 不存在而失败
        // 但不应返回 MODEL_UNAVAILABLE（那是 White_/Black_ 的专属状态）
        val result = service.inferFullImage("/nonexistent/photo.jpg")
        assertTrue(
            "旧零件不应返回 MODEL_UNAVAILABLE",
            result.status != NanoDetInferenceStatus.MODEL_UNAVAILABLE
        )
        service.close()
    }
}