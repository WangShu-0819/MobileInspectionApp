package com.wearable.inspection.mobile.dpm

/**
 * 网格重建扫描的中止原因。
 * - [NONE]：未中止（正常完成或正常 miss）；
 * - [DEADLINE]：绝对 deadline（System.nanoTime）到达 —— 预算耗尽；
 * - [CANCELLED]：扫码会话退出/任务被取消 —— 由 [DpmScanControl.isCancelled] 探测。
 */
enum class DpmAbortReason { NONE, DEADLINE, CANCELLED }

/**
 * 协作式扫描控制（纯 JVM，可单测）：绝对 deadline + 取消探测，供
 * ImportedDpmScanner 在**所有外层循环**主动调用 [shouldAbort] ——
 * withTimeoutOrNull 无法打断同步 CPU/OpenCV 循环，本结构才是真正的截止机制
 * （withTimeoutOrNull 仅保留为外层保险）。
 *
 * 语义：
 * - 一旦返回过 true，[abortReason] 锁存，后续 [shouldAbort] 恒 true —— 下游
 *   全部快速短路，扫描立即结束；
 * - [isCancelled] 每次调用都探测一次（上游注入 `!scanModeActive` 之类的
 *   volatile 读，成本可忽略）；
 * - [deadlineNanos] 为绝对时间（System.nanoTime 时钟域），由调用方在任务
 *   提交时以 `System.nanoTime() + budgetMs * 1_000_000` 计算；
 * - [shouldAbort] 的 [nowNanos] 参数可注入 —— 单测不依赖真实时钟。
 */
class DpmScanControl(
    private val deadlineNanos: Long,
    private val isCancelled: () -> Boolean = { false },
) {

    @Volatile
    private var reason = DpmAbortReason.NONE

    /** 当前中止原因（[DpmAbortReason.NONE] = 尚未中止）。锁存：首次中止后不再变化 */
    val abortReason: DpmAbortReason
        get() = reason

    /** 是否应立即停止处理。返回 true 时 [abortReason] 已确定（DEADLINE/CANCELLED） */
    fun shouldAbort(nowNanos: Long = System.nanoTime()): Boolean {
        if (reason != DpmAbortReason.NONE) return true
        if (isCancelled()) {
            reason = DpmAbortReason.CANCELLED
            return true
        }
        if (nowNanos >= deadlineNanos) {
            reason = DpmAbortReason.DEADLINE
            return true
        }
        return false
    }
}

/** 空安全的便捷中止检查：control 为 null（无预算约束的批量验证/单测路径）时恒不中止 */
internal fun DpmScanControl?.aborted(): Boolean = this != null && shouldAbort()
