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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * 共享相机控制器（单例）
 *
 * 职责：
 * 1. 统一管理 CameraX 生命周期
 * 2. 提供 Preview Surface 供给 LiveInspectionScreen
 * 3. 提供 ImageCapture 拍照能力
 * 4. 提供 ImageAnalysis 分析帧接口
 * 5. 切换相机模式（IDLE / INSPECTION / DPM_SCAN / STAMP_OCR / TEMPLATE_CAPTURE）
 *
 * 设计约束：
 * - 同一时刻只能有一个 CameraX 绑定（一组 UseCase + 一个分析器 + 一个 Executor）
 * - switchMode() 通过 Mutex 串行执行，停止旧资源后重绑新模式
 * - disconnect() 用于页面暂时离开，可再次 connect
 * - release() 用于永久释放，之后不可复用
 * - 不持有 Activity/LifecycleOwner/PreviewView 强引用
 * - 所有 ImageProxy 的关闭由 FrameAnalyzer 实现负责
 */
class CameraController private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: CameraController? = null

        fun getInstance(context: Context): CameraController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CameraController(context.applicationContext).also { INSTANCE = it }
            }
        }

        /** 反射重置单例（仅测试用） */
        @Volatile
        var testResetEnabled = false

        fun resetForTest() {
            if (!testResetEnabled) throw IllegalStateException("testResetEnabled 未开启")
            INSTANCE?.releaseInternal()
            INSTANCE = null
        }
    }

    // ─── CameraX 核心引用 ───
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var cameraControl: CameraControl? = null
    private var _cameraInfo: androidx.camera.core.CameraInfo? = null
    private var currentCamera: Camera? = null

    // ─── 分析资源 ───
    private var analysisExecutor: ExecutorService? = null
    private var frameAnalyzer: FrameAnalyzer? = null

    // ─── 连接状态 ───
    private var currentLifecycleOwner: LifecycleOwner? = null
    private var currentMode: CameraMode = CameraMode.IDLE
    private var isReleased = false

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

    val cameraInfo: androidx.camera.core.CameraInfo? get() = _cameraInfo

    // ─── 串行保护 ───
    private val switchMutex = Mutex()

    // ─── 4:3 统一选择器 ───
    private val resolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .build()

    /**
     * 连接相机
     *
     * 前置条件：未释放、有权限。
     * 如果已连接，先断开再重连（防重复 connect）。
     * connect 成功只表示 BOUND，不触发 CameraState.OPEN。
     */
    suspend fun connect(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        mode: CameraMode = CameraMode.INSPECTION
    ): Result<Unit> {
        if (isReleased) {
            return Result.failure(IllegalStateException("CameraController 已永久释放"))
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Result.failure(SecurityException("未授予相机权限"))
        }

        return switchMutex.withLock {
            try {
                // 防重复 connect：清理旧资源并 unbind
                val oldProvider = cameraProvider
                cleanupBoundResources()
                oldProvider?.unbindAll()

                val provider = ProcessCameraProvider.getInstance(context).get()
                val selector = CameraSelector.DEFAULT_BACK_CAMERA

                if (!provider.hasCamera(selector)) {
                    return@withLock Result.failure(IllegalStateException("未找到后置相机"))
                }

                // 确保干净状态
                provider.unbindAll()

                // 构建 Preview
                val preview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .build().also {
                        it.setSurfaceProvider(surfaceProvider)
                    }

                // 根据模式构建 UseCase
                val analysis = if (mode.needsAnalysis) {
                    buildAnalysisUseCase()
                } else null

                val capture = if (mode.needsCapture) {
                    buildCaptureUseCase()
                } else null

                // 绑定
                val useCases = listOfNotNull(preview, analysis, capture)
                val camera = provider.bindToLifecycle(
                    lifecycleOwner, selector, *useCases.toTypedArray()
                )

                // 连续自动对焦
                try {
                    Camera2CameraControl.from(camera.cameraControl).addCaptureRequestOptions(
                        CaptureRequestOptions.Builder()
                            .setCaptureRequestOption(
                                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                            )
                            .build()
                    )
                } catch (_: Exception) { /* 静默降级 */ }

                // 保存引用
                cameraProvider = provider
                previewUseCase = preview
                analysisUseCase = analysis
                imageCapture = capture
                cameraControl = camera.cameraControl
                _cameraInfo = camera.cameraInfo
                currentCamera = camera
                currentLifecycleOwner = lifecycleOwner
                currentMode = mode
                _isActive = false  // 等待 CameraState.OPEN
                _error = null

                // 获取流信息
                val resInfo = analysis?.resolutionInfo ?: capture?.resolutionInfo
                if (resInfo != null) {
                    _streamResolution = resInfo.resolution
                    _streamRotation = resInfo.rotationDegrees
                }

                // 创建分析 Executor（如果有分析 UseCase）
                if (analysis != null) {
                    analysisExecutor = Executors.newSingleThreadExecutor {
                        Thread(it, "camera-analysis-${mode.name.lowercase()}")
                    }
                }

                // 监控 Camera 状态
                observeCameraState(camera, lifecycleOwner)

                Result.success(Unit)
            } catch (e: Exception) {
                _error = e.message ?: "相机连接失败"
                cleanupBoundResources()
                Result.failure(e)
            }
        }
    }

    /**
     * 切换模式
     *
     * 串行执行：停止旧分析器 → 关闭旧 Executor → unbindAll → 构建新 UseCase → 重绑。
     * 相同模式返回成功不做重绑。
     */
    suspend fun switchMode(mode: CameraMode): Result<Unit> {
        if (isReleased) {
            return Result.failure(IllegalStateException("CameraController 已永久释放"))
        }
        if (currentMode == mode) {
            return Result.success(Unit)
        }

        return switchMutex.withLock {
            try {
                val provider = cameraProvider
                val owner = currentLifecycleOwner
                if (provider == null || owner == null) {
                    return@withLock Result.failure(IllegalStateException("相机未连接"))
                }

                // 1. 停止旧分析器
                frameAnalyzer?.stop()
                frameAnalyzer = null

                // 2. 关闭旧 Executor
                analysisExecutor?.shutdownNow()
                analysisExecutor = null

                // 3. 根据新模式构建 UseCase
                val preview = previewUseCase ?: run {
                    // 重建 Preview（理论上不应发生）
                    Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .build()
                }

                val analysis = if (mode.needsAnalysis) {
                    buildAnalysisUseCase()
                } else null

                val capture = if (mode.needsCapture) {
                    buildCaptureUseCase()
                } else null

                // 4. 重绑
                provider.unbindAll()
                val useCases = listOfNotNull(preview, analysis, capture)
                val camera = provider.bindToLifecycle(
                    owner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray()
                )

                // 连续自动对焦
                try {
                    Camera2CameraControl.from(camera.cameraControl).addCaptureRequestOptions(
                        CaptureRequestOptions.Builder()
                            .setCaptureRequestOption(
                                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                            )
                            .build()
                    )
                } catch (_: Exception) { /* 静默降级 */ }

                // 5. 保存新引用
                previewUseCase = preview
                analysisUseCase = analysis
                imageCapture = capture
                cameraControl = camera.cameraControl
                _cameraInfo = camera.cameraInfo
                currentCamera = camera
                currentMode = mode

                // 获取流信息
                val resInfo = analysis?.resolutionInfo ?: capture?.resolutionInfo
                if (resInfo != null) {
                    _streamResolution = resInfo.resolution
                    _streamRotation = resInfo.rotationDegrees
                }

                // 创建新 Executor
                if (analysis != null) {
                    analysisExecutor = Executors.newSingleThreadExecutor {
                        Thread(it, "camera-analysis-${mode.name.lowercase()}")
                    }
                }

                // 监控 Camera 状态
                observeCameraState(camera, owner)

                Result.success(Unit)
            } catch (e: Exception) {
                _error = e.message ?: "模式切换失败"
                _cameraStateFlow.value = CameraStateType.ERROR
                Result.failure(e)
            }
        }
    }

    /**
     * 断开连接（页面暂时离开）
     *
     * 停止分析器、关闭 Executor、unbindAll、清空引用。
     * 不标记 isReleased，可再次 connect。
     */
    fun disconnect() {
        releaseInternal()
    }

    /**
     * 永久释放
     *
     * 与 disconnect 相同的清理逻辑，但标记 isReleased = true。
     * 之后 connect/switchMode 返回失败。
     */
    fun release() {
        isReleased = true
        releaseInternal()
    }

    /**
     * 设置帧分析器
     *
     * 替换当前分析器（如果有旧的，先调用 stop()）。
     * 必须在 connect/switchMode 之后调用，且模式需要 Analysis。
     */
    fun setFrameAnalyzer(analyzer: FrameAnalyzer) {
        // 停止旧分析器
        frameAnalyzer?.stop()

        frameAnalyzer = analyzer

        // 如果 Analysis UseCase 已存在，设置实际的 analyzer 回调
        val analysis = analysisUseCase
        val executor = analysisExecutor
        if (analysis != null && executor != null) {
            analysis.setAnalyzer(executor) { imageProxy ->
                try {
                    analyzer.analyze(imageProxy)
                } catch (e: Exception) {
                    // 分析器异常不应崩溃相机，ImageProxy 关闭由分析器负责
                    android.util.Log.e("CameraController", "分析器异常", e)
                }
            }
        }
    }

    /**
     * 移除帧分析器
     */
    fun clearFrameAnalyzer() {
        frameAnalyzer?.stop()
        frameAnalyzer = null
        analysisUseCase?.clearAnalyzer()
    }

    /**
     * 拍照
     */
    suspend fun takePhoto(outputFile: File): Result<File> {
        val capture = imageCapture ?: return Result.failure(
            IllegalStateException("ImageCapture 未初始化（当前模式: $currentMode）")
        )

        outputFile.parentFile?.mkdirs()

        return suspendCancellableCoroutine { cont ->
            val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (cont.isActive) {
                            if (outputFile.exists() && outputFile.length() > 0L) {
                                cont.resume(Result.success(outputFile))
                            } else {
                                cont.resume(Result.failure(IllegalStateException("拍照文件为空")))
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (cont.isActive) {
                            cont.resume(Result.failure(exception))
                        }
                    }
                }
            )
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
    fun maxZoom(): Float = _cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f

    /** 当前模式 */
    fun currentMode(): CameraMode = currentMode

    /** 是否已永久释放 */
    fun isReleased(): Boolean = isReleased

    /** 是否已连接（有 CameraProvider） */
    fun isConnected(): Boolean = cameraProvider != null && !isReleased

    // ─── 内部方法 ───

    /**
     * 构建 ImageAnalysis UseCase
     */
    private fun buildAnalysisUseCase(): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(resolutionSelector)
            .build()
    }

    /**
     * 构建 ImageCapture UseCase
     */
    private fun buildCaptureUseCase(): ImageCapture {
        return ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(resolutionSelector)
            .build()
    }

    /**
     * 监控 Camera 状态
     *
     * 每次调用前先移除旧 observer（通过 CameraX 的 observe 自动管理）。
     */
    private fun observeCameraState(camera: Camera, lifecycleOwner: LifecycleOwner) {
        camera.cameraInfo.cameraState.observe(lifecycleOwner) { state ->
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
    }

    /**
     * 清理已绑定的资源（不 unbindAll，仅清空引用）
     */
    private fun cleanupBoundResources() {
        frameAnalyzer?.stop()
        frameAnalyzer = null
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
        analysisUseCase?.clearAnalyzer()
        analysisUseCase = null
        imageCapture = null
        previewUseCase = null
        cameraControl = null
        _cameraInfo = null
        currentCamera = null
        _streamResolution = null
        _streamRotation = 0
        _isActive = false
        _cameraStateFlow.value = null
        _error = null
    }

    /**
     * 内部释放逻辑
     */
    private fun releaseInternal() {
        cameraProvider?.unbindAll()
        cleanupBoundResources()
        cameraProvider = null
        currentLifecycleOwner = null
        currentMode = CameraMode.IDLE
    }
}
