package com.wearable.inspection.mobile.dpm

import android.app.Application
import android.graphics.Rect
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wearable.inspection.mobile.MobileInspectionApp
import com.wearable.inspection.mobile.camera.CameraController
import com.wearable.inspection.mobile.data.db.AppDatabase
import com.wearable.inspection.mobile.data.entity.DpmScanEvidenceEntity
import com.wearable.inspection.mobile.data.image.MobileImageStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

/**
 * DPM 扫码 ViewModel — 管理 DpmAnalyzer + DpmFrameAnalyzer 生命周期。
 *
 * 设计变更（修复竞态）：
 * - 不再自行 connect/switchMode，由 DpmScanScreen 的 CameraPreview 以 DPM_SCAN 模式连接
 * - startScan 接收已连接的 CameraController 和 sessionId
 * - stopScan 按 sessionId 清理
 */
class DpmScanViewModel(private val app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "DpmScanViewModel"
    }

    // ─── 证据持久化依赖 ───
    private val imageStore = MobileImageStore(app)
    private val database = AppDatabase.get(app)
    private val evidenceDao = database.dpmScanEvidenceDao()
    private val applicationScope: CoroutineScope =
        (app.applicationContext as? MobileInspectionApp)?.applicationScope
            ?: error("DpmScanViewModel requires MobileInspectionApp")

    // ─── DPM 组件 ───
    private var dpmAnalyzer: DpmAnalyzer? = null
    private var dpmFrameAnalyzer: DpmFrameAnalyzer? = null
    private var respondGate: DpmRespondGate? = null
    private var gridGate: DpmGridGate? = null

    // ─── 会话追踪 ───
    private var boundController: CameraController? = null
    private var boundSessionId: String? = null
    // 由扫描入口在启动时显式快照；不从历史照片、名称或时间反推关联。
    private var scanAssociation = DpmScanAssociation()

    // ─── 证据保存去重 ───
    @Volatile
    private var evidenceSaved = false
    private var evidenceSavedSessionId: String? = null
    private val evidenceSaveLock = Any()
    private val savedEvidenceSessions = mutableSetOf<String>()
    private val scheduledEvidenceSessions = mutableSetOf<String>()
    private val evidenceSaveCompletions = mutableMapOf<String, CompletableDeferred<Boolean>>()

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
        batchId: String? = null,
        partId: String? = null,
        templateId: String? = null,
        viewIndex: Int? = null,
        photoId: Long? = null,
    ) {
        scanAssociation = DpmScanAssociation(
            batchId = batchId?.takeIf { it.isNotBlank() },
            partId = partId?.takeIf { it.isNotBlank() },
            templateId = templateId?.takeIf { it.isNotBlank() },
            viewIndex = viewIndex,
            photoId = photoId,
        )
        Log.d(TAG, "startScan: sessionId=$sessionId, controller=$controller, batchId=${scanAssociation.batchId}")
        val scope = viewModelScope
        evidenceSaved = false
        evidenceSavedSessionId = sessionId
        // 清除旧会话的解码结果，防止旧 lastResult 触发新会话的保存
        _lastResult.value = null

        val rg = DpmRespondGate()
        val gg = DpmGridGate(missThreshold = 8, cooldownMs = 1500L)  // 旧版基线：MISS_STREAK_TO_GRID=8, GRID_COOLDOWN_MS=1500
        respondGate = rg
        gridGate = gg

        val analyzer = DpmAnalyzer(
            zxingDecoder = ZxingDataMatrixDecoder(),
            mlKitDecoder = MlKitDataMatrixDecoder(),
            respondGate = rg,
            gridGate = gg,
            scope = scope,
            dimensionMode = { MobileInspectionApp.settings(app).dpmDimensionMode },
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
     * 确保关闭闪光灯并复位 UI。
     * 不负责 disconnect（由 DpmScanScreen 按 sessionId 处理）。
     */
    fun stopScan() {
        val controller = boundController
        val sessionId = boundSessionId

        // 关闭闪光灯（异步尽力而为）
        if (controller != null) {
            applicationScope.launch {
                controller.setTorch(false)
            }
        }

        // 清理 FrameAnalyzer
        dpmFrameAnalyzer?.stop()
        dpmFrameAnalyzer = null

        // 清理 DpmAnalyzer 状态
        dpmAnalyzer?.setScanModeActive(false)
        dpmAnalyzer = null
        respondGate = null
        gridGate = null

        boundController = null
        boundSessionId = null
        scanAssociation = DpmScanAssociation()
        _scanState.value = DpmScanState()
    }

    /**
     * 获取证据帧数据（从 DpmFrameAnalyzer 提取）。
     *
     * 必须在 stopScan() 之前调用，否则 analyzer 已被清理。
     * 返回的 Bitmap 由调用方接管，必须在保存后回收。
     */
    fun getEvidenceFrames(): DpmFrameAnalyzer.EvidenceFrames? {
        return dpmFrameAnalyzer?.getAndClearEvidenceFrames()
    }

    /**
     * 保存通过解码器内部 ECC 的成功扫码证据到文件系统和数据库。
     *
     * 由 DpmScanScreen 在退出流程中调用（stopScan 之前）。
     * 重复调用会被 evidenceSaved 标记拦截。
     *
     * @param sessionId 当前扫描会话 ID
     * @param evidenceFrames 从 getEvidenceFrames() 获取的帧数据
     */
    suspend fun saveEvidence(
        sessionId: String,
        evidenceFrames: DpmFrameAnalyzer.EvidenceFrames?,
    ): Boolean {
        val association = synchronized(evidenceSaveLock) { scanAssociation }
        return saveEvidenceInternal(sessionId, evidenceFrames, association)
    }

    private suspend fun saveEvidenceInternal(
        sessionId: String,
        evidenceFrames: DpmFrameAnalyzer.EvidenceFrames?,
        association: DpmScanAssociation,
        alreadyScheduled: Boolean = false,
    ): Boolean {
        if (!alreadyScheduled) {
            synchronized(evidenceSaveLock) {
                if (sessionId in savedEvidenceSessions ||
                    evidenceSaved && evidenceSavedSessionId == sessionId ||
                    !scheduledEvidenceSessions.add(sessionId)
                ) {
                    Log.d(TAG, "saveEvidence: sessionId=$sessionId already saved or scheduled, skipping")
                    recycleEvidenceBitmap(evidenceFrames)
                    return false
                }
            }
        }

        var originalPath: String? = null
        var roiPath: String? = null
        try {
            if (evidenceFrames == null ||
                !evidenceFrames.isDecodeSuccess ||
                evidenceFrames.decodedCode.isNullOrBlank() ||
                evidenceFrames.decodeSource == null
            ) {
                // 无 ECC 成功时不落盘、不建 NO_READ 证据行；仅保留内存状态。
                Log.i(
                    TAG,
                    "saveEvidence: sessionId=$sessionId result=FAILURE reason=no ECC-validated success frame",
                )
                return false
            }

            val ts = System.currentTimeMillis()
            val shortId = sessionId.take(8)
            val frameFileName = "dpm_${shortId}_ok_${ts}_frame.jpg"
            val roiFileName = "dpm_${shortId}_ok_${ts}_roi.jpg"
            Log.i(
                TAG,
                "saveEvidence: sessionId=$sessionId evidenceFrames=SUCCESS " +
                    "decodedLength=${evidenceFrames.decodedCode?.length ?: 0} " +
                    "decodeSource=${evidenceFrames.decodeSource}",
            )

            originalPath = imageStore.saveDpmEvidenceFrame(
                evidenceFrames.bitmap, frameFileName
            )
            if (originalPath == null) {
                Log.e(TAG, "saveEvidence: sessionId=$sessionId result=FAILURE reason=original frame write")
                return false
            }
            Log.i(TAG, "saveEvidence: original path=$originalPath bytes=${File(originalPath!!).length()}")

            // 保存 ROI 裁切（如有 ROI 且帧尺寸有效）
            val roi = evidenceFrames.roi
            if (roi != null && !roi.isEmpty) {
                roiPath = imageStore.saveDpmEvidenceRoi(
                    evidenceFrames.bitmap, roi, roiFileName
                )
                if (roiPath == null) {
                    Log.e(TAG, "saveEvidence: sessionId=$sessionId result=FAILURE reason=ROI write; deleting original")
                    imageStore.deleteDpmEvidenceFile(originalPath)
                    return false
                }
                Log.i(TAG, "saveEvidence: roi path=$roiPath bytes=${File(roiPath!!).length()}")
            }

            val entity = DpmScanEvidenceEntity(
                scanSessionId = sessionId,
                frameTimeMs = evidenceFrames.frameTimeMs.takeIf { it > 0L } ?: ts,
                frameSource = "CAMERA",
                decodedContent = evidenceFrames.decodedCode,
                status = "SUCCESS",
                decodeSource = evidenceFrames.decodeSource?.name,
                originalImagePath = originalPath!!,
                roiImagePath = roiPath,
                batchId = association.batchId,
                partId = association.partId,
                templateId = association.templateId,
                viewIndex = association.viewIndex,
                photoId = association.photoId,
                roiId = null,
            )

            val rowId = runCatching { evidenceDao.insert(entity) }.getOrElse { error ->
                imageStore.deleteDpmEvidenceFile(originalPath)
                imageStore.deleteDpmEvidenceFile(roiPath)
                throw error
            }
            synchronized(evidenceSaveLock) {
                savedEvidenceSessions += sessionId
                if (evidenceSavedSessionId == sessionId) evidenceSaved = true
            }
            Log.i(
                TAG,
                "saveEvidence: sessionId=$sessionId persisted rowId=$rowId status=${entity.status} " +
                    "decodedLength=${entity.decodedContent?.length ?: 0} decodeSource=${entity.decodeSource}",
            )
            return true
        } catch (e: Exception) {
            imageStore.deleteDpmEvidenceFile(originalPath)
            imageStore.deleteDpmEvidenceFile(roiPath)
            Log.e(TAG, "saveEvidence: sessionId=$sessionId result=FAILURE reason=persistence", e)
            return false
        } finally {
            // 图片文件和数据库行均完成后才释放保存层唯一持有的 Bitmap。
            recycleEvidenceBitmap(evidenceFrames)
            if (!alreadyScheduled) {
                synchronized(evidenceSaveLock) { scheduledEvidenceSessions.remove(sessionId) }
            }
        }
    }

    private fun recycleEvidenceBitmap(evidenceFrames: DpmFrameAnalyzer.EvidenceFrames?) {
        evidenceFrames?.bitmap?.let { bitmap ->
            runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
        }
    }

    /**
     * Persist evidence from a screen disposal and then release the camera session.
     * The callback is suspend because CameraController cleanup suspends.
     */
    fun saveEvidenceInScope(
        sessionId: String,
        evidenceFrames: DpmFrameAnalyzer.EvidenceFrames?,
        afterSave: suspend () -> Unit,
    ): Deferred<Boolean> {
        var shouldStart = false
        var association = DpmScanAssociation()
        val completion = synchronized(evidenceSaveLock) {
            when {
                sessionId in savedEvidenceSessions -> {
                    Log.d(TAG, "saveEvidenceInScope: sessionId=$sessionId already finalized")
                    recycleEvidenceBitmap(evidenceFrames)
                    CompletableDeferred<Boolean>().also { it.complete(true) }
                }
                evidenceSaveCompletions[sessionId] != null -> {
                    Log.d(TAG, "saveEvidenceInScope: sessionId=$sessionId already scheduled")
                    recycleEvidenceBitmap(evidenceFrames)
                    evidenceSaveCompletions.getValue(sessionId)
                }
                else -> {
                    shouldStart = true
                    association = scanAssociation
                    scheduledEvidenceSessions += sessionId
                    CompletableDeferred<Boolean>().also { evidenceSaveCompletions[sessionId] = it }
                }
            }
        }

        if (!shouldStart) return completion

        Log.i(TAG, "saveEvidenceInScope: scheduled sessionId=$sessionId")
        applicationScope.launch {
            var saved = false
            try {
                saved = saveEvidenceInternal(
                    sessionId = sessionId,
                    evidenceFrames = evidenceFrames,
                    association = association,
                    alreadyScheduled = true,
                )
                Log.i(TAG, "saveEvidenceInScope: sessionId=$sessionId completed saved=$saved")
                if (boundSessionId == sessionId) afterSave()
                completion.complete(saved)
            } catch (e: Exception) {
                Log.e(TAG, "saveEvidenceInScope: sessionId=$sessionId cleanup failed", e)
                completion.complete(false)
            } finally {
                synchronized(evidenceSaveLock) {
                    scheduledEvidenceSessions.remove(sessionId)
                    evidenceSaveCompletions.remove(sessionId)
                }
            }
        }
        return completion
    }

    /**
     * 成功解码回调进入导航前，等待当前会话的证据行和文件完成。
     * 这样导航后的导出页不会在后台保存协程完成前读取数据库。
     */
    suspend fun saveCurrentEvidenceAndAwait(): Boolean {
        val sessionId = boundSessionId ?: return false
        val controller = boundController
        val frames = getEvidenceFrames()
        if (frames == null) {
            val pending = synchronized(evidenceSaveLock) {
                evidenceSaveCompletions[sessionId]
            }
            return pending?.await() ?: synchronized(evidenceSaveLock) {
                sessionId in savedEvidenceSessions
            }
        }
        return saveEvidenceInScope(sessionId, frames) {
            stopScan()
            controller?.clearFrameAnalyzer()
            controller?.disconnect(sessionId)
        }.await()
    }

    /**
     * 切换闪光灯（suspend，等待 CameraX 异步结果）
     *
     * 检查 hasFlashUnit，等待 enableTorch 异步完成，
     * 用真实 torchState 更新 UI。
     */
    suspend fun toggleTorch(): Boolean {
        val controller = boundController ?: run {
            Log.w(TAG, "toggleTorch: no bound controller")
            return false
        }
        if (!controller.hasFlashUnit()) {
            Log.w(TAG, "toggleTorch: camera reports no flash unit")
            return false
        }
        val current = controller.isTorchOn() ?: _scanState.value.torchOn
        Log.i(TAG, "toggleTorch: current=$current requested=${!current}")
        val result = controller.setTorch(!current)
        if (result) {
            // 读取真实 torchState 更新 UI（不信任中间状态）
            val realState = controller.isTorchOn() ?: !current
            _scanState.value = _scanState.value.copy(torchOn = realState)
        }
        Log.i(TAG, "toggleTorch: result=$result real=${controller.isTorchOn()} ui=${_scanState.value.torchOn}")
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
        // onCleared 是 ViewModel 销毁的兜底路径（进程杀死等）。
        // 正常退出由 DpmScanScreen 主动调用 saveEvidence + stopScan。
        // 此处使用 Application 级作用域兜底，避免 ViewModel scope 随导航返回取消保存。
        val sid = boundSessionId
        val controller = boundController
        val frames = dpmFrameAnalyzer?.getAndClearEvidenceFrames()
        if (sid != null && frames != null) {
            saveEvidenceInScope(sid, frames) {
                stopScan()
                controller?.clearFrameAnalyzer()
                controller?.disconnect(sid)
            }
        } else if (sid == null || synchronized(evidenceSaveLock) { sid !in scheduledEvidenceSessions }) {
            stopScan()
            if (sid != null && controller != null) {
                applicationScope.launch {
                    controller.clearFrameAnalyzer()
                    controller.disconnect(sid)
                }
            }
        }
        super.onCleared()
    }
}

/** 扫描启动时由调用方提供的可空稳定关联，不是持久化模型。 */
private data class DpmScanAssociation(
    val batchId: String? = null,
    val partId: String? = null,
    val templateId: String? = null,
    val viewIndex: Int? = null,
    val photoId: Long? = null,
)

/**
 * DPM 扫码 UI 状态
 */
data class DpmScanState(
    val scanning: Boolean = false,
    val lastDecodedCode: String? = null,
    val decodeCount: Int = 0,
    val torchOn: Boolean = false,
)
