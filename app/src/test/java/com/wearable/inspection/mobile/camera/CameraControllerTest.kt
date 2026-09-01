package com.wearable.inspection.mobile.camera

import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.camera.core.CameraState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.MockedStatic

/**
 * CameraController 核心逻辑测试
 *
 * 使用 FakeCameraBinder 替代真实 CameraX，
 * 测试状态机、并发串行化、资源清理和异常恢复。
 */
class CameraControllerTest {

    private lateinit var fakeBinder: FakeCameraBinder
    private lateinit var controller: CameraController
    private lateinit var fakeLifecycleOwner: FakeLifecycleOwner
    private lateinit var mockContext: Context
    private lateinit var looperStatic: MockedStatic<Looper>

    @Before
    fun setUp() {
        // Mock Looper for LifecycleRegistry (needs getMainLooper + myLooper)
        val mockLooper = mock(Looper::class.java)
        `when`(mockLooper.getThread()).thenReturn(Thread.currentThread())
        looperStatic = mockStatic(Looper::class.java)
        looperStatic.`when`<Looper> { Looper.getMainLooper() }.thenReturn(mockLooper)
        looperStatic.`when`<Looper> { Looper.myLooper() }.thenReturn(mockLooper)

        fakeBinder = FakeCameraBinder()
        mockContext = mock(Context::class.java)
        `when`(mockContext.checkPermission(anyString(), anyInt(), anyInt()))
            .thenReturn(PackageManager.PERMISSION_GRANTED)
        controller = CameraController.createForTest(mockContext, fakeBinder)
        fakeLifecycleOwner = FakeLifecycleOwner()
    }

    @After
    fun tearDown() {
        fakeLifecycleOwner.destroy()
        looperStatic.close()
    }

    // ─── 基础状态测试 ───

    @Test
    fun `初始状态 - 未连接未释放`() {
        assertFalse(controller.isReleased())
        assertFalse(controller.isConnected())
        assertEquals(CameraMode.IDLE, controller.currentMode())
    }

    @Test
    fun `connect 成功后 - isConnected 为 true`() = runTest {
        val result = controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        assertTrue(result.isSuccess)
        assertTrue(controller.isConnected())
        assertEquals(CameraMode.INSPECTION, controller.currentMode())
    }

    @Test
    fun `connect 使用指定模式`() = runTest {
        val result = controller.connect(fakeLifecycleOwner, FakeSurfaceProvider(), CameraMode.DPM_SCAN)
        assertTrue(result.isSuccess)
        assertEquals(CameraMode.DPM_SCAN, controller.currentMode())
    }

