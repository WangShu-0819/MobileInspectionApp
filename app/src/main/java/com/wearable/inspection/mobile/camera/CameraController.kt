package com.wearable.inspection.mobile.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * 相机绑定操作的结果
 *
 * 用于测试可注入的相机绑定边界。
 */
sealed class BindResult {
    data class Success(val camera: Any) : BindResult()
    data class Failure(val error: Exception) : BindResult()
}

/**
 * 相机绑定协调接口
 *
 * 抽象 ProcessCameraProvider 的绑定操作，使 CameraController 的
 * 状态机和资源协调逻辑可以通过 Fake 实现进行测试。
 */
interface CameraBinder {
    /** 检查相机权限 */
    fun hasCameraPermission(): Boolean

    /** 获取 ProcessCameraProvider（阻塞调用） */
    fun getProvider(): Any?

    /** 检查是否有后置相机 */
    fun hasBackCamera(provider: Any): Boolean

    /** 创建 Preview UseCase */
    fun createPreview(surfaceProvider: Any): Any

    /** 创建 ImageAnalysis UseCase（needsAnalysis 模式） */
    fun createAnalysis(): Any

    /** 创建 ImageCapture UseCase（needsCapture 模式） */
    fun createCapture(): Any

    /**
     * 绑定 UseCase 到生命周期
     *
     * @return BindResult.Success(camera) 或 BindResult.Failure(exception)
     */
    fun bindToLifecycle(
        provider: Any,
        lifecycleOwner: LifecycleOwner,
        selector: Any,
        useCases: List<Any>
    ): BindResult

    /** 解绑所有 UseCase */
    fun unbindAll(provider: Any)

    /** 获取 CameraInfo（用于状态观察） */
    fun getCameraInfo(camera: Any): Any?

    /** 观察 CameraState */
    fun observeCameraState(
        cameraInfo: Any,
        lifecycleOwner: LifecycleOwner,
        observer: Observer<androidx.camera.core.CameraState>
    )

    /** 移除 CameraState 观察 */
    fun removeCameraStateObserver(
        cameraInfo: Any,
        observer: Observer<androidx.camera.core.CameraState>
    )

    /** 设置 ImageAnalysis 分析器回调 */
    fun setAnalyzer(useCase: Any, executor: java.util.concurrent.ExecutorService, callback: (Any) -> Unit)

    /** 清除 ImageAnalysis 分析器 */
    fun clearAnalyzer(useCase: Any)

    /** 关闭 UseCase（如关闭 ImageProxy） */
    fun closeUseCase(useCase: Any) {}

    /** 获取 UseCase 的分辨率信息 */
    fun getResolutionInfo(useCase: Any): Pair<android.util.Size, Int>? = null
}

/**
 * 真实 CameraBinder 实现（生产环境）
 */
class RealCameraBinder(private val context: Context) : CameraBinder {

    override fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun getProvider(): Any? {
        return try {
            ProcessCameraProvider.getInstance(context).get()
        } catch (e: Exception) {
            null
        }
    }

    override fun hasBackCamera(provider: Any): Boolean {
        return (provider as? ProcessCameraProvider)?.hasCamera(
            CameraSelector.DEFAULT_BACK_CAMERA
        ) == true
    }

    override fun bindToLifecycle(
        provider: Any,
        lifecycleOwner: LifecycleOwner,
        selector: Any,
        useCases: List<Any>
    ): BindResult {
        return try {
            val p = provider as ProcessCameraProvider
            val s = selector as CameraSelector
            @Suppress("UNCHECKED_CAST")
            val uc = useCases as List<androidx.camera.core.UseCase>
            val camera = p.bindToLifecycle(lifecycleOwner, s, *uc.toTypedArray())
            BindResult.Success(camera)
        } catch (e: Exception) {
            BindResult.Failure(e)
        }
    }

    override fun unbindAll(provider: Any) {
        (provider as? ProcessCameraProvider)?.unbindAll()
    }

    override fun getCameraInfo(camera: Any): Any? {
        return (camera as? Camera)?.cameraInfo
    }

    override fun observeCameraState(
        cameraInfo: Any,
        lifecycleOwner: LifecycleOwner,
        observer: Observer<androidx.camera.core.CameraState>
    ) {
        (cameraInfo as? androidx.camera.core.CameraInfo)?.cameraState?.observe(
            lifecycleOwner, observer
        )
    }

