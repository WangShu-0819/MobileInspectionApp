package com.wearable.inspection.mobile.dpm

/**
 * 重型网格任务提交门控纯状态机（DpmAnalyzer 委托，纯 JVM 可单测；时钟注入）。
 *
 * 状态与决策：
 * - [scanModeActive]：扫码模式开关。false 时 [canSubmit] 恒 false ——
 *   普通非扫码画面绝不启动 ImportedDpmScanner 重型网格重建；
 * - [setScanModeActive]：每次切换 generation+1 并清零 [missStreak]/[lastAttemptAt]
 *   —— 进入扫码模式时历史 miss 不生效（不因旧画面立即提交），退出时旧任务
 *   结果经 [belongsToCurrentSession] 判定为过期、不得回灌；
 * - [canSubmit]：scanModeActive && 无进行中任务 && miss 达门槛 && 冷却已过；
 * - [markSubmitted]：提交或被质量门控拒绝后都记录（拒绝帧同样吃冷却，
 *   避免持续坏帧下每帧重复跑质量判断/刷日志）。
 *
 * 线程模型：状态只在 DpmAnalyzer 分析线程读改（detect 单线程）；[scanModeActive]/
 * [generation] 被网格任务线程经 [belongsToCurrentSession] 读取 —— 这两字段 volatile，
 * 读取到旧值也只会导致"结果被丢弃"，无竞态损害。
 */
class DpmGridGate(
    private val missThreshold: Int,
    private val cooldownMs: Long,
) {

    @Volatile
    var scanModeActive = false
        private set

    /** 扫码会话代数：每次开关扫码模式 +1；任务提交时快照，完成时比对防旧结果回灌 */
    @Volatile
    var generation = 0L
        private set

    var missStreak = 0
        private set

    var lastAttemptAt = 0L
        private set

    /** 开关扫码模式：重置 miss 连击与冷却，代数 +1（历史 miss 不影响新会话） */
    fun setScanModeActive(active: Boolean) {
        if (scanModeActive == active) return
        scanModeActive = active
        generation++
        missStreak = 0
        lastAttemptAt = 0L
    }

    /** 分析帧 miss（detect 末尾调用；[onHit] 清零） */
    fun onMiss() {
        missStreak++
    }

    /** 分析帧命中（快速路径/网格回灌均调用） */
    fun onHit() {
        missStreak = 0
    }

    /**
     * 是否允许提交网格任务（不修改状态）：
     * 扫码模式开启 && 无进行中任务（[gridActive] 由调用方提供）&&
     * miss 达门槛 && 距上次尝试 ≥ 冷却。
     */
    fun canSubmit(nowMs: Long, gridActive: Boolean): Boolean {
        if (!scanModeActive) return false
        if (gridActive) return false
        if (missStreak < missThreshold) return false
        if (nowMs - lastAttemptAt < cooldownMs) return false
        return true
    }

    /** 记录一次提交尝试时刻（提交或质量拒绝后调用 —— 两者都进入冷却） */
    fun markSubmitted(nowMs: Long) {
        lastAttemptAt = nowMs
    }

    /**
     * 提交时快照的 generation 是否仍属于当前会话且扫码模式仍开启。
     * 网格任务完成时调用：false → 结果过期（退出过扫码模式/重开过），必须丢弃。
     */
    fun belongsToCurrentSession(generationAtSubmit: Long): Boolean =
        scanModeActive && generationAtSubmit == generation
}