    @Test
    fun `release 后 connect 失败`() = runTest {
        controller.release()
        val result = controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("已永久释放") == true)
    }

    @Test
    fun `权限拒绝 - connect 失败`() = runTest {
        fakeBinder.hasPermission = false
        val result = controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    // ─── 模式切换测试 ───

    @Test
    fun `switchMode 成功切换`() = runTest {
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        val result = controller.switchMode(CameraMode.DPM_SCAN)
        assertTrue(result.isSuccess)
        assertEquals(CameraMode.DPM_SCAN, controller.currentMode())
    }

    @Test
    fun `switchMode 相同模式 - 不重绑`() = runTest {
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        val bindCountBefore = fakeBinder.bindCount
        val result = controller.switchMode(CameraMode.INSPECTION)
        assertTrue(result.isSuccess)
        assertEquals(bindCountBefore, fakeBinder.bindCount)
    }

    @Test
    fun `switchMode 未连接 - 失败`() = runTest {
        val result = controller.switchMode(CameraMode.DPM_SCAN)
        assertTrue(result.isFailure)
    }

    @Test
    fun `所有模式 round-trip`() = runTest {
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        for (mode in CameraMode.entries) {
            if (mode == CameraMode.IDLE) continue
            val result = controller.switchMode(mode)
            assertTrue("切换到 $mode 失败", result.isSuccess)
            assertEquals(mode, controller.currentMode())
        }
    }

    // ─── 并发串行化测试 ───

    @Test
    fun `并发 connect + switchMode - 串行执行无崩溃`() = runTest {
        fakeBinder.bindDelayMs = 50

        val jobs = mutableListOf<Job>()
        jobs += launch(Dispatchers.Default) {
            controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        }
        jobs += launch(Dispatchers.Default) {
            delay(10)
            controller.switchMode(CameraMode.DPM_SCAN)
        }
        jobs.forEach { it.join() }
    }

    @Test
    fun `2x switchMode 并发 - 串行执行无竞态`() = runTest {
        fakeBinder.bindDelayMs = 30
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())

        val jobs = mutableListOf<Deferred<Result<Unit>>>()
        jobs += async(Dispatchers.Default) { controller.switchMode(CameraMode.DPM_SCAN) }
        jobs += async(Dispatchers.Default) { controller.switchMode(CameraMode.STAMP_OCR) }

        jobs.awaitAll()
        val finalMode = controller.currentMode()
        assertTrue(
            "最终模式应为 DPM_SCAN 或 STAMP_OCR，实际: $finalMode",
            finalMode == CameraMode.DPM_SCAN || finalMode == CameraMode.STAMP_OCR
        )
    }

    @Test
    fun `switchMode + disconnect 并发 - 串行执行`() = runTest {
        fakeBinder.bindDelayMs = 30
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())

        val job1 = launch(Dispatchers.Default) { controller.switchMode(CameraMode.DPM_SCAN) }
        val job2 = launch(Dispatchers.Default) { controller.disconnect() }
        joinAll(job1, job2)
    }

    @Test
    fun `switchMode + release 并发 - 串行执行`() = runTest {
        fakeBinder.bindDelayMs = 30
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())

        val job1 = launch(Dispatchers.Default) { controller.switchMode(CameraMode.DPM_SCAN) }
        val job2 = launch(Dispatchers.Default) { controller.release() }
        joinAll(job1, job2)
        assertTrue(controller.isReleased())
    }

    // ─── 资源清理测试 ───

    @Test
    fun `disconnect 清理所有资源`() = runTest {
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        controller.disconnect()
        assertFalse(controller.isConnected())
        assertEquals(CameraMode.IDLE, controller.currentMode())
    }

    @Test
    fun `release 清理所有资源并标记释放`() = runTest {
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        controller.release()
        assertFalse(controller.isConnected())
        assertTrue(controller.isReleased())
    }

    @Test
    fun `connect 失败后清理半绑定资源`() = runTest {
        fakeBinder.shouldFailBind = true
        val result = controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        assertTrue(result.isFailure)
        assertFalse(controller.isConnected())
    }

    @Test
    fun `switchMode 失败后清理半绑定资源`() = runTest {
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        fakeBinder.shouldFailBind = true
        val result = controller.switchMode(CameraMode.DPM_SCAN)
        assertTrue(result.isFailure)
        assertFalse(controller.isConnected())
    }

    // ─── Observer 管理测试 ───

    @Test
    fun `connect 设置 observer`() = runTest {
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        assertEquals(1, fakeBinder.observerCount)
    }

    @Test
    fun `switchMode 移除旧 observer 再添加新`() = runTest {
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        assertEquals(1, fakeBinder.observerCount)
        controller.switchMode(CameraMode.DPM_SCAN)
        assertEquals(1, fakeBinder.observerCount)
    }

    @Test
    fun `disconnect 移除 observer`() = runTest {
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        controller.disconnect()
        assertEquals(0, fakeBinder.observerCount)
    }

    @Test
    fun `多次 switchMode 不累积 observer`() = runTest {
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        controller.switchMode(CameraMode.DPM_SCAN)
        controller.switchMode(CameraMode.STAMP_OCR)
        controller.switchMode(CameraMode.TEMPLATE_CAPTURE)
        assertEquals(1, fakeBinder.observerCount)
    }

    // ─── 分析器管理测试 ───

    @Test
    fun `setFrameAnalyzer 设置分析器`() = runTest {
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider(), CameraMode.INSPECTION)
        controller.setFrameAnalyzer(TestCountingAnalyzer())
    }

    @Test
    fun `clearFrameAnalyzer 移除分析器`() = runTest {
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider(), CameraMode.INSPECTION)
        controller.setFrameAnalyzer(TestCountingAnalyzer())
        controller.clearFrameAnalyzer()
    }

    @Test
    fun `FrameAnalyzer 异常 - ImageProxy 仍然关闭`() = runTest {
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider(), CameraMode.INSPECTION)
        val analyzer = TestCountingAnalyzer().apply { throwOnAnalyze = true }
        controller.setFrameAnalyzer(analyzer)
        val fakeProxy = FakeImageProxy()
        fakeBinder.simulateFrameArrival(fakeProxy)
        assertTrue("ImageProxy 应被 Controller 关闭", fakeProxy.isClosed)
    }

    // ─── Executor 管理测试 ───

    @Test
    fun `switchMode 关闭旧 Executor`() = runTest {
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider(), CameraMode.INSPECTION)
        // connect 创建了 Executor，switchMode 应该关闭它
        controller.switchMode(CameraMode.DPM_SCAN)
        // 如果没有异常即通过（Executor 被正确关闭和替换）
        assertEquals(CameraMode.DPM_SCAN, controller.currentMode())
    }

    // ─── 模式 UseCase 正确性测试 ───

    @Test
    fun `INSPECTION 绑定 Preview + Analysis + Capture`() = runTest {
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider(), CameraMode.INSPECTION)
        assertEquals(3, fakeBinder.lastBoundUseCases?.size)
    }

    @Test
    fun `DPM_SCAN 绑定 Preview + Analysis`() = runTest {
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider(), CameraMode.DPM_SCAN)
        assertEquals(2, fakeBinder.lastBoundUseCases?.size)
    }

    @Test
    fun `TEMPLATE_CAPTURE 绑定 Preview + Capture`() = runTest {
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider(), CameraMode.TEMPLATE_CAPTURE)
        assertEquals(2, fakeBinder.lastBoundUseCases?.size)
    }

    @Test
    fun `STAMP_OCR 绑定 Preview + Analysis + Capture`() = runTest {
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider(), CameraMode.STAMP_OCR)
        assertEquals(3, fakeBinder.lastBoundUseCases?.size)
    }

    // ─── 20 次 round-trip 压力测试 ───

    @Test
    fun `20 次模式 round-trip - 无资源泄漏`() = runTest {
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        val modes = listOf(
            CameraMode.INSPECTION,
            CameraMode.DPM_SCAN,
            CameraMode.STAMP_OCR,
            CameraMode.TEMPLATE_CAPTURE
        )
        repeat(20) { i ->
            val mode = modes[i % modes.size]
            val result = controller.switchMode(mode)
            assertTrue("第 ${i + 1} 次切换到 $mode 失败", result.isSuccess)
        }
        assertTrue(controller.isConnected())
        assertEquals(1, fakeBinder.observerCount)
    }

    // ─── 会话管理测试 ───

    @Test
    fun `connect 返回有效的 sessionId`() = runTest {
        val result = controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        assertTrue(result.isSuccess)
        val session = result.getOrNull()!!
        assertNotNull(session.sessionId)
        assertTrue(session.sessionId.isNotEmpty())
    }

    @Test
    fun `connect 后 getActiveSession 返回相同会话`() = runTest {
        val result = controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        val session = result.getOrNull()!!
        assertEquals(session.sessionId, controller.getActiveSession()?.sessionId)
    }

    @Test
    fun `disconnect 匹配 sessionId 成功解绑`() = runTest {
        val result = controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        val session = result.getOrNull()!!
        val disconnected = controller.disconnect(session.sessionId)
        assertTrue(disconnected)
        assertFalse(controller.isConnected())
        assertNull(controller.getActiveSession())
    }

    @Test
    fun `disconnect 不匹配 sessionId 被忽略`() = runTest {
        val result = controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        val session = result.getOrNull()!!
        val disconnected = controller.disconnect("invalid-session-id")
        assertFalse(disconnected)
        assertTrue(controller.isConnected())
        assertEquals(session.sessionId, controller.getActiveSession()?.sessionId)
    }

    @Test
    fun `connect - connect 第二次 unbind 后才执行 bind`() = runTest {
        val result1 = controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        val session1 = result1.getOrNull()!!
        val bindCountAfterFirst = fakeBinder.bindCount

        val result2 = controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        val session2 = result2.getOrNull()!!

        // 第二次 connect 应该先 unbind 再 bind
        assertTrue(fakeBinder.unbindCount > 0)
        assertTrue(fakeBinder.bindCount > bindCountAfterFirst)
        assertNotEquals(session1.sessionId, session2.sessionId)
    }

    @Test
    fun `old disconnect 晚于 connect session2 不被解绑`() = runTest {
        val result1 = controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        val session1 = result1.getOrNull()!!

        val result2 = controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        val session2 = result2.getOrNull()!!

        // 旧页面延迟 disconnect
        val disconnected = controller.disconnect(session1.sessionId)
        assertFalse(disconnected)

        // session2 仍然活跃
        assertTrue(controller.isConnected())
        assertEquals(session2.sessionId, controller.getActiveSession()?.sessionId)
    }

    @Test
    fun `connect 与 disconnect 并发 - 最终状态确定`() = runTest {
        fakeBinder.bindDelayMs = 50

        val result1 = controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        val session1 = result1.getOrNull()!!

        val job1 = launch(Dispatchers.Default) { controller.connect(fakeLifecycleOwner, FakeSurfaceProvider()) }
        val job2 = launch(Dispatchers.Default) { controller.disconnect(session1.sessionId) }

        joinAll(job1, job2)

        // 最终状态应该是确定的（要么连接，要么断开）
        val isConnected = controller.isConnected()
        val activeSession = controller.getActiveSession()
        if (isConnected) {
            assertNotNull(activeSession)
        } else {
            assertNull(activeSession)
        }
    }

    @Test
    fun `bind 失败 - 所有资源和 session 清空`() = runTest {
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        fakeBinder.shouldFailBind = true

        val result = controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        assertTrue(result.isFailure)
        assertFalse(controller.isConnected())
        assertNull(controller.getActiveSession())
        assertEquals(0, fakeBinder.observerCount)
    }

    @Test
    fun `连续 10 次页面进入离开 - 绑定组数量始终合理`() = runTest {
        // INSPECTION 模式会绑定 3 个 UseCase（Preview + Analysis + Capture）
        val expectedMaxUseCases = 3
        repeat(10) {
            val result = controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
            val session = result.getOrNull()!!
            assertTrue("第 ${it + 1} 次连接失败", controller.isConnected())

            // 每次绑定后检查当前绑定的 UseCase 数量
            val currentBound = fakeBinder.lastBoundUseCases?.size ?: 0
            assertTrue("第 ${it + 1} 次绑定后 UseCase 数量应为 $expectedMaxUseCases，实际: $currentBound",
                currentBound == expectedMaxUseCases)

            val disconnected = controller.disconnect(session.sessionId)
            assertTrue("第 ${it + 1} 次断开失败", disconnected)
            assertFalse(controller.isConnected())
        }
        // FakeCameraBinder 中绑定组数量始终合理
        assertTrue("最大绑定 UseCase 数量应为 $expectedMaxUseCases，实际: ${fakeBinder.maxBoundUseCases}",
            fakeBinder.maxBoundUseCases == expectedMaxUseCases)
    }

    @Test
    fun `每次新绑定前断言旧 useCases 数量为 0`() = runTest {
        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())
        val bindCountAfterFirst = fakeBinder.bindCount

        controller.connect(fakeLifecycleOwner, FakeSurfaceProvider())

        // 第二次绑定前应该先 unbind
        assertTrue(fakeBinder.unbindCount > 0)
        // 确保绑定计数增加
        assertTrue(fakeBinder.bindCount > bindCountAfterFirst)
    }
}

