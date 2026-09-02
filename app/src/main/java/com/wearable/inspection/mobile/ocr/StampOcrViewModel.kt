package com.wearable.inspection.mobile.ocr

import android.app.Application
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wearable.inspection.mobile.camera.CameraController
import com.wearable.inspection.mobile.camera.CameraMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 钢印 OCR ViewModel — 管理拍照、OCR 处理和结果状态。
 *
 * 状态机：IDLE → CAPTURING → PROCESSING → EXACT / NEED_CONFIRMATION / FAILED / ERROR
 *
 * 设计约束：
 * - 不自行 connect/switchMode，由 StampOcrScreen 的 CameraPreview 以 STAMP_OCR 模式连接
 * - startOcr 接收已连接的 CameraController 和 sessionId
 * - takePhoto 使用 CameraController 的 ImageCapture
 * - OCR 分析在 Dispatchers.Default 上执行（同步 CPU + ML Kit await）
 * - 低置信结果进入 NEED_CONFIRMATION，不得自动当作确定结果
 * - stopOcr 按 sessionId 清理
 */
class StampOcrViewModel(private val app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "StampOcrViewModel"
    }

    // ─── OCR 组件 ───
    private var ocrAnalyzer: SteelStampOcrAnalyzer? = null
    private var frameAnalyzer: StampOcrFrameAnalyzer? = null

    // ─── 会话追踪 ───
    private var boundController: CameraController? = null
    private var boundSessionId: String? = null

    // ─── UI 状态 ───
    private val _ocrState = MutableStateFlow(StampOcrState())
    val ocrState: StateFlow<StampOcrState> = _ocrState.asStateFlow()

    // ─── 最近一次 OCR 结果 ───
    private val _lastResult = MutableStateFlow<SteelStampResult?>(null)
    val lastResult: StateFlow<SteelStampResult?> = _lastResult.asStateFlow()

    // ─── 人工确认后的最终文本 ───
    private val _confirmedText = MutableStateFlow<String?>(null)
    val confirmedText: StateFlow<String?> = _confirmedText.asStateFlow()

    /**
     * 启动 OCR 模式
     *
     * 由 StampOcrScreen 的 CameraPreview.onConnected 回调触发。
     * 此时 CameraController 已以 STAMP_OCR 模式连接完成。
     */
    fun startOcr(
        controller: CameraController,
        sessionId: String,
    ) {
        Log.d(TAG, "startOcr: sessionId=$sessionId")
        boundController = controller
        boundSessionId = sessionId

        val analyzer = SteelStampOcrAnalyzer()
        ocrAnalyzer = analyzer

        // 预热 ML Kit（首拍前加载模型，避免首拍超时）
        viewModelScope.launch(Dispatchers.Default) {
            analyzer.warmUp()
            Log.d(TAG, "startOcr: warmUp completed")
        }

        _ocrState.value = StampOcrState(status = StampOcrStatus.IDLE)
    }

    /**
     * 拍照并执行 OCR
     *
     * 流程：CAPTURING → 保存 JPEG → PROCESSING → OCR 分析 → 结果状态
     */
    fun captureAndRecognize() {
        val controller = boundController
        val sessionId = boundSessionId
        if (controller == null || sessionId == null) {
            Log.e(TAG, "captureAndRecognize: controller or sessionId is null")
            _ocrState.value = StampOcrState(status = StampOcrStatus.ERROR, error = "相机未连接")
            return
        }

        val currentStatus = _ocrState.value.status
        if (currentStatus == StampOcrStatus.CAPTURING || currentStatus == StampOcrStatus.PROCESSING) {
            Log.w(TAG, "captureAndRecognize: already in progress ($currentStatus)")
            return
        }

        _ocrState.value = StampOcrState(status = StampOcrStatus.CAPTURING)
        _lastResult.value = null
        _confirmedText.value = null

        viewModelScope.launch {
            try {
                // 保存到临时文件
                val tempFile = File(app.cacheDir, "stamp_ocr_${System.currentTimeMillis()}.jpg")
                val result = controller.takePhoto(sessionId, tempFile)

                result.fold(
                    onSuccess = { file ->
                        Log.d(TAG, "captureAndRecognize: photo saved: ${file.absolutePath}, size=${file.length()}")
                        _ocrState.value = StampOcrState(status = StampOcrStatus.PROCESSING)
                        runOcr(file)
                    },
                    onFailure = { error ->
                        Log.e(TAG, "captureAndRecognize: takePhoto failed", error)
                        _ocrState.value = StampOcrState(
                            status = StampOcrStatus.ERROR,
                            error = "拍照失败: ${error.message}"
                        )
                        tempFile.delete()
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "captureAndRecognize: exception", e)
                _ocrState.value = StampOcrState(
                    status = StampOcrStatus.ERROR,
                    error = "拍照异常: ${e.message}"
                )
            }
        }
    }

    /**
     * 执行 OCR 分析（在 Default dispatcher 上）
     */
    private suspend fun runOcr(imageFile: File) {
        val analyzer = ocrAnalyzer
        if (analyzer == null) {
            Log.e(TAG, "runOcr: ocrAnalyzer is null")
            _ocrState.value = StampOcrState(status = StampOcrStatus.ERROR, error = "OCR 分析器未初始化")
            imageFile.delete()
            return
        }

        try {
            val bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(imageFile.absolutePath)
            }
            if (bitmap == null) {
                Log.e(TAG, "runOcr: failed to decode image")
                _ocrState.value = StampOcrState(status = StampOcrStatus.ERROR, error = "图片解码失败")
                imageFile.delete()
                return
            }

            val ocrResult = withContext(Dispatchers.Default) {
                analyzer.analyzeStructured(bitmap)
            }
            bitmap.recycle()

            Log.d(TAG, "runOcr: result status=${ocrResult.status}, stage=${ocrResult.stage}, lines=${ocrResult.detectedLineCount}")
            _lastResult.value = ocrResult

            // 根据 OCR 结果设置状态
            val newStatus = when (ocrResult.status) {
                OcrResultStatus.EXACT -> StampOcrStatus.EXACT
                OcrResultStatus.NEED_CONFIRMATION -> StampOcrStatus.NEED_CONFIRMATION
                OcrResultStatus.FAILED -> StampOcrStatus.FAILED
            }
            _ocrState.value = StampOcrState(
                status = newStatus,
                result = ocrResult,
            )

            // 如果是 EXACT，自动填充确认文本
            if (ocrResult.status == OcrResultStatus.EXACT) {
                _confirmedText.value = ocrResult.lines.joinToString("\n") { it.text }
            }
        } catch (e: Exception) {
            Log.e(TAG, "runOcr: exception during OCR", e)
            _ocrState.value = StampOcrState(
                status = StampOcrStatus.ERROR,
                error = "OCR 分析异常: ${e.message}"
            )
        } finally {
            imageFile.delete()
        }
    }

    /**
     * 人工确认结果（用于 NEED_CONFIRMATION 状态）
     *
     * 用户可以编辑识别文本后调用此方法确认。
     */
    fun confirmResult(editedText: String) {
        Log.d(TAG, "confirmResult: $editedText")
        _confirmedText.value = editedText
        _ocrState.value = _ocrState.value.copy(
            status = StampOcrStatus.EXACT,
        )
    }

    /**
     * 重试（回到 IDLE 状态）
     */
    fun retry() {
        Log.d(TAG, "retry")
        _lastResult.value = null
        _confirmedText.value = null
        _ocrState.value = StampOcrState(status = StampOcrStatus.IDLE)
    }

    /**
     * 停止 OCR 并清理
     *
     * 不负责 disconnect（由 StampOcrScreen 按 sessionId 处理）。
     */
    fun stopOcr() {
        Log.d(TAG, "stopOcr")
        boundController = null
        boundSessionId = null
        ocrAnalyzer = null
        frameAnalyzer = null
        _ocrState.value = StampOcrState()
        _lastResult.value = null
        _confirmedText.value = null
    }
}

/**
 * OCR 状态枚举
 */
enum class StampOcrStatus {
    /** 相机已连接，等待拍照 */
    IDLE,
    /** 拍照中 */
    CAPTURING,
    /** OCR 分析中 */
    PROCESSING,
    /** 识别成功（高置信度） */
    EXACT,
    /** 需要人工确认（低置信度/漏行/目录冲突） */
    NEED_CONFIRMATION,
    /** 识别失败（模糊/无内容） */
    FAILED,
    /** 系统错误（相机/文件/分析异常） */
    ERROR,
}

/**
 * OCR UI 状态
 */
data class StampOcrState(
    val status: StampOcrStatus = StampOcrStatus.IDLE,
    val result: SteelStampResult? = null,
    val error: String? = null,
)
