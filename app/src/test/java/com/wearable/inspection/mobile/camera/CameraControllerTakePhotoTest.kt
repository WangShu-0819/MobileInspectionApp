package com.wearable.inspection.mobile.camera

import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.mockito.MockedStatic
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * CameraController takePhoto 异步行为测试
 *
 * 核心测试策略：
 * - runTest + StandardTestDispatcher: 虚拟时间，advanceUntilIdle() 驱动协程
 * - FakeCaptureExecutor: autoComplete=false 时存储回调，autoComplete=true 时在独立线程回调
 * - 对于需要 pending 状态的测试：launch + advanceUntilIdle 让 takePhoto 进入 suspendCancellableCoroutine
 *   此时 isCapturing=true, takePicture 已调用，但回调未触发
 * - 对于需要回调完成的测试：completeLast 后 advanceUntilIdle 让 cont.resume 生效
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CameraControllerTakePhotoTest {

    private lateinit var fakeBinder: FakeCaptureBinder
    private lateinit var fakeExecutor: FakeCaptureExecutor
    private lateinit var controller: CameraController
    private lateinit var fakeLifecycleOwner: FakeLifecycleOwner
    private lateinit var mockContext: Context
    private lateinit var looperStatic: MockedStatic<Looper>

    @Before
    fun setUp() {
        val mockLooper = mock(Looper::class.java)
        `when`(mockLooper.getThread()).thenReturn(Thread.currentThread())
        looperStatic = mockStatic(Looper::class.java)
        looperStatic.`when`<Looper> { Looper.getMainLooper() }.thenReturn(mockLooper)
        looperStatic.`when`<Looper> { Looper.myLooper() }.thenReturn(mockLooper)

        fakeBinder = FakeCaptureBinder()
        fakeExecutor = FakeCaptureExecutor()
        mockContext = mock(Context::class.java)
        `when`(mockContext.checkPermission(anyString(), anyInt(), anyInt()))
            .thenReturn(PackageManager.PERMISSION_GRANTED)
        `when`(mockContext.mainExecutor).thenReturn(Runnable::run)
        controller = CameraController.createForTest(mockContext, fakeBinder, fakeExecutor)
        fakeLifecycleOwner = FakeLifecycleOwner()
    }

    @After
    fun tearDown() {
        fakeLifecycleOwner.destroy()
        looperStatic.close()
    }

    // ─── 辅助方法 ───

    private suspend fun connectAndOpen(): CameraSession {
        val result = controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        val session = result.getOrNull()!!
        fakeBinder.simulateCameraState(CameraState.Type.OPEN)
        return session
    }

    private fun cleanupFiles(vararg files: File) {
        files.forEach { it.delete() }
    }

    // ─── 前置条件测试 ───

    @Test
    fun `未连接时拍照失败`() = runTest {
        val tempFile = File.createTempFile("test", ".jpg")
        try {
            val result = controller.takePhoto("fake-session", tempFile)
            assertTrue("未连接时拍照应该失败", result.isFailure)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `sessionId 不匹配时拍照失败`() = runTest {
        val session = connectAndOpen()
        val tempFile = File.createTempFile("test", ".jpg")
        try {
            val captureResult = controller.takePhoto("wrong-session-id", tempFile)
            assertTrue("sessionId 不匹配时拍照应该失败", captureResult.isFailure)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `相机未就绪时拍照失败`() = runTest {
        val session = connectAndOpen()
        fakeBinder.simulateCameraState(CameraState.Type.PENDING_OPEN)
        val tempFile = File.createTempFile("test", ".jpg")
        try {
            val captureResult = controller.takePhoto(session.sessionId, tempFile)
            assertTrue("相机未就绪时拍照应该失败", captureResult.isFailure)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `release 后拍照失败`() = runTest {
        val session = connectAndOpen()
        controller.release()
        val tempFile = File.createTempFile("test", ".jpg")
        try {
            val captureResult = controller.takePhoto(session.sessionId, tempFile)
            assertTrue("release 后拍照应该失败", captureResult.isFailure)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `disconnect 后拍照失败`() = runTest {
        val session = connectAndOpen()
        controller.disconnect(session.sessionId)
        val tempFile = File.createTempFile("test", ".jpg")
        try {
            val captureResult = controller.takePhoto(session.sessionId, tempFile)
            assertTrue("disconnect 后拍照应该失败", captureResult.isFailure)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `switchMode 后拍照失败`() = runTest {
        val session = connectAndOpen()
        controller.switchMode(CameraMode.DPM_SCAN)
        val tempFile = File.createTempFile("test", ".jpg")
        try {
            val captureResult = controller.takePhoto(session.sessionId, tempFile)
            assertTrue("switchMode 后拍照应该失败", captureResult.isFailure)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `连接新 session 后旧 session 拍照失败`() = runTest {
        val session1 = connectAndOpen()
        val session2 = connectAndOpen()
        val tempFile = File.createTempFile("test", ".jpg")
        try {
            val captureResult = controller.takePhoto(session1.sessionId, tempFile)
            assertTrue("旧 session 拍照应该失败", captureResult.isFailure)
        } finally {
            tempFile.delete()
        }
    }

    // ─── 异步行为测试 ───
    //
    // 核心技巧：launch + advanceUntilIdle()
    // - launch 启动 takePhoto 协程（autoComplete=false 时进入 suspendCancellableCoroutine 挂起）
    // - advanceUntilIdle() 让 TestDispatcher 执行所有就绪协程，包括 launch 的那个
    // - 此时 isCapturing=true, takePicture 已调用，但回调未触发（被存储在 pendingCallbacks）
    // - 主测试协程此时可以调用 takePhoto 测试 single-flight
    // - completeLast 后 advanceUntilIdle 让 cont.resume 生效

    @Test
    fun `第一个请求未完成时第二个请求立即失败`() = runTest {
        val session = connectAndOpen()
        val tempFile1 = File.createTempFile("test1", ".jpg")
        val tempFile2 = File.createTempFile("test2", ".jpg")
        tempFile1.writeBytes(ByteArray(100))
        tempFile2.writeBytes(ByteArray(100))
        try {
            fakeExecutor.autoComplete = false

            // 启动第一个请求（autoComplete=false → 进入 suspendCancellableCoroutine 挂起）
            val job1 = launch { controller.takePhoto(session.sessionId, tempFile1) }
            // 驱动 launch 协程执行到挂起点
            advanceUntilIdle()

            // 此时 isCapturing=true, 第二个请求应该立即失败
            val result2 = controller.takePhoto(session.sessionId, tempFile2)
            assertTrue("第二个请求应该立即失败", result2.isFailure)

            // 完成第一个请求
            fakeExecutor.completeLast(tempFile1)
            advanceUntilIdle()

            assertTrue("第一个请求应该已完成", job1.isCompleted)
        } finally {
            cleanupFiles(tempFile1, tempFile2)
        }
    }

    @Test
    fun `成功回调只提交当前 request token`() = runTest {
        val session = connectAndOpen()
        val tempFile = File.createTempFile("test", ".jpg")
        tempFile.writeBytes(ByteArray(100))
        try {
            fakeExecutor.autoComplete = true
            val result = controller.takePhoto(session.sessionId, tempFile)
            assertTrue("拍照应该成功, 错误: ${result.exceptionOrNull()?.message}", result.isSuccess)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `disconnect 期间的迟到成功回调被拒绝并删除临时文件`() = runTest {
        val session = connectAndOpen()
        val tempFile = File.createTempFile("test", ".jpg")
        tempFile.writeBytes(ByteArray(100))
        try {
            fakeExecutor.autoComplete = false

            val job = launch { controller.takePhoto(session.sessionId, tempFile) }
            advanceUntilIdle()

            // disconnect 使 token 失效
            controller.disconnect(session.sessionId)

            // 完成回调（迟到的）
            fakeExecutor.completeLast(tempFile)
            advanceUntilIdle()

            assertTrue("拍照应该失败（token 失效）", job.isCompleted)
            assertFalse("临时文件应该被删除", tempFile.exists())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `switchMode 期间的迟到回调被拒绝并删除临时文件`() = runTest {
        val session = connectAndOpen()
        val tempFile = File.createTempFile("test", ".jpg")
        tempFile.writeBytes(ByteArray(100))
        try {
            fakeExecutor.autoComplete = false

            val job = launch { controller.takePhoto(session.sessionId, tempFile) }
            advanceUntilIdle()

            controller.switchMode(CameraMode.DPM_SCAN)
            fakeExecutor.completeLast(tempFile)
            advanceUntilIdle()

            assertTrue("拍照应该失败（token 失效）", job.isCompleted)
            assertFalse("临时文件应该被删除", tempFile.exists())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `release 期间的迟到回调被拒绝并删除临时文件`() = runTest {
        val session = connectAndOpen()
        val tempFile = File.createTempFile("test", ".jpg")
        tempFile.writeBytes(ByteArray(100))
        try {
            fakeExecutor.autoComplete = false

            val job = launch { controller.takePhoto(session.sessionId, tempFile) }
            advanceUntilIdle()

            controller.release()
            fakeExecutor.completeLast(tempFile)
            advanceUntilIdle()

            assertTrue("拍照应该失败（token 失效）", job.isCompleted)
            assertFalse("临时文件应该被删除", tempFile.exists())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `协程取消后迟到成功回调不能保存文件`() = runTest {
        val session = connectAndOpen()
        val tempFile = File.createTempFile("test", ".jpg")
        tempFile.writeBytes(ByteArray(100))
        try {
            fakeExecutor.autoComplete = false

            val job = launch { controller.takePhoto(session.sessionId, tempFile) }
            advanceUntilIdle()

            job.cancel()
            advanceUntilIdle()

            assertFalse("临时文件应该被删除", tempFile.exists())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `错误回调删除临时文件并恢复拍照状态`() = runTest {
        val session = connectAndOpen()
        val tempFile = File.createTempFile("test", ".jpg")
        tempFile.writeBytes(ByteArray(100))
        try {
            fakeExecutor.autoComplete = false
            fakeExecutor.failNext = true

            val job = launch { controller.takePhoto(session.sessionId, tempFile) }
            advanceUntilIdle()

            fakeExecutor.completeLast(tempFile)
            advanceUntilIdle()

            assertTrue("拍照应该失败（错误回调）", job.isCompleted)
            assertFalse("临时文件应该被删除", tempFile.exists())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `前一个请求结束后允许发起新的拍照请求`() = runTest {
        val session = connectAndOpen()
        val tempFile1 = File.createTempFile("test1", ".jpg")
        val tempFile2 = File.createTempFile("test2", ".jpg")
        tempFile1.writeBytes(ByteArray(100))
        tempFile2.writeBytes(ByteArray(100))
        try {
            fakeExecutor.autoComplete = true
            val result1 = controller.takePhoto(session.sessionId, tempFile1)
            assertTrue("第一个请求应该成功, 错误: ${result1.exceptionOrNull()?.message}", result1.isSuccess)

            val result2 = controller.takePhoto(session.sessionId, tempFile2)
            assertTrue("第二个请求应该成功, 错误: ${result2.exceptionOrNull()?.message}", result2.isSuccess)
        } finally {
            cleanupFiles(tempFile1, tempFile2)
        }
    }

    @Test
    fun `旧请求的 finally 不清除新请求的 isCapturing`() = runTest {
        val session = connectAndOpen()
        val tempFile1 = File.createTempFile("test1", ".jpg")
        val tempFile2 = File.createTempFile("test2", ".jpg")
        val tempFile3 = File.createTempFile("test3", ".jpg")
        tempFile1.writeBytes(ByteArray(100))
        tempFile2.writeBytes(ByteArray(100))
        tempFile3.writeBytes(ByteArray(100))
        try {
            fakeExecutor.autoComplete = false

            // 第一个请求挂起
            val job1 = launch { controller.takePhoto(session.sessionId, tempFile1) }
            advanceUntilIdle()

            // 第二个请求因 isCapturing=true 而失败
            val result2 = controller.takePhoto(session.sessionId, tempFile2)
            assertTrue("第二个请求应该失败", result2.isFailure)

            // 完成第一个请求
            fakeExecutor.completeLast(tempFile1)
            advanceUntilIdle()
            assertTrue("第一个请求应该完成", job1.isCompleted)

            // 现在应该可以发起新请求
            fakeExecutor.autoComplete = true
            val result3 = controller.takePhoto(session.sessionId, tempFile3)
            assertTrue("第一个请求结束后新请求应该成功, 错误: ${result3.exceptionOrNull()?.message}", result3.isSuccess)
        } finally {
            cleanupFiles(tempFile1, tempFile2, tempFile3)
        }
    }

    @Test
    fun `回调与 disconnect 并发时不会保存过期会话文件`() = runTest {
        val session = connectAndOpen()
        val tempFile = File.createTempFile("test", ".jpg")
        tempFile.writeBytes(ByteArray(100))
        try {
            fakeExecutor.autoComplete = false

            val job = launch { controller.takePhoto(session.sessionId, tempFile) }
            advanceUntilIdle()

            // disconnect 使 token 失效
            controller.disconnect(session.sessionId)

            // 完成回调
            fakeExecutor.completeLast(tempFile)
            advanceUntilIdle()

            assertTrue("拍照应该失败", job.isCompleted)
        } finally {
            tempFile.delete()
        }
    }
}

/**
 * Fake CaptureExecutor（测试用）
 *
 * - autoComplete=true: 在独立 Thread 上延迟 10ms 后回调（模拟异步）
 * - autoComplete=false: 存储回调，等待 completeLast() 手动触发
 */
class FakeCaptureExecutor : CaptureExecutor {
    var autoComplete = true
    var failNext = false

    private val pendingCallbacks = mutableListOf<Pair<Any, Any>>()

    override fun takePicture(
        capture: Any,
        outputOptions: Any,
        executor: Executor,
        callback: Any
    ) {
        if (autoComplete) {
            Thread {
                Thread.sleep(10)
                executeCallback(callback as ImageCapture.OnImageSavedCallback)
            }.start()
        } else {
            synchronized(pendingCallbacks) {
                pendingCallbacks.add(Pair(callback, executor))
            }
        }
    }

    private fun executeCallback(callback: ImageCapture.OnImageSavedCallback) {
        if (failNext) {
            failNext = false
            callback.onError(ImageCaptureException(ImageCapture.ERROR_CAMERA_CLOSED, "模拟错误", null))
        } else {
            callback.onImageSaved(ImageCapture.OutputFileResults(null))
        }
    }

    /**
     * 手动完成最后一个待处理回调（同步执行，确保 cont.resume 在 advanceUntilIdle 前入队）
     */
    fun completeLast(outputFile: File) {
        val pair = synchronized(pendingCallbacks) {
            if (pendingCallbacks.isNotEmpty()) pendingCallbacks.removeAt(0) else null
        }
        if (pair != null) {
            val callback = pair.first as ImageCapture.OnImageSavedCallback
            if (!outputFile.exists() || outputFile.length() == 0L) {
                outputFile.writeBytes(ByteArray(100))
            }
            executeCallback(callback)
        }
    }
}

/**
 * Fake CameraBinder（测试用）
 */
class FakeCaptureBinder : CameraBinder {
    var hasPermission = true
    private var cameraStateObserver: Observer<CameraState>? = null

    override fun hasCameraPermission(): Boolean = hasPermission
    override fun getProvider(): Any = "FakeProvider"
    override fun hasBackCamera(provider: Any): Boolean = true
    override fun createPreview(surfaceProvider: Any): Any = "FakePreview"
    override fun createAnalysis(): Any = "FakeAnalysis"
    override fun createCapture(): Any = "FakeCapture"

    override fun bindToLifecycle(
        provider: Any, lifecycleOwner: LifecycleOwner, selector: Any, useCases: List<Any>
    ): BindResult = BindResult.Success("FakeCamera")

    override fun unbindAll(provider: Any) {}
    override fun getCameraInfo(camera: Any): Any = "FakeCameraInfo"

    override fun observeCameraState(
        cameraInfo: Any, lifecycleOwner: LifecycleOwner, observer: Observer<CameraState>
    ) { cameraStateObserver = observer }

    override fun removeCameraStateObserver(cameraInfo: Any, observer: Observer<CameraState>) {
        if (cameraStateObserver == observer) cameraStateObserver = null
    }

    override fun setAnalyzer(useCase: Any, executor: java.util.concurrent.ExecutorService, callback: (Any) -> Unit) {}
    override fun clearAnalyzer(useCase: Any) {}

    fun simulateCameraState(type: CameraState.Type) {
        cameraStateObserver?.onChanged(CameraState.create(type, null))
    }
}
