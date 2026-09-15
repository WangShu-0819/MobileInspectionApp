package com.wearable.inspection.mobile.dpm

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * 会话内证据帧所有权与源帧关联。
 *
 * 追踪器持有的 Bitmap 只有在 [freeze] 返回或 [clear] 时才转移/回收。
 * GRID 临时帧在任务完成、取消或超时后立即从待处理集合移除。
 */
internal class DpmEvidenceFrameTracker {

    data class Snapshot(
        val bitmap: Bitmap,
        val roi: Rect?,
        val frameToken: Long,
        val frameTimeMs: Long,
        val isDecodeSuccess: Boolean,
        val decodedCode: String?,
        val decodeSource: DecodeSource?,
    )

    private data class Entry(
        val token: Long,
        val timeMs: Long,
        val bitmap: Bitmap,
        val roi: Rect?,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<Long, Entry>()
    private val pendingGridTokens = HashSet<Long>()
    private var lastToken: Long? = null
    private var successToken: Long? = null
    private var successResult: DpmAnalyzeResult? = null
    private var frozen = false

    val isFrozen: Boolean
        get() = synchronized(lock) { frozen }

    fun addFrame(token: Long, timeMs: Long, bitmap: Bitmap, roi: Rect?): Boolean = synchronized(lock) {
        if (frozen) {
            bitmap.recycle()
            return@synchronized false
        }
        entries[token] = Entry(token, timeMs, bitmap, roi?.let(::Rect))
        lastToken = token
        cleanupUnusedLocked()
        true
    }

    fun markGridSubmitted(token: Long) = synchronized(lock) {
        if (!frozen && entries.containsKey(token)) pendingGridTokens += token
    }

    fun markGridFinished(token: Long) = synchronized(lock) {
        pendingGridTokens -= token
        if (successToken != token && lastToken == token) {
            entries.remove(token)?.bitmap?.recycle()
            lastToken = null
        }
        cleanupUnusedLocked()
    }

    /**
     * 仅 DECOD​​ED 且 code 非空时才选择成功帧。sourceFrameToken 存在时绝不回退到最新帧。
     */
    fun recordDecodeSuccess(result: DpmAnalyzeResult, fallbackToken: Long? = null): Boolean = synchronized(lock) {
        if (frozen || result.status != DpmAnalyzeStatus.DECODED || result.code.isNullOrBlank()) return@synchronized false
        if (successToken != null) return@synchronized true
        val token = result.sourceFrameToken ?: fallbackToken ?: return@synchronized false
        if (!entries.containsKey(token)) return@synchronized false
        successToken = token
        successResult = result
        cleanupUnusedLocked()
        true
    }

    /** 冻结一次会话证据；只有 ECC 成功源帧才转移给保存层，否则全部回收并返回 null。 */
    fun freeze(): Snapshot? = synchronized(lock) {
        if (frozen) return@synchronized null
        frozen = true
        val selectedToken = successToken
        val selected = selectedToken?.let { entries[it] }
        entries.values.forEach { entry ->
            if (entry !== selected) entry.bitmap.recycle()
        }
        entries.clear()
        pendingGridTokens.clear()
        lastToken = null
        successToken = null
        val result = successResult
        successResult = null
        selected?.let {
            Snapshot(
                bitmap = it.bitmap,
                roi = it.roi,
                frameToken = it.token,
                frameTimeMs = it.timeMs,
                isDecodeSuccess = true,
                decodedCode = result?.code,
                decodeSource = result?.source,
            )
        }
    }

    /** 清理尚未转移给调用方的全部 Bitmap。 */
    fun clear() = synchronized(lock) {
        frozen = true
        entries.values.forEach { it.bitmap.recycle() }
        entries.clear()
        pendingGridTokens.clear()
        lastToken = null
        successToken = null
        successResult = null
    }

    private fun cleanupUnusedLocked() {
        val keep = setOfNotNull(lastToken, successToken) + pendingGridTokens
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.token !in keep) {
                iterator.remove()
                entry.bitmap.recycle()
            }
        }
    }
}