    override fun removeCameraStateObserver(
        cameraInfo: Any,
        observer: Observer<androidx.camera.core.CameraState>
    ) {
        (cameraInfo as? androidx.camera.core.CameraInfo)?.cameraState?.removeObserver(
            observer
        )
    }

    private val resolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .build()

    override fun createPreview(surfaceProvider: Any): Any {
        return Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build().also { it.setSurfaceProvider(surfaceProvider as Preview.SurfaceProvider) }
    }

    override fun createAnalysis(): Any {
        return ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(resolutionSelector)
            .build()
    }

    override fun createCapture(): Any {
        return ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(resolutionSelector)
            .build()
    }

    override fun setAnalyzer(
        useCase: Any,
        executor: java.util.concurrent.ExecutorService,
        callback: (Any) -> Unit
    ) {
        (useCase as? ImageAnalysis)?.setAnalyzer(executor) { imageProxy ->
            callback(imageProxy)
        }
    }

    override fun clearAnalyzer(useCase: Any) {
        (useCase as? ImageAnalysis)?.clearAnalyzer()
    }

    override fun getResolutionInfo(useCase: Any): Pair<android.util.Size, Int>? {
        val info = (useCase as? ImageAnalysis)?.resolutionInfo
            ?: (useCase as? ImageCapture)?.resolutionInfo
            ?: return null
        return Pair(info.resolution, info.rotationDegrees)
    }
}

/**
 * 相机会话标识
 *
 * 每次 connect 创建唯一会话，disconnect 必须携带匹配的 sessionId 才能解绑。
 * 防止旧页面延迟 disconnect 错误解绑新页面的相机。
 */
