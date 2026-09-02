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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 组装版 DpmAnalyzer — 从旧版 Wearable Inspection 的 camera/DpmAnalyzer.kt 迁移。
 *
 * 核心算法忠实保留：
 * - ZXing 主解码 + ML Kit 兜底
 * - 4 种预处理策略轮转（普通极性 + 反色双试）
 * - 中心 ROI 裁切（默认 1200×1200，↓2/3，stride=5）
 * - 200ms 节流（逐次追踪 success / FAIL_SHORT_DELAY_MS / FAIL_LONG_DELAY_MS）
 * - 单飞分析（AtomicBoolean）
 * - 漏检触发自动对焦回调
 * - 栅格触发（DpmGridGate 门控 + DpmScanControl 截止时间 + 异步执行）
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
) {

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
        if (focusStatus == FocusStatus.OUT_OF_FOCUS) {
            return DpmAnalyzeResult(status = DpmAnalyzeStatus.NO_CODE)
        }
        if (!analysisRunning.compareAndSet(false, true)) {
            return DpmAnalyzeResult(status = DpmAnalyzeStatus.THROTTLED)
        }
        try {
            val throttleResult = shouldAllowAnalysis()
            if (throttleResult != DpmAnalyzeStatus.PROCEED) {
                return DpmAnalyzeResult(status = throttleResult)
            }
            val decodedCode = performMultiStrategyDecode(frame, frameRotation, scanRoi, scanControl)
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

    private suspend fun performMultiStrategyDecode(
        frame: Bitmap,
        frameRotation: Int,
        scanRoi: Rect?,
        scanControl: DpmScanControl?,
    ): DecodeResult? {
        val processingFrame = applyFrameTransforms(frame, frameRotation, scanRoi)
        val gray = bitmapToGray(processingFrame)
        val w = processingFrame.width
        val h = processingFrame.height
        val strategies = DpmPreprocessor.strategiesForFrame(frameCount++)
        for (strategy in strategies) {
            if (scanControl?.aborted() == true) break
            val candidates = DpmPreprocessor.preprocess(gray, w, h, strategy)
            for (candidate in candidates) {
                if (scanControl?.aborted() == true) break
                // 正常极性
                decodePixels(candidate.pixels, candidate.width, candidate.height, candidate.binarizer)?.let { return it }
                // 反色双试
                val inverted = invertBytes(candidate.pixels)
                decodePixels(inverted, candidate.width, candidate.height, candidate.binarizer)?.let { return it }
            }
        }
        triggerGridDecode(gray, w, h, scanControl)
        return null
    }

    private suspend fun decodePixels(pixels: ByteArray, w: Int, h: Int, binarizer: DpmBinarizer): DecodeResult? {
        val bitmap = grayBytesToBitmap(pixels, w, h)
        zxingDecoder.decode(bitmap)?.let { return DecodeResult(it.rawValue, DecodeSource.ZXING) }
        mlKitDecoder.decode(bitmap)?.let { return DecodeResult(it.rawValue, DecodeSource.ML_KIT) }
        return null
    }

    private fun applyFrameTransforms(frame: Bitmap, frameRotation: Int, scanRoi: Rect?): Bitmap {
        var result = if (scanRoi != null && !scanRoi.isEmpty) {
            val clamped = clampRect(scanRoi, frame.width, frame.height)
            createBitmap(frame, clamped.left, clamped.top, clamped.width(), clamped.height())
        } else {
            cropCenterRegion(frame, config.centerRoiWidth, config.centerRoiHeight)
        }
        if (frameRotation != 0) {
            result = rotateBitmap(result, frameRotation.toFloat())
        }
        // 旧版 DPM_ROI_TARGET_WIDTH=400：ROI 预处理前缩放到 400px（保持宽高比）
        if (result.width > config.roiTargetWidth) {
            val scale = config.roiTargetWidth.toFloat() / result.width
            result = Bitmap.createScaledBitmap(result, config.roiTargetWidth, (result.height * scale).toInt(), true)
        }
        return result
    }

    private fun cropCenterRegion(bitmap: Bitmap, roiW: Int, roiH: Int): Bitmap {
        val maxW = minOf(roiW, bitmap.width)
        val maxH = minOf(roiH, bitmap.height)
        val left = (bitmap.width - maxW) / 2
        val top = (bitmap.height - maxH) / 2
        return createBitmap(bitmap, left, top, maxW, maxH)
    }

    private fun downscaleBitmap(bitmap: Bitmap, divisor: Int): Bitmap {
        val w = bitmap.width / divisor
        val h = bitmap.height / divisor
        if (w <= 0 || h <= 0) return bitmap
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
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
                val result = DpmGridReconstructor.reconstruct(gray, w, h, DpmDimensionMode.AUTO, scanControl)
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
        return DpmAnalyzeResult(
            status = DpmAnalyzeStatus.DECODED,
            code = code,
            isDuplicated = false,
            source = source,
        )
    }

    private fun handleMiss(now: Long): DpmAnalyzeResult {
        respondGate.onMiss()
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
    /** 旧版 CENTER_ROI_RATIO=0.5f → 中心 50% 区域（默认1200×1200） */
    val centerRoiWidth: Int = 1200,
    val centerRoiHeight: Int = 1200,
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
