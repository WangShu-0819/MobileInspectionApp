package com.wearable.inspection.mobile.camera

import android.content.Context
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CameraController 真机生命周期测试
 *
 * 使用真实 CameraX 绑定，验证模式切换和资源清理。
 * 必须在真机上运行，设备需已授予相机权限。
 * CameraController 要求主线程操作，使用 withContext(Dispatchers.Main) 切换。
 */
@RunWith(AndroidJUnit4::class)
class CameraControllerLifecycleInstrumentedTest {

    private lateinit var context: Context
    private lateinit var controller: CameraController
    private lateinit var lifecycleOwner: TestLifecycleOwner
    private lateinit var previewView: PreviewView

    companion object {
        private const val TAG = "CameraLifecycleTest"
    }

    @Before
    fun setUp() {
        // 通过 shell 命令授予相机权限（无 GrantPermissionRule 依赖）
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${ApplicationProvider.getApplicationContext<Context>().packageName} android.permission.CAMERA")
            .close()
        Thread.sleep(500)

        context = ApplicationProvider.getApplicationContext()

        // 验证权限已授予
        val permStatus = context.checkSelfPermission(android.Manifest.permission.CAMERA)
        assertEquals(
            "相机权限授予失败，请在设备设置中手动授予",
            android.content.pm.PackageManager.PERMISSION_GRANTED,
            permStatus
        )

        controller = CameraController.getInstance(context)
        lifecycleOwner = TestLifecycleOwner()
        // PreviewView 和 LifecycleRegistry 必须在主线程创建
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            previewView = PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FIT_CENTER
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            }
            lifecycleOwner.moveToResumed()
        }
    }

    @Test
    fun modeRoundTrip20Times(): Unit = runBlocking {
        val modes = listOf(
            CameraMode.INSPECTION,
            CameraMode.DPM_SCAN,
            CameraMode.STAMP_OCR,
            CameraMode.TEMPLATE_CAPTURE,
            CameraMode.IDLE,
            CameraMode.INSPECTION
        )

        Log.i(TAG, "=== 开始 20 轮模式 round-trip 测试 ===")

        withContext(Dispatchers.Main) {
            val connectResult = controller.connect(
                lifecycleOwner,
                previewView.surfaceProvider,
                CameraMode.INSPECTION
            )
            assertTrue("初始连接失败: ${connectResult.exceptionOrNull()?.message}", connectResult.isSuccess)
        }

        repeat(20) { round ->
            Log.i(TAG, "--- 第 ${round + 1} 轮 ---")
            for (mode in modes) {
                withContext(Dispatchers.Main) {
                    val result = controller.switchMode(mode)
                    assertTrue(
                        "第 ${round + 1} 轮切换到 $mode 失败: ${result.exceptionOrNull()?.message}",
                        result.isSuccess
                    )
                }
                Log.i(TAG, "  模式: $mode 成功")
                Thread.sleep(200)
            }
        }

        withContext(Dispatchers.Main) {
            controller.disconnect()
        }
        Log.i(TAG, "=== 20 轮模式 round-trip 测试完成 ===")
    }

    @Test
    fun sameModeRepeat20Times(): Unit = runBlocking {
        withContext(Dispatchers.Main) {
            val connectResult = controller.connect(
                lifecycleOwner,
                previewView.surfaceProvider,
                CameraMode.INSPECTION
            )
            assertTrue(connectResult.isSuccess)
        }

        repeat(20) { i ->
            withContext(Dispatchers.Main) {
                val result = controller.switchMode(CameraMode.INSPECTION)
                assertTrue("第 ${i + 1} 次相同模式切换失败", result.isSuccess)
            }
        }

        withContext(Dispatchers.Main) {
            controller.disconnect()
        }
        Log.i(TAG, "相同模式 20 次切换测试通过")
    }

    @Test
    fun rapidModeSwitching(): Unit = runBlocking {
        withContext(Dispatchers.Main) {
            val connectResult = controller.connect(
                lifecycleOwner,
                previewView.surfaceProvider,
                CameraMode.INSPECTION
            )
            assertTrue(connectResult.isSuccess)
        }

        val modes = listOf(
            CameraMode.DPM_SCAN,
            CameraMode.STAMP_OCR,
            CameraMode.TEMPLATE_CAPTURE,
            CameraMode.INSPECTION
        )

        repeat(10) { round ->
            for (mode in modes) {
                withContext(Dispatchers.Main) {
                    val result = controller.switchMode(mode)
                    assertTrue(
                        "快速切换第 ${round + 1}/10 轮 → $mode 失败: ${result.exceptionOrNull()?.message}",
                        result.isSuccess
                    )
                }
            }
        }

        withContext(Dispatchers.Main) {
            assertTrue("最终应有活跃会话", controller.getActiveSession() != null)
            controller.disconnect()
        }
        Log.i(TAG, "快速切换压力测试通过")
    }

    @Test
    fun disconnectAndReconnect(): Unit = runBlocking {
        withContext(Dispatchers.Main) {
            val connectResult = controller.connect(
                lifecycleOwner,
                previewView.surfaceProvider,
                CameraMode.INSPECTION
            )
            assertTrue(connectResult.isSuccess)
            assertTrue("应有活跃会话", controller.getActiveSession() != null)

            controller.disconnect()
            assertNull("断开后不应有活跃会话", controller.getActiveSession())

            val reconnectResult = controller.connect(
                lifecycleOwner,
                previewView.surfaceProvider,
                CameraMode.DPM_SCAN
            )
            assertTrue("重新连接失败", reconnectResult.isSuccess)
            assertNotNull("重连后应有活跃会话", controller.getActiveSession())

            controller.disconnect()
        }
        Log.i(TAG, "disconnect/reconnect 测试通过")
    }

    @Test
    fun useCaseConfiguration(): Unit = runBlocking {
        withContext(Dispatchers.Main) {
            val connectResult = controller.connect(
                lifecycleOwner,
                previewView.surfaceProvider,
                CameraMode.INSPECTION
            )
            assertTrue(connectResult.isSuccess)

            var result = controller.switchMode(CameraMode.DPM_SCAN)
            assertTrue(result.isSuccess)

            result = controller.switchMode(CameraMode.TEMPLATE_CAPTURE)
            assertTrue(result.isSuccess)

            result = controller.switchMode(CameraMode.STAMP_OCR)
            assertTrue(result.isSuccess)

            controller.disconnect()
        }
        Log.i(TAG, "UseCase 配置测试通过")
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)

        fun moveToResumed() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle get() = registry
    }
}