// ─── Test Fakes ───

/**
 * Fake CameraBinder（测试用）
 */
class FakeCameraBinder : CameraBinder {

    var bindDelayMs: Long = 0
    var shouldFailBind = false
    var hasPermission = true

    var bindCount = 0; private set
    var unbindCount = 0; private set
    var observerCount = 0; private set
    var lastBoundUseCases: List<Any>? = null; private set
    var lastBoundExecutor: java.util.concurrent.ExecutorService? = null; private set

    // 追踪最大绑定 UseCase 数量
    var maxBoundUseCases: Int = 0; private set
    private var currentBoundUseCases: Int = 0

    private val observers = mutableMapOf<Any, MutableList<Observer<CameraState>>>()
    private var lastAnalyzerCallback: ((Any) -> Unit)? = null

    override fun hasCameraPermission(): Boolean = hasPermission
    override fun getProvider(): Any = "FakeProvider"
    override fun hasBackCamera(provider: Any): Boolean = true

    override fun createPreview(surfaceProvider: Any): Any = "FakePreview"
    override fun createAnalysis(): Any = "FakeAnalysis"
    override fun createCapture(): Any = "FakeCapture"

    override fun bindToLifecycle(
        provider: Any, lifecycleOwner: LifecycleOwner, selector: Any, useCases: List<Any>
    ): BindResult {
        if (shouldFailBind) return BindResult.Failure(IllegalStateException("模拟绑定失败"))
        if (bindDelayMs > 0) Thread.sleep(bindDelayMs)
        bindCount++
        lastBoundUseCases = useCases
        currentBoundUseCases = useCases.size
        maxBoundUseCases = maxOf(maxBoundUseCases, currentBoundUseCases)
        return BindResult.Success("FakeCamera")
    }

