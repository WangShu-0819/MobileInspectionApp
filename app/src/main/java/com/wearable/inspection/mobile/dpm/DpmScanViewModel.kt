package com.wearable.inspection.mobile.dpm

import android.app.Application
import android.graphics.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wearable.inspection.mobile.camera.CameraController
import com.wearable.inspection.mobile.camera.CameraMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * DPM 扫码 ViewModel — 管理 DpmAnalyzer + DpmFrameAnalyzer 生命周期。
 *
 * 设计变更（修复竞态）：
 * - 不再自行 connect/switchMode，由 DpmScanScreen 的 CameraPreview 以 DPM_SCAN 模式连接
 * - startScan 接收已连接的 CameraController 和 sessionId
 * - stopScan 按 sessionId 清理
 */
class DpmScanViewModel(application: Application) : AndroidViewModel(application) {

    // ─── DPM 组件 ───
    private var dpmAnalyzer: DpmAnalyzer? = null
    private var dpmFrameAnalyzer: DpmFrameAnalyzer? = null
    private var respondGate: DpmRespondGate? = null
    private var gridGate: DpmGridGate? = null

    // ─── 会话追踪 ───
    private var boundController: CameraController? = null
    private var boundSessionId: String? = null

    // ─── UI 状态 ───
    private val _scanState = MutableStateFlow(DpmScanState())
    val scanState: StateFlow<DpmScanState> = _scanState.asStateFlow()

    private val _lastResult = MutableStateFlow<DpmScanResult?>(null)
    val lastResult: StateFlow<DpmScanResult?> = _lastResult.asStateFlow()

    /**
     * 启动 DPM 扫码模式
     *
     * 由 DpmScanScreen 的 CameraPreview.onConnected 回调触发。
     * 此时 CameraController 已以 DPM_SCAN 模式连接完成。
     *
     * @param controller 已连接的 CameraController
     * @param sessionId 当前相机会话 ID
     * @param scanRoi 可选的扫描 ROI
     */
    fun startScan(
        controller: CameraController,
        sessionId: String,
    ) {
        val scope = viewModelScope

        val rg = DpmRespondGate()
        val gg = DpmGridGate(missThreshold = 5, cooldownMs = 3000L)
        respondGate = rg
        gridGate = gg

        val analyzer = DpmAnalyzer(
            zxingDecoder = ZxingDataMatrixDecoder(),
            mlKitDecoder = MlKitDataMatrixDecoder(),
            respondGate = rg,
            gridGate = gg,
            scope = scope,
        )
        analyzer.setMode(DpmAnalyzer.AnalysisMode.SCAN)
        analyzer.setScanModeActive(true)
        dpmAnalyzer = analyzer

        val frameAnalyzer = DpmFrameAnalyzer(
            dpmAnalyzer = analyzer,
            scope = scope,
        )
        dpmFrameAnalyzer = frameAnalyzer

        boundController = controller
        boundSessionId = sessionId

        // 绑定到已连接的 CameraController
        scope.launch {
            controller.setFrameAnalyzer(frameAnalyzer)
        }

        // 收集结果
        scope.launch {
            frameAnalyzer.results.collect { result ->
                handleResult(result)
            }
        }

        _scanState.value = DpmScanState(scanning = true)
    }

    /**
     * 动态更新扫描 ROI
     *
     * 由 DpmScanScreen 在 frameInfo 或 scanFrame 变化时调用。
     * 传 null 表示全图扫描（框与图像无交集时停止分析）。
     */
    fun updateScanRoi(scanRoi: Rect?) {
        dpmFrameAnalyzer?.updateScanRoi(scanRoi)
    }

    /**
     * 停止扫码并清理
     *
     * 清理 Analyzer、FrameAnalyzer 和状态。
     * 不负责 disconnect（由 DpmScanScreen 按 sessionId 处理）。
     */
    fun stopScan() {
        val controller = boundController
        val sessionId = boundSessionId

        // 清理 FrameAnalyzer
        dpmFrameAnalyzer?.stop()
        dpmFrameAnalyzer = null

        // 清理 DpmAnalyzer 状态
        dpmAnalyzer?.setScanModeActive(false)
        dpmAnalyzer = null
        respondGate = null
        gridGate = null

        // 从控制器移除分析器
        if (controller != null && sessionId != null) {
            viewModelScope.launch {
                controller.clearFrameAnalyzer()
            }
        }

        boundController = null
        boundSessionId = null
        _scanState.value = DpmScanState()
    }

    /**
     * 切换闪光灯
     */
    fun toggleTorch(): Boolean {
        val controller = boundController ?: return false
        val current = _scanState.value.torchOn
        val result = controller.setTorch(!current)
        if (result) {
            _scanState.value = _scanState.value.copy(torchOn = !current)
        }
        return result
    }

    private fun handleResult(result: DpmAnalyzeResult) {
        if (result.status == DpmAnalyzeStatus.DECODED && result.code != null) {
            _lastResult.value = DpmScanResult(
                rawValue = result.code,
                format = BarcodeFormat.DATA_MATRIX,
                timestampMs = System.currentTimeMillis(),
                source = result.source ?: DecodeSource.ZXING,
            )
            _scanState.value = _scanState.value.copy(
                lastDecodedCode = result.code,
                decodeCount = _scanState.value.decodeCount + 1,
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
    }
}

/**
 * DPM 扫码 UI 状态
 */
data class DpmScanState(
    val scanning: Boolean = false,
    val lastDecodedCode: String? = null,
    val decodeCount: Int = 0,
    val torchOn: Boolean = false,
)