data class CameraSession(
    val sessionId: String = java.util.UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 共享相机控制器（单例）
 *
 * 设计约束：
 * - 所有资源变更（connect/switchMode/disconnect/release/setFrameAnalyzer/clearFrameAnalyzer）
 *   必须经过同一 Mutex 串行执行。
 * - 同一时刻只有一组 UseCase、一个分析器、一个 Executor。
 * - CameraController 拥有 ImageProxy，在 finally 中关闭；FrameAnalyzer 不负责 close。
 * - LifecycleOwner 使用 WeakReference 保存，失效时返回错误并清理。
 * - 每次重绑前显式 removeObserver，防止累积。
 * - connect 必须先 unbindAll 再绑定，防止 UseCase 累积。
 * - disconnect 必须携带 sessionId，只解绑当前活跃会话。
 */
class CameraController private constructor(
    private val context: Context,
    private val binder: CameraBinder
) {

    companion object {
        @Volatile
        private var INSTANCE: CameraController? = null

        fun getInstance(context: Context): CameraController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CameraController(
                    context.applicationContext,
                    RealCameraBinder(context.applicationContext)
                ).also { INSTANCE = it }
            }
        }

        /** 仅测试用：注入自定义 binder 创建实例 */
        fun createForTest(context: Context, binder: CameraBinder): CameraController {
            return CameraController(context, binder)
        }
    }

    // ─── CameraX 核心引用 ───
    private var cameraProvider: Any? = null
    private var previewUseCase: Any? = null
    private var analysisUseCase: Any? = null
    private var imageCapture: Any? = null
    private var cameraControl: CameraControl? = null
    private var currentCamera: Any? = null

    // ─── 分析资源 ───
    private var analysisExecutor: ExecutorService? = null
    private var frameAnalyzer: FrameAnalyzer? = null

    // ─── Observer 管理 ───
    private var cameraStateObserver: Observer<androidx.camera.core.CameraState>? = null
    private var observedCameraInfo: Any? = null

    // ─── 连接状态（使用 WeakReference 避免泄漏 LifecycleOwner）───
    private var lifecycleOwnerRef: WeakReference<LifecycleOwner>? = null
    private var surfaceProviderRef: WeakReference<Preview.SurfaceProvider>? = null
    private var currentMode: CameraMode = CameraMode.IDLE
    private var isReleased = false
    private var isConnected = false

    // ─── 会话管理 ───
    private var activeSession: CameraSession? = null

    // ─── 流信息 ───
    private var _streamResolution: android.util.Size? = null
    val streamResolution: android.util.Size? get() = _streamResolution

    private var _streamRotation: Int = 0
    val streamRotation: Int get() = _streamRotation

    // ─── 状态暴露 ───
    @Volatile
    private var _isActive = false
    val isActive: Boolean get() = _isActive

    private val _cameraStateFlow = MutableStateFlow<CameraStateType?>(null)
    val cameraStateFlow: StateFlow<CameraStateType?> = _cameraStateFlow.asStateFlow()

    @Volatile
    private var _error: String? = null
    val error: String? get() = _error

    val cameraInfo: Any? get() = observedCameraInfo

    // ─── 统一串行保护 ───
    private val mutex = Mutex()

    // ─── 选择器（测试可访问） ───
    internal val cameraSelector: Any = CameraSelector.DEFAULT_BACK_CAMERA

    /**
     * 连接相机
     *
     * 所有状态检查和资源变更在 mutex 内完成。
     * **关键**：必须先 unbindAll 再绑定，防止 UseCase 累积。
     *
     * @return 成功时返回 CameraSession，失败时返回异常
     */
    suspend fun connect(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        mode: CameraMode = CameraMode.INSPECTION
    ): Result<CameraSession> = mutex.withLock {
        // 前置检查
        if (isReleased) {
            return@withLock Result.failure(IllegalStateException("CameraController 已永久释放"))
        }

        if (!binder.hasCameraPermission()) {
            return@withLock Result.failure(SecurityException("未授予相机权限"))
        }

        try {
            // ─── 步骤 1-7：清理旧资源 ───
            // 0. 使在途拍照请求失效
            invalidateCaptureRequest()

            // 1. 标记旧连接进入关闭状态
            val oldSession = activeSession
            activeSession = null
            isConnected = false

            // 2. 移除旧 CameraState observer
            removeCameraStateObserver()

            // 3. clearAnalyzer
            analysisUseCase?.let { binder.clearAnalyzer(it) }

            // 4. stop 旧 FrameAnalyzer
            frameAnalyzer?.stop()
            frameAnalyzer = null

            // 5. shutdownNow 旧 Executor
            analysisExecutor?.shutdownNow()
            analysisExecutor = null

            // 6. 对旧 cameraProvider 执行 unbindAll()
            cameraProvider?.let { binder.unbindAll(it) }

            // 7. 清空旧 UseCase 和流信息引用
            previewUseCase = null
            analysisUseCase = null
            imageCapture = null
            currentCamera = null
            cameraControl = null
            lifecycleOwnerRef = null
            surfaceProviderRef = null
            currentMode = CameraMode.IDLE
            _streamResolution = null
            _streamRotation = 0
            _isActive = false
            _cameraStateFlow.value = null
            _error = null

            // ─── 步骤 8：创建并绑定新 UseCase ───

            // 获取 provider
            val provider = binder.getProvider()
                ?: return@withLock Result.failure(IllegalStateException("无法获取 CameraProvider"))

            if (!binder.hasBackCamera(provider)) {
                return@withLock Result.failure(IllegalStateException("未找到后置相机"))
            }

            // 创建新会话
            val newSession = CameraSession()

            // 构建 UseCase（通过 binder 创建，测试可注入）
            val preview = binder.createPreview(surfaceProvider)
            val analysis = if (mode.needsAnalysis) binder.createAnalysis() else null
            val capture = if (mode.needsCapture) binder.createCapture() else null

            // 绑定
            val useCases = listOfNotNull(preview, analysis, capture)
            val selector = cameraSelector
            val bindResult = binder.bindToLifecycle(provider, lifecycleOwner, selector, useCases)

            when (bindResult) {
                is BindResult.Failure -> {
                    // 绑定失败：完整回滚
                    performFullRollback(provider)
                    return@withLock Result.failure(bindResult.error)
                }
                is BindResult.Success -> {
                    // 连续自动对焦
                    try {
                        val cam = bindResult.camera as? Camera
                        if (cam != null) {
                            Camera2CameraControl.from(cam.cameraControl).addCaptureRequestOptions(
                                CaptureRequestOptions.Builder()
                                    .setCaptureRequestOption(
                                        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                                    )
                                    .build()
                            )
                        }
                    } catch (_: Exception) { /* 静默降级 */ }

                    // 保存引用
                    cameraProvider = provider
                    previewUseCase = preview
                    analysisUseCase = analysis
                    imageCapture = capture
                    currentCamera = bindResult.camera
                    lifecycleOwnerRef = WeakReference(lifecycleOwner)
                    surfaceProviderRef = WeakReference(surfaceProvider)
                    currentMode = mode
                    isConnected = true
                    activeSession = newSession
                    _isActive = false
                    _error = null

                    // 流信息
                    val resInfo = binder.getResolutionInfo(analysis ?: capture ?: preview)
                    if (resInfo != null) {
                        _streamResolution = resInfo.first
                        _streamRotation = resInfo.second
                    }

                    // 创建 Executor
                    if (analysis != null) {
                        analysisExecutor = Executors.newSingleThreadExecutor {
                            Thread(it, "camera-analysis-${mode.name.lowercase()}")
                        }
                    }

                    // 设置分析器回调（如果有）
                    if (analysis != null && frameAnalyzer != null) {
                        attachAnalyzerToUseCase(analysis, frameAnalyzer!!)
                    }

                    // 观察 Camera 状态
                    setupCameraStateObserver(bindResult.camera, lifecycleOwner)

                    Result.success(newSession)
                }
            }
        } catch (e: Exception) {
            _error = "相机启动失败"
            cleanupBoundResources()
            Result.failure(e)
        }
    }

    /**
     * 绑定失败时的完整回滚
     *
     * 确保 CameraX 不残留任何 UseCase。
     */
    private fun performFullRollback(provider: Any) {
        // 使在途拍照请求失效
        invalidateCaptureRequest()

        // unbindAll
        binder.unbindAll(provider)

        // removeObserver
        removeCameraStateObserver()

        // clearAnalyzer
        analysisUseCase?.let { binder.clearAnalyzer(it) }

        // shutdownNow Executor
        analysisExecutor?.shutdownNow()
        analysisExecutor = null

        // stop FrameAnalyzer
        frameAnalyzer?.stop()
        frameAnalyzer = null

        // 清空所有引用
        previewUseCase = null
        analysisUseCase = null
        imageCapture = null
        currentCamera = null
        cameraControl = null
        activeSession = null
        lifecycleOwnerRef = null
        surfaceProviderRef = null
        currentMode = CameraMode.IDLE
        _streamResolution = null
        _streamRotation = 0
        _isActive = false
        _cameraStateFlow.value = CameraStateType.ERROR
        _error = "相机启动失败"
        isConnected = false
    }

    /**
     * 切换模式
     *
     * mutex 内完成所有状态检查和资源变更。
     * 相同模式返回成功不做重绑。
     * 只操作当前活跃会话。
     */
    suspend fun switchMode(mode: CameraMode): Result<Unit> = mutex.withLock {
        if (isReleased) {
            return@withLock Result.failure(IllegalStateException("CameraController 已永久释放"))
        }
        if (!isConnected || activeSession == null) {
            return@withLock Result.failure(IllegalStateException("相机未连接"))
        }
        if (currentMode == mode) {
            return@withLock Result.success(Unit)
        }

        try {
            val provider = cameraProvider
                ?: return@withLock Result.failure(IllegalStateException("CameraProvider 丢失"))
            val owner = lifecycleOwnerRef?.get()
                ?: return@withLock Result.failure(IllegalStateException("LifecycleOwner 已失效"))

            // 0. 使在途拍照请求失效
            invalidateCaptureRequest()

            // 1. 停止旧分析器 + 断开 UseCase 回调
            frameAnalyzer?.stop()
            frameAnalyzer = null
            analysisUseCase?.let { binder.clearAnalyzer(it) }

            // 2. 关闭旧 Executor
            analysisExecutor?.shutdownNow()
            analysisExecutor = null

            // 3. 移除旧 observer
            removeCameraStateObserver()

            // 4. unbindAll
            binder.unbindAll(provider)

            // 5. 构建新 UseCase（通过 binder）
            val preview = previewUseCase ?: surfaceProviderRef?.get()?.let {
                binder.createPreview(it)
            } ?: return@withLock Result.failure(IllegalStateException("SurfaceProvider 已失效"))
            val analysis = if (mode.needsAnalysis) binder.createAnalysis() else null
            val capture = if (mode.needsCapture) binder.createCapture() else null

            // 6. 重绑
            val useCases = listOfNotNull(preview, analysis, capture)
            val selector = cameraSelector
            val bindResult = binder.bindToLifecycle(provider, owner, selector, useCases)

            when (bindResult) {
                is BindResult.Failure -> {
                    // 绑定失败：完整回滚
                    performFullRollback(provider)
                    return@withLock Result.failure(bindResult.error)
                }
                is BindResult.Success -> {
                    // 连续自动对焦
                    try {
                        val cam = bindResult.camera as? Camera
                        if (cam != null) {
                            Camera2CameraControl.from(cam.cameraControl).addCaptureRequestOptions(
                                CaptureRequestOptions.Builder()
                                    .setCaptureRequestOption(
                                        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                                    )
                                    .build()
                            )
                        }
                    } catch (_: Exception) { /* 静默降级 */ }

                    // 保存新引用
                    previewUseCase = preview
                    analysisUseCase = analysis
                    imageCapture = capture
                    currentCamera = bindResult.camera
                    currentMode = mode

                    // 流信息
                    val resInfo = binder.getResolutionInfo(analysis ?: capture ?: preview)
                    if (resInfo != null) {
                        _streamResolution = resInfo.first
                        _streamRotation = resInfo.second
                    }

                    // 新 Executor
                    if (analysis != null) {
                        analysisExecutor = Executors.newSingleThreadExecutor {
                            Thread(it, "camera-analysis-${mode.name.lowercase()}")
                        }
                    }

                    // 设置分析器回调
                    if (analysis != null && frameAnalyzer != null) {
                        attachAnalyzerToUseCase(analysis, frameAnalyzer!!)
                    }

                    // 新 observer
                    setupCameraStateObserver(bindResult.camera, owner)

                    Result.success(Unit)
                }
            }
        } catch (e: Exception) {
            _error = "模式切换失败"
            _cameraStateFlow.value = CameraStateType.ERROR
            cleanupBoundResources()
            Result.failure(e)
        }
    }

    /**
     * 断开连接（页面暂时离开）
     *
     * suspend 操作，mutex 内完成所有清理。
     * 不标记 isReleased，可再次 connect。
     *
     * **关键**：必须携带 sessionId，只解绑当前活跃会话。
     * 旧页面延迟 disconnect 遇到新 session 时只能忽略。
     *
     * @param sessionId 由 connect 返回的会话标识
     * @return true 表示成功解绑，false 表示 sessionId 不匹配（被忽略）
     */
    suspend fun disconnect(sessionId: String): Boolean = mutex.withLock {
        // 检查 sessionId 是否匹配当前活跃会话
        if (activeSession?.sessionId != sessionId) {
            // sessionId 不匹配，说明是旧页面的延迟 disconnect，忽略
            return@withLock false
        }

        releaseInternal()
        return@withLock true
    }

    /**
     * 无 sessionId 的 disconnect（仅用于测试或紧急清理）
     *
     * 生产代码应使用 disconnect(sessionId)。
     */
    suspend fun disconnect(): Unit = mutex.withLock {
        releaseInternal()
    }

    /**
     * 永久释放
     *
     * suspend 操作，mutex 内完成所有清理。
     * 标记 isReleased = true，之后 connect/switchMode 返回失败。
     */
    suspend fun release(): Unit = mutex.withLock {
        isReleased = true
        releaseInternal()
    }

    /**
     * 获取当前活跃会话
     *
     * 用于 CameraPreview 保存 sessionId。
     */
    fun getActiveSession(): CameraSession? = activeSession

    /**
     * 设置帧分析器
     *
     * mutex 内完成替换。
     * CameraController 拥有 ImageProxy，在 finally 中关闭。
     * FrameAnalyzer.analyze() 不负责 close。
     */
    suspend fun setFrameAnalyzer(analyzer: FrameAnalyzer): Unit = mutex.withLock {
        // 停止旧分析器
        frameAnalyzer?.stop()
        frameAnalyzer = analyzer

        // 设置回调
        val analysis = analysisUseCase
        if (analysis != null && analysisExecutor != null) {
            attachAnalyzerToUseCase(analysis, analyzer)
        }
    }

    /**
     * 移除帧分析器
     *
     * mutex 内完成清理。
     */
    suspend fun clearFrameAnalyzer(): Unit = mutex.withLock {
        frameAnalyzer?.stop()
        frameAnalyzer = null
        analysisUseCase?.let { binder.clearAnalyzer(it) }
    }

    /**
     * 拍照结果
     */
    data class CaptureResult(
        val filePath: String,
        val sizeBytes: Long,
        val width: Int,
        val height: Int,
        val orientation: Int,
        val capturedAt: Long
    )

    // ─── 拍照并发保护 ───
    // captureRequestId: 当前活跃的拍照请求标识
    // disconnect/switchMode 时递增使旧请求失效
    @Volatile
    private var captureRequestId: Long = 0L

    @Volatile
    private var isCapturing = false

    /**
     * 使当前拍照请求失效
     *
     * 在 disconnect/switchMode/release 时调用，使在途回调忽略结果。
     * 必须在 mutex 内调用。
     */
    private fun invalidateCaptureRequest() {
        captureRequestId++
        isCapturing = false
    }

    /**
     * 会话安全拍照
     *
     * 设计约束：
     * 1. mutex 内完成状态检查和 token 创建，释放 mutex 后调用 takePicture
     * 2. 回调完成时重新进入 mutex 核对 token 和 sessionId
     * 3. disconnect/switchMode 使旧 token 失效
     * 4. 协程取消、旧会话回调、错误路径删除临时文件
     * 5. 所有路径恢复 isCapturing 且只恢复对应请求
     *
     * @param sessionId 当前活跃会话 ID
     * @param outputFile 目标文件（应为临时文件）
     * @return 成功时返回文件，失败时返回异常
     */
    suspend fun takePhoto(sessionId: String, outputFile: File): Result<File> {
        // ─── 阶段 1：mutex 内完成状态检查和 token 创建 ───
        val requestId = mutex.withLock {
            // 前置检查
            if (isReleased) {
                return@withLock -1L // 特殊值表示立即失败
            }

            if (activeSession?.sessionId != sessionId) {
                return@withLock -1L
            }

            if (!isConnected) {
                return@withLock -1L
            }

            if (_cameraStateFlow.value != CameraStateType.OPEN) {
                return@withLock -1L
            }

            if (!currentMode.needsCapture) {
                return@withLock -1L
            }

            if (imageCapture == null) {
                return@withLock -1L
            }

            // 并发保护：只允许一个拍照请求
            if (isCapturing) {
                return@withLock -1L
            }

            // 创建唯一 request token
            val requestId = ++captureRequestId
            isCapturing = true
            requestId
        }

        // 检查是否立即失败
        if (requestId <= 0L) {
            return Result.failure(IllegalStateException("拍照前置条件不满足"))
        }

        val capture = imageCapture as? ImageCapture
            ?: run {
                isCapturing = false
                return Result.failure(IllegalStateException("ImageCapture 未初始化"))
            }

        // ─── 阶段 2：mutex 外执行异步拍照 ───
        return try {
            outputFile.parentFile?.mkdirs()

            val result = suspendCancellableCoroutine<File?> { cont ->
                val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
                capture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            if (cont.isActive) {
                                // ─── 阶段 3：回调时重新验证 ───
                                // 检查 request token 和 sessionId
                                val valid = captureRequestId == requestId &&
                                        activeSession?.sessionId == sessionId &&
                                        outputFile.exists() &&
                                        outputFile.length() > 0L

                                if (valid) {
                                    cont.resume(outputFile)
                                } else {
                                    // session 已过期、token 失效或文件无效，清理
                                    outputFile.delete()
                                    cont.resume(null)
                                }
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            if (cont.isActive) {
                                // 清理临时文件
                                outputFile.delete()
                                cont.resume(null)
                            }
                        }
                    }
                )

                // 协程取消时清理
                cont.invokeOnCancellation {
                    outputFile.delete()
                }
            }

            if (result != null) {
                Result.success(result)
            } else {
                Result.failure(IllegalStateException("拍照失败或会话已过期"))
            }
        } catch (e: Exception) {
            outputFile.delete()
            Result.failure(e)
        } finally {
            // 只恢复对应请求的 isCapturing
            if (captureRequestId == requestId) {
                isCapturing = false
            }
        }
    }

    /**
     * 设置闪光灯
     */
    fun setTorch(enabled: Boolean): Boolean {
        val control = cameraControl ?: return false
        return runCatching { control.enableTorch(enabled) }.isSuccess
    }

    /**
     * 设置变焦
     */
    fun setZoom(ratio: Float): Boolean {
        val control = cameraControl ?: return false
        val clamped = ratio.coerceIn(1f, maxZoom())
        return runCatching { control.setZoomRatio(clamped) }.isSuccess
    }

    /** 最大变焦倍率 */
    fun maxZoom(): Float = cameraControl?.let {
        (it as? CameraControl)?.let { ctrl ->
            (currentCamera as? Camera)?.cameraInfo?.zoomState?.value?.maxZoomRatio
        }
    } ?: 1f

    /** 当前模式（mutex 外读取，可能与锁内状态有短暂不一致） */
    fun currentMode(): CameraMode = currentMode

    /** 是否已永久释放 */
    fun isReleased(): Boolean = isReleased

    /** 是否已连接 */
    fun isConnected(): Boolean = isConnected && !isReleased

    // ─── 内部方法（必须在 mutex 内调用） ───

    /**
     * 将分析器回调附加到 ImageAnalysis UseCase
     *
     * CameraController 拥有 ImageProxy，在 finally 中关闭。
     * FrameAnalyzer.analyze() 不负责 close。
     */
    private fun attachAnalyzerToUseCase(analysis: Any, analyzer: FrameAnalyzer) {
        val executor = analysisExecutor ?: return
        binder.setAnalyzer(analysis, executor) { imageProxy ->
            val proxy = imageProxy as? androidx.camera.core.ImageProxy ?: return@setAnalyzer
            try {
                analyzer.analyze(proxy)
            } catch (e: Exception) {
                android.util.Log.e("CameraController", "分析器异常", e)
            } finally {
                // CameraController 拥有 ImageProxy，始终关闭
                proxy.close()
            }
        }
    }


    /**
     * 设置 Camera 状态观察
     *
     * 先移除旧 observer，再添加新的。保存引用以便后续移除。
     */
    private fun setupCameraStateObserver(camera: Any, lifecycleOwner: LifecycleOwner) {
        // 移除旧 observer
        removeCameraStateObserver()

        val cameraInfo = binder.getCameraInfo(camera) ?: return
        val observer = Observer<androidx.camera.core.CameraState> { state ->
            val stateType = when (state.type) {
                androidx.camera.core.CameraState.Type.PENDING_OPEN -> CameraStateType.PENDING_OPEN
                androidx.camera.core.CameraState.Type.OPEN -> CameraStateType.OPEN
                androidx.camera.core.CameraState.Type.CLOSING -> CameraStateType.CLOSED
                androidx.camera.core.CameraState.Type.CLOSED -> CameraStateType.CLOSED
                else -> null
            }
            _isActive = state.type == androidx.camera.core.CameraState.Type.OPEN
            _cameraStateFlow.value = stateType
            state.error?.let {
                _error = "Camera error: ${it.code}"
                _cameraStateFlow.value = CameraStateType.ERROR
            }
        }

        binder.observeCameraState(cameraInfo, lifecycleOwner, observer)
        cameraStateObserver = observer
        observedCameraInfo = cameraInfo
    }

    /**
     * 移除 Camera 状态观察
     */
    private fun removeCameraStateObserver() {
        val observer = cameraStateObserver
        val info = observedCameraInfo
        if (observer != null && info != null) {
            try {
                binder.removeCameraStateObserver(info, observer)
            } catch (_: Exception) {
                // 忽略移除失败（可能 Camera 已销毁）
            }
        }
        cameraStateObserver = null
        observedCameraInfo = null
    }

    /**
     * 清理已绑定的资源（mutex 内调用）
     *
     * 包括：停止分析器、关闭 Executor、移除 observer、清空所有引用。
     * 不调用 provider.unbindAll()，由调用方决定是否需要。
     */
    private fun cleanupBoundResources() {
        // 使在途拍照请求失效
        invalidateCaptureRequest()

        // 停止分析器
        frameAnalyzer?.stop()
        frameAnalyzer = null

        // 关闭 Executor
        analysisExecutor?.shutdownNow()
        analysisExecutor = null

        // 断开 UseCase 回调
        analysisUseCase?.let { binder.clearAnalyzer(it) }

        // 移除 observer
        removeCameraStateObserver()

        // 清空引用
        analysisUseCase = null
        imageCapture = null
        previewUseCase = null
        cameraControl = null
        currentCamera = null
        activeSession = null
        _streamResolution = null
        _streamRotation = 0
        _isActive = false
        _cameraStateFlow.value = null
        _error = null
        isConnected = false
    }

    /**
     * 内部释放逻辑（mutex 内调用）
     */
    private fun releaseInternal() {
        // 使在途拍照请求失效
        invalidateCaptureRequest()

        cameraProvider?.let { binder.unbindAll(it) }
        cleanupBoundResources()
        cameraProvider = null
        lifecycleOwnerRef = null
        currentMode = CameraMode.IDLE
    }
}
