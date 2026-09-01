package com.wearable.inspection.mobile.dpm

/**
 * Debug PNG 落盘配额（纯 JVM，可单测）：默认 0 配额 —— **Debug 包默认也不写**，
 * 只有显式 [request] 后才允许保存；单进程累计写满 [maxSets] 组后停止。
 * 线程安全（request 可能来自主线程，tryConsume 来自相机分析线程）。
 */
class DpmDumpBudget(private val maxSets: Int = DEFAULT_MAX_FRAME_SETS) {

    private val lock = Any()
    private var granted = 0
    private var used = 0

    /** 显式请求 [frameSets] 组落盘（累计不超过 [maxSets]，多余请求截断） */
    fun request(frameSets: Int = 1) = synchronized(lock) {
        granted = (granted + frameSets.coerceAtLeast(0)).coerceAtMost(maxSets)
    }

    /** 尝试消费一组配额。true = 本组允许落盘；配额耗尽后恒 false */
    fun tryConsume(): Boolean = synchronized(lock) {
        if (used >= granted) return false
        used++
        true
    }

    /** 已消费组数 */
    val usedSets: Int get() = synchronized(lock) { used }

    /** 已授予组数 */
    val grantedSets: Int get() = synchronized(lock) { granted }

    companion object {
        /** 单进程硬上限：约 30 组。一组配额 = 一帧全部文件（input + 各策略候选，见
         *  DpmPreprocessor.preprocess 的 tryConsume 位置）→ 30 组 ≈ 30 帧素材；
         *  仍是硬上限防 3.4GB 级缓存膨胀重演（externalCacheDir 可随时清理）。 */
        const val DEFAULT_MAX_FRAME_SETS = 30
    }
}
