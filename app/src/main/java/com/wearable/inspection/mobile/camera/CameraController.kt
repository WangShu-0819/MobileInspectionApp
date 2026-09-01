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
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.io.File

/**
 * 相机状态类型（对应 CameraX CameraState.Type）
 */
enum class CameraStateType {
    PENDING_OPEN,
    OPEN,
    CLOSED,
    ERROR
}

/**
 * 共享相机控制器（单例）
 *
 * 职责：
 * 1. 统一管理 CameraX 生命周期
 * 2. 提供 Preview Surface 供给 LiveInspectionScreen
 * 3. 提供 ImageCapture 拍照能力
 * 4. 提供 ImageAnalysis 分析帧接口
 * 5. 切换相机模式（INSPECTION / DPM_SCAN / STAMP_OCR / TEMPLATE_CAPTURE）
 *
 * 设计约束：
 * - 同一时刻只能有一个 CameraX 绑定
 * - 所有 ImageProxy 必须在所有路径关闭
 * - 不持有 Bitmap/Mat/ImageProxy 长期引用
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
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: java.util.concurrent.ExecutorService? = null
    private var imageCapture: ImageCapture? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var cameraControl: CameraControl? = null
    private var _cameraInfo: androidx.camera.core.CameraInfo? = null

    private var currentLifecycleOwner: LifecycleOwner? = null
    private var frameAnalyzer: ((ImageProxy) -> Unit)? = null
    private var cameraMode: CameraMode = CameraMode.IDLE

    /** 实际流分辨率（从绑定后获取） */
    private var _streamResolution: android.util.Size? = null
    val streamResolution: android.util.Size? get() = _streamResolution

    /** 实际流旋转角度（从 CameraInfo 获取） */
    private var _streamRotation: Int = 0
    val streamRotation: Int get() = _streamRotation

    /** 相机是否活跃（仅 OPEN 状态为 true） */
    @Volatile
    private var _isActive = false
    val isActive: Boolean get() = _isActive

    /** 相机状态流（供 UI 观察 OPEN/CLOSED/ERROR 等状态） */
    private val _cameraStateFlow = MutableStateFlow<CameraStateType?>(null)
    val cameraStateFlow: StateFlow<CameraStateType?> = _cameraStateFlow.asStateFlow()

    /** 错误信息 */
    @Volatile
    private var _error: String? = null
    val error: String? get() = _error

    /** 相机信息（只读） */
    val cameraInfo: androidx.camera.core.CameraInfo? get() = _cameraInfo

    /**
     * 连接相机
     */
    suspend fun connect(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        mode: CameraMode = CameraMode.INSPECTION
    ): Result<Unit> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Result.failure(SecurityException("未授予相机权限"))
        }

        return try {
            val provider = ProcessCameraProvider.getInstance(context).get()
            val selector = CameraSelector.DEFAULT_BACK_CAMERA

            if (!provider.hasCamera(selector)) {
                return Result.failure(IllegalStateException("未找到后置相机"))
            }

            // 分析线程池
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor {
                Thread(it, "camera-analysis-${mode.name.lowercase()}")
            }

            // 4:3 画幅统一选择器
            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                    AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                )
                .build()

            // Preview（4:3）
            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build().also {
                    it.setSurfaceProvider(surfaceProvider)
                }

            // ImageAnalysis（KEEP_ONLY_LATEST + YUV_420_888 + 4:3）
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setResolutionSelector(resolutionSelector)
                .build().also {
                    frameAnalyzer?.let { analyzer ->
                        it.setAnalyzer(executor, analyzer)
                    }
                }

            // ImageCapture（最小延迟模式 + 4:3）
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(resolutionSelector)
                .build()

            // 绑定
            provider.unbindAll()
            val camera = try {
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis, capture)
            } catch (e: Exception) {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            }

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
            } catch (e: Exception) {
                // 静默降级
            }

            // 保存引用
            cameraProvider = provider
            analysisExecutor = executor
            imageCapture = capture
            previewUseCase = preview
            analysisUseCase = analysis
            cameraControl = camera.cameraControl
            _cameraInfo = camera.cameraInfo

            // 获取实际流分辨率和旋转角度（从 UseCase 的 ResolutionInfo 获取）
            val analysisResInfo = analysis.resolutionInfo
            val captureResInfo = capture.resolutionInfo
            val resInfo = analysisResInfo ?: captureResInfo
            if (resInfo != null) {
                _streamResolution = resInfo.resolution
                _streamRotation = resInfo.rotationDegrees
            }
            // 如果获取失败，保持 null（不降级，UI 显示等待状态）

            currentLifecycleOwner = lifecycleOwner
            cameraMode = mode
            _isActive = false  // 初始化为 false，等待 CameraState.OPEN
            _error = null

            // 监控 Camera 状态，只有 OPEN 才认为就绪
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

            Result.success(Unit)
        } catch (e: Exception) {
            _error = e.message ?: "相机连接失败"
            Result.failure(e)
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        cameraProvider?.unbindAll()
        analysisExecutor?.shutdownNow()
        cameraProvider = null
        analysisExecutor = null
        imageCapture = null
        previewUseCase = null
        analysisUseCase = null
        cameraControl = null
        _cameraInfo = null
        _streamResolution = null
        _streamRotation = 0
        currentLifecycleOwner = null
        _isActive = false
        _cameraStateFlow.value = null
        _error = null
    }

    /**
     * 释放资源（不 disconnect，保持会话）
     */
    fun release() {
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
        frameAnalyzer = null
    }

    /**
     * 设置分析帧消费者
     */
    fun setFrameAnalyzer(analyzer: (ImageProxy) -> Unit) {
        frameAnalyzer = analyzer
        analysisUseCase?.setAnalyzer(
            analysisExecutor ?: java.util.concurrent.Executors.newSingleThreadExecutor(),
            analyzer
        )
    }

    /**
     * 附加 SurfaceProvider 到 Preview
     */
    fun attachSurfaceProvider(surfaceProvider: Preview.SurfaceProvider) {
        previewUseCase?.setSurfaceProvider(surfaceProvider)
    }

    /**
     * 拍照
     */
    suspend fun takePhoto(outputFile: File): Result<File> {
        val capture = imageCapture ?: return Result.failure(
            IllegalStateException("ImageCapture 未初始化")
        )

        outputFile.parentFile?.mkdirs()

        return suspendCancellableCoroutine { cont ->
            val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val size = outputFile.length()
                        if (cont.isActive) {
                            if (outputFile.exists() && size > 0L) {
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
     * 切换模式
     */
    fun switchMode(mode: CameraMode) {
        if (cameraMode == mode) return
        cameraMode = mode
        // TODO: 根据模式调整相机参数
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
}

/**
 * 相机模式枚举
 */
enum class CameraMode {
    IDLE,
    INSPECTION,
    DPM_SCAN,
    STAMP_OCR,
    TEMPLATE_CAPTURE
}
