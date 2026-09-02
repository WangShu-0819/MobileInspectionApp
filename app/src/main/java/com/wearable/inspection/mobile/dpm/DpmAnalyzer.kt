package com.wearable.inspection.mobile.dpm

import android.graphics.Bitmap
import android.graphics.Bitmap.createBitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 组装版 DpmAnalyzer — 从旧版 Wearable Inspection 的 camera/DpmAnalyzer.kt 迁移。
 *
 * 核心算法忠实保留旧版顺序与短路语义：
 * 1. ROI 原始/预处理 ZXing 主解码（中心 50% 或 scanRoi → 缩放到400px → 策略轮转）
 * 2. 无扫码框约束时才允许全图 ZXing（长边>1280 降采样 → 同策略预处理）
 * 3. ML Kit DATA_MATRIX 兜底（无扫码框时对原图直接解码）
 * 4. 满足旧门控条件时才执行网格兜底（异步）
 * 5. 扫码框存在时禁止框外全图识别
 *
 * 每个 ZXing 阶段内部：正常极性 + 反色双试；命中但响应门拦截时不响应、继续后续阶段。
 */
class DpmAnalyzer(
    private val config: DpmAnalyzerConfig = DpmAnalyzerConfig(),
    private val zxingDecoder: DpmZxingDecoder,
    private val mlKitDecoder: DpmMlKitDecoder,
    private val respondGate: DpmRespondGate,
    private val gridGate: DpmGridGate,
    private val scope: CoroutineScope,
    private val clock: DpmClock = SystemDpmClock(),
    private val onLensRefocusNeeded: () -> Unit = {},
    /** DPM 网格重建尺寸模式提供器（每次提交网格任务时读取快照；默认 AUTO） */
    private val dimensionMode: () -> DpmDimensionMode = { DpmDimensionMode.AUTO },
) {
    companion object {
        private const val TAG = "DpmAnalyzer"
    }

    @Volatile
    var currentMode: AnalysisMode = AnalysisMode.SCAN
        private set

    @Volatile
    var scanModeActive: Boolean = false
        private set

    @Volatile
    var focusStatus: FocusStatus = FocusStatus.FOCUSED
        private set

    private val throttleLastSuccessMs = AtomicLong(0L)
    private val throttleLastFailMs = AtomicLong(0L)
    private val gateStateAccess = Any()
    private val analysisRunning = AtomicBoolean(false)
    private val gridTriggered = AtomicBoolean(false)
    private var gridExecutionJob: Job? = null
    private var frameCount = 0L

    private var missCount = 0

    // ─── Session lifecycle ───

    fun setMode(mode: AnalysisMode) {
        synchronized(gateStateAccess) {
            if (currentMode == mode) return
            currentMode = mode
            respondGate.reset()
            resetThrottleState()
            missCount = 0
        }
    }

    fun setScanModeActive(active: Boolean) {
        synchronized(gateStateAccess) {
            scanModeActive = active
            if (!active) {
                gridTriggered.set(false)
            }
        }
        gridGate.setScanModeActive(active)
    }

    fun onFrameFocusChanged(hasFocus: Boolean) {
        val newStatus = if (hasFocus) FocusStatus.FOCUSED else FocusStatus.OUT_OF_FOCUS
        focusStatus = newStatus
        if (!hasFocus) return
        synchronized(gateStateAccess) {
            resetThrottleState()
            missCount = 0
        }
    }

    fun resetForTest() {
        synchronized(gateStateAccess) {
            currentMode = AnalysisMode.SCAN
            scanModeActive = false
            focusStatus = FocusStatus.FOCUSED
            respondGate.reset()
            gridGate.setScanModeActive(false)
            resetThrottleState()
            missCount = 0
            frameCount = 0
        }
    }

    private fun resetThrottleState() {
        throttleLastSuccessMs.set(0L)
        throttleLastFailMs.set(0L)
    }

    // ─── Analysis entry point ───

    suspend fun analyze(
        frame: Bitmap,
        frameRotation: Int,
        scanRoi: Rect? = null,
        scanControl: DpmScanControl? = null,
    ): DpmAnalyzeResult {
        Log.d(TAG, "analyze: frame=${frame.width}x${frame.height}, roi=$scanRoi, focus=$focusStatus, mode=$currentMode")
        if (focusStatus == FocusStatus.OUT_OF_FOCUS) {
            Log.d(TAG, "analyze: OUT_OF_FOCUS, returning NO_CODE")
            return DpmAnalyzeResult(status = DpmAnalyzeStatus.NO_CODE)
        }
        if (!analysisRunning.compareAndSet(false, true)) {
            Log.d(TAG, "analyze: THROTTLED (already running)")
            return DpmAnalyzeResult(status = DpmAnalyzeStatus.THROTTLED)
        }
        try {
            val throttleResult = shouldAllowAnalysis()
            if (throttleResult != DpmAnalyzeStatus.PROCEED) {
                Log.d(TAG, "analyze: THROTTLED by timing gate")
                return DpmAnalyzeResult(status = throttleResult)
            }
            Log.d(TAG, "analyze: starting performMultiStrategyDecode")
            val decodedCode = performMultiStrategyDecode(frame, frameRotation, scanRoi, scanControl)
            Log.d(TAG, "analyze: decodedCode=$decodedCode")
            return processDecodeResult(decodedCode)
        } finally {
            analysisRunning.set(false)
        }
    }

    private fun shouldAllowAnalysis(): DpmAnalyzeStatus {
        val now = clock.currentTimeMs()
        if (throttleLastSuccessMs.get() > 0) {
            val delay = if (currentMode == AnalysisMode.SCAN) SUCCESS_HOLD_MS else SCAN_SUCCESS_HOLD_MS
            if (now - throttleLastSuccessMs.get() < delay) return DpmAnalyzeStatus.THROTTLED
        }
        if (throttleLastFailMs.get() > 0) {
            val failDelay = if (missCount >= config.missTriggerCount) FAIL_LONG_DELAY_MS else FAIL_SHORT_DELAY_MS
            if (now - throttleLastFailMs.get() < failDelay) return DpmAnalyzeStatus.THROTTLED
        }
        return DpmAnalyzeStatus.PROCEED
    }

    /**
     * 解码结果：code + source
     */
    private data class DecodeResult(val code: String, val source: DecodeSource)

    /**
     * 旧版4阶段解码流程（忠实保留顺序与短路语义）：
     * 1. ROI（中心 50% 或 scanRoi）→ 缩放到400px → 预处理策略 → ZXing/ML Kit
     * 2. 无扫码框时：全图降采样（长边>1280）→ 同策略预处理 → ZXing
     * 3. 无扫码框时：ML Kit 全图兜底
     * 4. 无扫码框时：网格兜底（异步）
     *
     * 扫码框存在时禁止框外全图识别（只解码框内区域）。
     */
    private suspend fun performMultiStrategyDecode(
        frame: Bitmap,
        frameRotation: Int,
        scanRoi: Rect?,
        scanControl: DpmScanControl?,
    ): DecodeResult? {
        val strategies = DpmPreprocessor.strategiesForFrame(frameCount++)
        Log.d(TAG, "performMultiStrategyDecode: frame=${frame.width}x${frame.height}, roi=$scanRoi, strategies=${strategies.size}")
        // ─── 阶段1：ROI 解码 ───
        val roi = cropRoi(frame, scanRoi)
        val roiScaled = scaleToTargetWidth(roi, config.roiTargetWidth)
        val roiGray = bitmapToGray(roiScaled)
        val roiW = roiScaled.width
        val roiH = roiScaled.height
        Log.d(TAG, "Stage1: ROI ${roi.width}x${roi.height} → scaled ${roiW}x${roiH}")
        try {
            for (strategy in strategies) {
                if (scanControl?.aborted() == true) break
                decodeWithStrategy(roiGray, roiW, roiH, strategy, scanControl)?.let {
                    Log.d(TAG, "Stage1: HIT strategy=$strategy, code=${it.code}")
                    return it
                }
            }
        } finally {
            if (roiScaled !== roi) roiScaled.recycle()
            roi.recycle()
        }
        Log.d(TAG, "Stage1: MISS")

        // 扫码框存在时跳过全图兜底（只识别框内码）
        if (scanRoi != null) {
            Log.d(TAG, "scanRoi present, skipping full-image stages")
            return null
        }

        // ─── 阶段2：全图 ZXing（降采样到 SCAN_MAX_EDGE）───
        val scan = downscaleToMaxEdge(frame, SCAN_MAX_EDGE)
        val scanGray = bitmapToGray(scan)
        val scanW = scan.width
        val scanH = scan.height
        Log.d(TAG, "Stage2: full-image ${frame.width}x${frame.height} → ${scanW}x${scanH}")
        try {
            for (strategy in strategies) {
                if (scanControl?.aborted() == true) break
                decodeWithStrategy(scanGray, scanW, scanH, strategy, scanControl)?.let {
                    Log.d(TAG, "Stage2: HIT strategy=$strategy, code=${it.code}")
                    return it
                }
            }
        } finally {
            if (scan !== frame) scan.recycle()
        }
        Log.d(TAG, "Stage2: MISS")

        // ─── 阶段3：ML Kit 全图兜底 ───
        Log.d(TAG, "Stage3: ML Kit full-image decode")
        mlKitDecoder.decode(frame)?.let {
            Log.d(TAG, "Stage3: ML Kit HIT, code=${it.rawValue}")
            return DecodeResult(it.rawValue, DecodeSource.ML_KIT)
        }
        Log.d(TAG, "Stage3: ML Kit MISS")

        // ─── 阶段4：网格兜底（异步）───
        Log.d(TAG, "Stage4: triggering grid decode")
        triggerGridDecode(scanGray, scanW, scanH, scanControl)
        return null
    }

    /**
     * 单策略完整解码尝试：灰度 → 预处理 → 逐候选 ZXing+ML Kit（正常极性+反色双试）。
     * 返回第一个成功结果，null = 全部候选双极性均未识别。
     */
    private suspend fun decodeWithStrategy(
        gray: IntArray, w: Int, h: Int, strategy: Int, scanControl: DpmScanControl?,
    ): DecodeResult? {
        val candidates = DpmPreprocessor.preprocess(gray, w, h, strategy)
        for (candidate in candidates) {
            if (scanControl?.aborted() == true) break
            // 正常极性
            decodePixels(candidate.pixels, candidate.width, candidate.height, candidate.binarizer)?.let { return it }
            // 反色双试
            val inverted = invertBytes(candidate.pixels)
            decodePixels(inverted, candidate.width, candidate.height, candidate.binarizer)?.let { return it }
            // 旧版 ROI 快速链：策略 2 的亮点候选直接尝试轴对齐点阵重建。
            if (strategy == DpmPreprocessor.STRATEGY_LASER_ETCHED &&
                candidate.name == "s2-bright-otsu-dilate"
            ) {
                ImportedDpmScanner.decodeDotGridCandidate(
                    candidate.pixels,
                    candidate.width,
                    candidate.height,
                    dimensionMode().dimensions(),
                )?.let { return DecodeResult(it.text, DecodeSource.GRID) }
            }
        }
        return null
    }

    /**
     * 单候选单极性 ZXing 解码（旧版：ML Kit 不在候选级别调用，仅全图阶段3兜底）。
     */
    private suspend fun decodePixels(pixels: ByteArray, w: Int, h: Int, binarizer: DpmBinarizer): DecodeResult? {
        val bitmap = grayBytesToBitmap(pixels, w, h)
        zxingDecoder.decode(bitmap)?.let { return DecodeResult(it.rawValue, DecodeSource.ZXING) }
        return null
    }

    /**
     * 裁切 ROI：scanRoi 存在时裁切框内区域；否则裁切中心 CENTER_ROI_RATIO 区域。
     * 与旧版 cropCenter(bitmap, CENTER_ROI_RATIO) / cropNormalized(bitmap, scanRoi) 一致。
     */
    private fun cropRoi(frame: Bitmap, scanRoi: Rect?): Bitmap {
        if (scanRoi != null && !scanRoi.isEmpty) {
            val clamped = clampRect(scanRoi, frame.width, frame.height)
            return createBitmap(frame, clamped.left, clamped.top, clamped.width(), clamped.height())
        }
        // 旧版 CENTER_ROI_RATIO=0.5f：中心 50% 区域
        val cropW = (frame.width * config.centerCropRatio).toInt().coerceIn(1, frame.width)
        val cropH = (frame.height * config.centerCropRatio).toInt().coerceIn(1, frame.height)
        val left = (frame.width - cropW) / 2
        val top = (frame.height - cropH) / 2
        return createBitmap(frame, left, top, cropW, cropH)
    }

    /** 缩放到目标宽度（保持宽高比），与旧版 DPM_ROI_TARGET_WIDTH=400 一致 */
    private fun scaleToTargetWidth(bitmap: Bitmap, targetWidth: Int): Bitmap {
        if (bitmap.width <= targetWidth) return bitmap
        val scale = targetWidth.toFloat() / bitmap.width
        return Bitmap.createScaledBitmap(bitmap, targetWidth, (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }

    /**
     * 全图降采样（长边 > maxEdge 时等比缩放），与旧版 SCAN_MAX_EDGE=1280 一致。
     * 返回原图或新 Bitmap（调用方负责回收新 Bitmap）。
     */
    private fun downscaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val m = Matrix().apply { postRotate(degrees) }
        return createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private fun clampRect(rect: Rect, w: Int, h: Int): Rect {
        val l = rect.left.coerceIn(0, w - 1)
        val t = rect.top.coerceIn(0, h - 1)
        val r = rect.right.coerceIn(l + 1, w)
        val b = rect.bottom.coerceIn(t + 1, h)
        return Rect(l, t, r, b)
    }

    private fun bitmapToGray(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return IntArray(w * h) { i ->
            val argb = pixels[i]
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            (r * 299 + g * 587 + b * 114) / 1000
        }
    }

    private fun grayBytesToBitmap(gray: ByteArray, w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h) { i ->
            val v = gray[i].toInt() and 0xFF
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    private fun invertBytes(src: ByteArray): ByteArray =
        ByteArray(src.size) { i -> (255 - (src[i].toInt() and 0xFF)).toByte() }

    // ─── Grid decode ───

    private fun triggerGridDecode(gray: IntArray, w: Int, h: Int, scanControl: DpmScanControl?) {
        val nowMs = clock.currentTimeMs()
        if (!gridGate.canSubmit(nowMs, gridTriggered.get())) return
        if (!gridTriggered.compareAndSet(false, true)) return
        gridGate.markSubmitted(nowMs)
        val generationSnap = gridGate.generation
        gridExecutionJob?.cancel()
        gridExecutionJob = scope.launch {
            try {
                val result = DpmGridReconstructor.reconstruct(gray, w, h, dimensionMode(), scanControl)
                result?.let { code ->
                    if (gridGate.belongsToCurrentSession(generationSnap)) {
                        // 网格解码结果：绕过响应门直接响应，source=GRID
                        val now = clock.currentTimeMs()
                        respondGate.onResponded(code)
                        throttleLastSuccessMs.set(now)
                        missCount = 0
                        gridGate.onHit()
                        emitResult(DpmAnalyzeResult(
                            status = DpmAnalyzeStatus.DECODED,
                            code = code,
                            isDuplicated = false,
                            source = DecodeSource.GRID,
                        ))
                    }
                }
            } finally {
                gridTriggered.set(false)
            }
        }
    }

    /**
     * 发射结果到结果流（由子协程调用）
     */
    private var resultEmitter: (suspend (DpmAnalyzeResult) -> Unit)? = null

    fun setResultEmitter(emitter: suspend (DpmAnalyzeResult) -> Unit) {
        resultEmitter = emitter
    }

    private suspend fun emitResult(result: DpmAnalyzeResult) {
        resultEmitter?.invoke(result)
    }

    // ─── Result processing ───

    private fun processDecodeResult(result: DecodeResult?): DpmAnalyzeResult {
        val now = clock.currentTimeMs()
        return if (result != null) {
            handleSuccess(result.code, result.source, now)
        } else {
            handleMiss(now)
        }
    }

    private fun handleSuccess(code: String, source: DecodeSource, now: Long): DpmAnalyzeResult {
        val rearmOnHold = currentMode == AnalysisMode.SCAN
        if (!respondGate.shouldRespond(code, rearmOnHold)) {
            throttleLastSuccessMs.set(now)
            return DpmAnalyzeResult(status = DpmAnalyzeStatus.DEDUPLICATED)
        }
        respondGate.onResponded(code)
        throttleLastSuccessMs.set(now)
        missCount = 0
        gridGate.onHit()  // 旧版：命中复位网格 miss 计数
        return DpmAnalyzeResult(
            status = DpmAnalyzeStatus.DECODED,
            code = code,
            isDuplicated = false,
            source = source,
        )
    }

    private fun handleMiss(now: Long): DpmAnalyzeResult {
        respondGate.onMiss()
        gridGate.onMiss()  // 旧版：miss 累计网格门控计数
        throttleLastFailMs.set(now)
        missCount++
        if (missCount >= config.missTriggerCount) {
            onLensRefocusNeeded()
        }
        return DpmAnalyzeResult(status = DpmAnalyzeStatus.NO_CODE)
    }

    // ─── Types ───

    enum class FocusStatus { FOCUSED, OUT_OF_FOCUS }
    enum class AnalysisMode { SCAN, INSPECTION }
}

/**
 * DpmAnalyzer 配置参数 — 忠实保留旧版默认值。
 *
 * 旧版来源：camera/DpmAnalyzer.kt companion object 常量。
 */
data class DpmAnalyzerConfig(
    /** 旧版 CENTER_ROI_RATIO=0.5f → 中心 50% 区域 */
    val centerCropRatio: Float = 0.5f,
    /** 旧版 DPM_ROI_TARGET_WIDTH=400 → ROI 预处理前缩放到 400px */
    val roiTargetWidth: Int = 400,
    /** 旧版 ATTEMPT_INTERVAL_MS=200 */
    val attemptIntervalMs: Long = 200L,
    /** 旧版 MISS_STREAK_TO_FOCUS=30 */
    val missTriggerCount: Int = 30,
    /** 旧版 MISS_STREAK_TO_GRID=8 */
    val gridMissThreshold: Int = 8,
    /** 旧版 GRID_COOLDOWN_MS=1500 */
    val gridCooldownMs: Long = 1500L,
)

data class DpmAnalyzeResult(
    val status: DpmAnalyzeStatus,
    val code: String? = null,
    val isDuplicated: Boolean = false,
    val source: DecodeSource? = null,
)

enum class DpmAnalyzeStatus {
    PROCEED, DECODED, DEDUPLICATED, NO_CODE, THROTTLED
}

/** 旧版无显式 success hold；扫码模式下同码由 DpmRespondGate 控制 */
private const val SUCCESS_HOLD_MS = 1000L
private const val SCAN_SUCCESS_HOLD_MS = 3000L
/** 旧版 ATTEMPT_INTERVAL_MS=200 的 miss 端细分 */
private const val FAIL_SHORT_DELAY_MS = 100L
private const val FAIL_LONG_DELAY_MS = 200L
/** 旧版全图阶段长边上限（超限降采样，控制 ZXing tryHarder 成本） */
private const val SCAN_MAX_EDGE = 1280
