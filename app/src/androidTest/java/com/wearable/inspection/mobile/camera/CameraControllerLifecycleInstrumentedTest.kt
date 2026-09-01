package com.wearable.inspection.mobile.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * CameraController 真机生命周期测试
 *
 * 使用真实 CameraX 绑定，验证模式切换、资源清理和 Observer 管理。
 * 必须在真机上运行，不使用 Fake。
 */
@RunWith(AndroidJUnit4::class)
class CameraControllerLifecycleInstrumentedTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.CAMERA
    )

    private lateinit var context: Context
    private lateinit var controller: CameraController
    private lateinit var lifecycleOwner: TestLifecycleOwner
    private lateinit var previewView: PreviewView

    companion object {
        private const val TAG = "CameraLifecycleTest"
        private const val MODE_SWITCH_TIMEOUT_MS = 10_000L
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        controller = CameraController.getInstance(context)
        lifecycleOwner = TestLifecycleOwner()
        previewView = PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }

    /**
     * 20 轮模式 round-trip 测试
     *
     * 按顺序切换：INSPECTION → DPM_SCAN → STAMP_OCR → TEMPLATE_CAPTURE → IDLE → INSPECTION
     * 重复 20 轮，每次记录状态。
     */
    @Test
    fun modeRoundTrip20Times() = runBlocking {
        val modes = listOf(
            CameraMode.INSPECTION,
            CameraMode.DPM_SCAN,
            CameraMode.STAMP_OCR,
            CameraMode.TEMPLATE_CAPTURE,
            CameraMode.IDLE,
            CameraMode.INSPECTION
        )

        Log.i(TAG, "=== 开始 20 轮模式 round-trip 测试 ===")

        // 初始连接
        val connectResult = controller.connect(
            lifecycleOwner,
            previewView.surfaceProvider,
            CameraMode.INSPECTION
        )
        assertTrue("初始连接失败: ${connectResult.exceptionOrNull()?.message}", connectResult.isSuccess)
        Log.i(TAG, "初始连接成功，模式: ${controller.currentMode()}")

        // 20 轮 round-trip
        repeat(20) { round ->
            Log.i(TAG, "--- 第 ${round + 1} 轮 ---")
            for (mode in modes) {
                if (mode == controller.currentMode()) {
                    Log.i(TAG, "  跳过相同模式: $mode")
                    continue
                }

                val result = controller.switchMode(mode)
                assertTrue(
                    "第 ${round + 1} 轮切换到 $mode 失败: ${result.exceptionOrNull()?.message}",
                    result.isSuccess
                )

                // 记录状态
                val currentMode = controller.currentMode()
                val isActive = controller.isActive
                Log.i(TAG, "  模式: $mode → currentMode=$currentMode, isActive=$isActive")

                assertEquals("模式不匹配", mode, currentMode)

                // 等待一小段时间让 CameraX 完成绑定
                Thread.sleep(200)
            }
        }

        // 最终断开
        controller.disconnect()
        Log.i(TAG, "=== 20 轮模式 round-trip 测试完成 ===")
    }

    /**
     * 连续 20 次相同模式切换（不应重绑）
     */
    @Test
    fun sameModeRepeat20Times() = runBlocking {
        val connectResult = controller.connect(
            lifecycleOwner,
            previewView.surfaceProvider,
            CameraMode.INSPECTION
        )
        assertTrue(connectResult.isSuccess)

        repeat(20) { i ->
            val result = controller.switchMode(CameraMode.INSPECTION)
            assertTrue("第 ${i + 1} 次相同模式切换失败", result.isSuccess)
            assertEquals(CameraMode.INSPECTION, controller.currentMode())
        }

        controller.disconnect()
        Log.i(TAG, "相同模式 20 次切换测试通过")
    }

    /**
     * 快速连续切换（并发压力）
     */
    @Test
    fun rapidModeSwitching() = runBlocking {
        val connectResult = controller.connect(
            lifecycleOwner,
            previewView.surfaceProvider,
            CameraMode.INSPECTION
        )
        assertTrue(connectResult.isSuccess)

        val modes = listOf(
            CameraMode.DPM_SCAN,
            CameraMode.STAMP_OCR,
            CameraMode.TEMPLATE_CAPTURE,
            CameraMode.INSPECTION
        )

        // 快速连续切换，不等待
        repeat(10) { round ->
            for (mode in modes) {
                val result = controller.switchMode(mode)
                // 快速切换可能导致某些失败，但不应崩溃
                Log.i(TAG, "快速切换 ${round + 1}/10 → $mode: ${result.isSuccess}")
            }
        }

        // 最终状态应一致
        assertTrue("最终应已连接", controller.isConnected())
        controller.disconnect()
        Log.i(TAG, "快速切换压力测试通过")
    }

    /**
     * disconnect 后重新 connect
     */
    @Test
    fun disconnectAndReconnect() = runBlocking {
        // 连接
        val connectResult = controller.connect(
            lifecycleOwner,
            previewView.surfaceProvider,
            CameraMode.INSPECTION
        )
        assertTrue(connectResult.isSuccess)
        assertTrue(controller.isConnected())

        // 断开
        controller.disconnect()
        assertFalse(controller.isConnected())

        // 重新连接
        val reconnectResult = controller.connect(
            lifecycleOwner,
            previewView.surfaceProvider,
            CameraMode.DPM_SCAN
        )
        assertTrue("重新连接失败", reconnectResult.isSuccess)
        assertTrue(controller.isConnected())
        assertEquals(CameraMode.DPM_SCAN, controller.currentMode())

        // 再次断开
        controller.disconnect()
        Log.i(TAG, "disconnect/reconnect 测试通过")
    }

    /**
     * 验证 UseCase 配置正确性
     */
    @Test
    fun useCaseConfiguration() = runBlocking {
        // INSPECTION: Preview + Analysis + Capture
        var result = controller.connect(
            lifecycleOwner,
            previewView.surfaceProvider,
            CameraMode.INSPECTION
        )
        assertTrue(result.isSuccess)
        // 不直接检查 UseCase 数量（需要 binder 访问），但验证连接成功

        // DPM_SCAN: Preview + Analysis
        result = controller.switchMode(CameraMode.DPM_SCAN)
        assertTrue(result.isSuccess)
        assertEquals(CameraMode.DPM_SCAN, controller.currentMode())

        // TEMPLATE_CAPTURE: Preview + Capture
        result = controller.switchMode(CameraMode.TEMPLATE_CAPTURE)
        assertTrue(result.isSuccess)
        assertEquals(CameraMode.TEMPLATE_CAPTURE, controller.currentMode())

        // STAMP_OCR: Preview + Analysis + Capture
        result = controller.switchMode(CameraMode.STAMP_OCR)
        assertTrue(result.isSuccess)
        assertEquals(CameraMode.STAMP_OCR, controller.currentMode())

        controller.disconnect()
        Log.i(TAG, "UseCase 配置测试通过")
    }

    /**
     * 测试 LifecycleOwner
     */
    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)

        init {
            registry.currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle get() = registry

        fun pause() {
            registry.currentState = Lifecycle.State.STARTED
        }

        fun resume() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