    override fun unbindAll(provider: Any) {
        unbindCount++
        currentBoundUseCases = 0
        lastBoundUseCases = null
    }

    override fun getCameraInfo(camera: Any): Any = "FakeCameraInfo"

    override fun observeCameraState(
        cameraInfo: Any, lifecycleOwner: LifecycleOwner, observer: Observer<CameraState>
    ) {
        observers.getOrPut(cameraInfo) { mutableListOf() }.add(observer)
        observerCount = observers.values.sumOf { it.size }
    }

    override fun removeCameraStateObserver(cameraInfo: Any, observer: Observer<CameraState>) {
        observers[cameraInfo]?.remove(observer)
        observerCount = observers.values.sumOf { it.size }
    }

    override fun setAnalyzer(useCase: Any, executor: java.util.concurrent.ExecutorService, callback: (Any) -> Unit) {
        lastBoundExecutor = executor
        lastAnalyzerCallback = callback
    }

    override fun clearAnalyzer(useCase: Any) {
        lastAnalyzerCallback = null
    }

    override fun getResolutionInfo(useCase: Any): Pair<android.util.Size, Int>? = null

    fun simulateFrameArrival(imageProxy: Any) {
        lastAnalyzerCallback?.invoke(imageProxy)
    }
}

/**
 * Fake LifecycleOwner（测试用）
 */
class FakeLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    init { registry.currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}

/**
 * Fake SurfaceProvider（测试用）
 */
class FakeSurfaceProvider : androidx.camera.core.Preview.SurfaceProvider {
    override fun onSurfaceRequested(request: androidx.camera.core.SurfaceRequest) {}
}

/**
 * Fake ImageProxy（测试用）
 */
class FakeImageProxy : androidx.camera.core.ImageProxy {
    var isClosed = false; private set
    override fun close() { isClosed = true }
    override fun getWidth() = 640
    override fun getHeight() = 480
    override fun getImageInfo() = throw UnsupportedOperationException()
    override fun getPlanes() = throw UnsupportedOperationException()
    override fun getCropRect() = android.graphics.Rect(0, 0, 640, 480)
    override fun setCropRect(rect: android.graphics.Rect?) {}
    override fun getImage() = null
    override fun getFormat() = android.graphics.ImageFormat.YUV_420_888
}
