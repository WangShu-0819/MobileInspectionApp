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
 * 职责：
 * 1. 创建/销毁 DpmAnalyzer（ZXing 主 + ML Kit 兜底）
 * 2. 创建/销毁 DpmFrameAnalyzer 并绑定到 CameraController
 * 3. 收集解码结果并更新 UI 状态
 * 4. 管理扫码模式切换（scanModeActive）
 */
class DpmScanViewModel(application: Application) : AndroidViewModel(application) {

    private val cameraController = CameraController.getInstance(application)

    // ─── DPM 组件 ───
    private var dpmAnalyzer: DpmAnalyzer? = null
    private var dpmFrameAnalyzer: DpmFrameAnalyzer? = null
    private var respondGate: DpmRespondGate? = null
    private var gridGate: DpmGridGate? = null

    // ─── UI 状态 ───
    private val _scanState = MutableStateFlow(DpmScanState())
    val scanState: StateFlow<DpmScanState> = _scanState.asStateFlow()

    private val _lastResult = MutableStateFlow<DpmScanResult?>(null)
    val lastResult: StateFlow<DpmScanResult?> = _lastResult.asStateFlow()

    /**
     * 启动 DPM 扫码模式
     *
     * 创建 DpmAnalyzer + DpmFrameAnalyzer，绑定到 CameraController。
     * CameraController 应已以 DPM_SCAN 模式连接。
     */
    fun startScan(scanRoi: Rect? = null) {
        val app = getApplication<Application>()
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
            scanRoi = scanRoi,
        )
        dpmFrameAnalyzer = frameAnalyzer

        // 绑定到 CameraController
        scope.launch {
            cameraController.setFrameAnalyzer(frameAnalyzer)
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
     * 停止扫码
     */
    fun stopScan() {
        viewModelScope.launch {
            cameraController.clearFrameAnalyzer()
        }
        dpmFrameAnalyzer?.stop()
        dpmFrameAnalyzer = null
        dpmAnalyzer?.setScanModeActive(false)
        dpmAnalyzer = null
        respondGate = null
        gridGate = null
        _scanState.value = DpmScanState()
    }

    /**
     * 切换闪光灯
     */
    fun toggleTorch(): Boolean {
        val current = _scanState.value.torchOn
        val result = cameraController.setTorch(!current)
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
                source = DecodeSource.ZXING,
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
