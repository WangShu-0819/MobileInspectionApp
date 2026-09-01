package com.wearable.inspection.mobile.dpm

/**
 * DPM 同码响应门 —— 「防连扫」与「连续扫同一码」的折中（原 3s 定时冷却的问题：
 * 成功后 3s 内再扫同一码被静默吞掉，产线同一型号零件码相同，表现为"扫一次成功后
 * 怎么扫都扫不出来"）。
 *
 * - **换码立即响应**：`code != lastCode` 直接放行 —— A 件扫完马上扫 B 件不等待；
 * - **同码离开视野后重新进入才响应**：`absentMisses` 每帧 miss 累计、命中清零
 *   （码持续停在镜头里 → 恒为 0 → 不重复切件，防连扫）；达 [REARM_MISSES] 后
 *   同码再出现即放行 —— 移开再放回（哪怕同一码）无需等任何定时器，支持产线
 *   连续扫同一型号零件码；
 * - **兜底超时（仅扫码弹窗模式）**：同码持续在视野内超过 [MAX_HOLD_RESPOND_MS] 仍
 *   放行 —— 重开扫码页时码未移开也能扫到；巡检匹配常驻模式下该兜底禁用（码持续
 *   入镜不得重复切件/重置会话，防 5s 周期重置）。
 *
 * 线程模型：仅在 DpmAnalyzer 分析线程访问（detect 单线程），无需同步。
 * [now] 可注入时钟用于单测。
 */
class DpmRespondGate(
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /** 最近一次响应的码（同码基准） */
    var lastCode: String? = null
        private set

    /** 最近一次响应时刻（[now] 时钟） */
    var lastRespondedAt = 0L
        private set

    /** 码离开视野的累计帧数（命中即清零；达 [REARM_MISSES] 后同码重新放行） */
    var absentMisses = 0
        private set

    /** 本帧命中（码在视野内）：复位缺席计数 */
    fun onHit() {
        absentMisses = 0
    }

    /** 本帧未命中：累计缺席计数 */
    fun onMiss() {
        absentMisses++
    }

    /**
     * 命中码 [code] 是否应响应；[rearmOnHold] = 允许同码持续入镜的超时重放行
     * （仅扫码弹窗模式传 true：重开扫码页码未移开也能扫到）。
     */
    fun shouldRespond(code: String, rearmOnHold: Boolean = true): Boolean {
        if (code != lastCode) return true
        if (absentMisses >= REARM_MISSES) return true
        // 巡检匹配常驻模式下禁用超时重放行：码持续停在镜头里不得重复切件/重置会话
        return rearmOnHold && now() - lastRespondedAt >= MAX_HOLD_RESPOND_MS
    }

    /** 记录一次响应：更新同码基准与响应时刻、复位缺席计数 */
    fun onResponded(code: String) {
        lastCode = code
        lastRespondedAt = now()
        absentMisses = 0
    }

    /** 重置门控状态（切换零件、重新开始扫码等场景） */
    fun reset() {
        lastCode = null
        lastRespondedAt = 0L
        absentMisses = 0
    }

    companion object {
        /** 同码需离开视野 ≥ 该帧数（≈ 10 × 200ms = 2s 持续 miss）后重新进入才响应 */
        const val REARM_MISSES = 10

        /** 同码持续在视野内的最大响应间隔（兜底：重开扫码页时码未移开也能扫到） */
        const val MAX_HOLD_RESPOND_MS = 5000L
    }
}
