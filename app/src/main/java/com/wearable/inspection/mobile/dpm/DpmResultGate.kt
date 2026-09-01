package com.wearable.inspection.mobile.dpm

/**
 * DPM 结果门控
 *
 * 控制扫码结果的去重和重复抑制：
 * - 新码立即响应
 * - 同码持续可见时抑制重复回调
 * - 达到 miss 阈值后重新允许同码
 * - reset/stop 清空状态
 *
 * 典型场景：
 * - 检测人员对准一个 DPM 码，相机持续扫到同一码 → 只回调一次
 * - 码移出视野（miss 累积达到阈值）→ 再次出现时重新回调
 * - 切换零件或重置扫码 → reset 清空状态
 */
class DpmResultGate(
    private val missThreshold: Int = DEFAULT_MISS_THRESHOLD,
    private val clock: DpmClock = SystemDpmClock()
) {
    companion object {
        /** 默认 miss 阈值：连续 miss 此次数后重新允许同码 */
        const val DEFAULT_MISS_THRESHOLD = 5
    }

    /** 上次通过门控的码值 */
    private var lastAcceptedValue: String? = null

    /** 上次通过门控的时间戳 */
    private var lastAcceptedTimeMs: Long = 0L

    /** 连续 miss 计数（解码失败或非同码） */
    private var consecutiveMisses: Int = 0

    /** 门控是否已停止 */
    @Volatile
    private var stopped: Boolean = false

    /**
     * 提交解码结果到门控
     *
     * @param result 解码结果（null 表示本帧未识别）
     * @return 通过门控的结果，null 表示被抑制
     */
    fun submit(result: DpmScanResult?): DpmScanResult? {
        if (stopped) return null

        if (result == null) {
            // 解码失败 → miss
            consecutiveMisses++
            return null
        }

        val currentValue = result.rawValue

        if (lastAcceptedValue == null) {
            // 首次识别 → 通过
            return accept(result)
        }

        if (currentValue == lastAcceptedValue) {
            // 同码
            return if (consecutiveMisses >= missThreshold) {
                // miss 超过阈值 → 重新允许
                accept(result)
            } else {
                // 同码持续可见 → 抑制
                consecutiveMisses = 0
                null
            }
        } else {
            // 新码 → 立即响应
            consecutiveMisses = 0
            return accept(result)
        }
    }

    /**
     * 接受结果：更新状态并返回
     */
    private fun accept(result: DpmScanResult): DpmScanResult {
        lastAcceptedValue = result.rawValue
        lastAcceptedTimeMs = clock.currentTimeMs()
        consecutiveMisses = 0
        return result
    }

    /**
     * 重置门控状态
     *
     * 用于切换零件、重新开始扫码等场景。
     */
    fun reset() {
        lastAcceptedValue = null
        lastAcceptedTimeMs = 0L
        consecutiveMisses = 0
        stopped = false
    }

    /**
     * 停止门控
     *
     * 停止后 submit 始终返回 null，直到 reset。
     */
    fun stop() {
        stopped = true
        lastAcceptedValue = null
        lastAcceptedTimeMs = 0L
        consecutiveMisses = 0
    }

    // ─── 测试辅助 ───

    /** 当前是否已接受过结果 */
    fun hasAccepted(): Boolean = lastAcceptedValue != null

    /** 当前连续 miss 次数 */
    fun getConsecutiveMisses(): Int = consecutiveMisses

    /** 最后接受的码值 */
    fun getLastAcceptedValue(): String? = lastAcceptedValue
}
