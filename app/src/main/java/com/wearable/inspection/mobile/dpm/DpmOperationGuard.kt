package com.wearable.inspection.mobile.dpm

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用级 DPM 操作并发协调器。
 *
 * 清理（cleanup）与所有非清理操作（扫码、保存、绑定、导出）共享同一门禁：
 * - 清理期间禁止任何新操作进入；
 * - 任何操作活跃时禁止启动清理；
 * - 清理使用 Mutex 保证同一时刻只有一个清理执行。
 *
 * Repository 和 ViewModel 均通过此单例协调，不互相依赖。
 *
 * 推荐使用 [acquireLease] 获取 [OperationLease] 令牌，release 幂等，防止重复 end。
 * 向后兼容的 [begin]/[end] 仍保留，但新代码应优先使用 lease。
 */
object DpmOperationGuard {

    private const val TAG = "DpmOperationGuard"

    /**
     * 当前活跃的非清理操作数。
     * 由 begin/end 递增/递减；volatile 保证跨线程可见。
     */
    @Volatile
    var activeOperations: Int = 0
        private set

    /** 清理是否正在进行中。仅在 cleanupExclusive 持有 mutex 期间为 true。 */
    @Volatile
    var cleanupInProgress: Boolean = false
        private set

    /** 清理专用 Mutex，保证同一时刻仅有一个清理任务。 */
    private val cleanupMutex = Mutex()

    /** begin/end 配对 Mutex，保证 activeOperations 的检查与递增原子化。 */
    private val gateMutex = Mutex()

    /**
     * 操作租约令牌。持有者负责在操作完成时调用 [release]。
     * release 是幂等的：多次调用只触发一次 end()，防止异步错配导致的重复释放。
     *
     * @property leaseId 唯一标识，用于日志追踪
     */
    data class OperationLease(val leaseId: String = UUID.randomUUID().toString().take(8)) {

        private val released = AtomicBoolean(false)

        /** 释放门禁。幂等：多次调用只有第一次会触发 end()。 */
        suspend fun release() {
            if (released.compareAndSet(false, true)) {
                end()
                Log.d(TAG, "lease[$leaseId] released")
            } else {
                Log.d(TAG, "lease[$leaseId] already released, skipping")
            }
        }

        /** 是否已释放（用于测试断言）。 */
        val isReleased: Boolean get() = released.get()
    }

    /**
     * 获取操作租约。清理正在进行时返回 null。
     * 成功后持有者必须在操作完成时调用 [OperationLease.release]。
     *
     * @return 租约令牌，或 null（清理进行中，操作应拒绝）
     */
    suspend fun acquireLease(): OperationLease? = gateMutex.withLock {
        if (cleanupInProgress) {
            Log.d(TAG, "acquireLease: rejected, cleanupInProgress")
            return@withLock null
        }
        activeOperations++
        val lease = OperationLease()
        Log.d(TAG, "acquireLease: activeOperations=$activeOperations, lease=${lease.leaseId}")
        lease
    }

    /**
     * 注册一个非清理 DPM 操作（向后兼容）。
     * 清理正在进行时返回 false，调用方应拒绝本次操作。
     * 成功后必须配对调用 [end]。
     *
     * 新代码推荐使用 [acquireLease] 替代。
     */
    suspend fun begin(): Boolean = gateMutex.withLock {
        if (cleanupInProgress) {
            Log.d(TAG, "begin: rejected, cleanupInProgress")
            return@withLock false
        }
        activeOperations++
        Log.d(TAG, "begin: activeOperations=$activeOperations")
        true
    }

    /**
     * 注销一个非清理 DPM 操作（向后兼容）。
     * 必须在 begin() 成功后调用，且与 begin 在同一逻辑配对。
     */
    suspend fun end() = gateMutex.withLock {
        if (activeOperations > 0) activeOperations--
        Log.d(TAG, "end: activeOperations=$activeOperations")
    }

    /**
     * 获取清理独占锁。
     * 任何非清理操作活跃时返回 false。
     * 返回的函数必须在清理完成后调用以释放锁。
     *
     * @return (acquired, releaseFn)；acquired=false 时 releaseFn 为空操作
     */
    suspend fun cleanupExclusive(): Pair<Boolean, suspend () -> Unit> {
        // 快速检查：清理已在进行中则立即拒绝（避免阻塞在 mutex 上）
        if (cleanupInProgress) {
            Log.d(TAG, "cleanupExclusive: rejected, cleanupInProgress already true")
            return false to {}
        }
        // 获取 gateMutex 检查 activeOperations，再设置 cleanupInProgress。
        // gateMutex 保证检查→设置期间无新的 begin()/acquireLease() 插入。
        val acquired = gateMutex.withLock {
            if (activeOperations > 0 || cleanupInProgress) {
                Log.d(TAG, "cleanupExclusive: rejected, activeOperations=$activeOperations, cleanupInProgress=$cleanupInProgress")
                false
            } else {
                cleanupInProgress = true
                Log.d(TAG, "cleanupExclusive: cleanupInProgress=true")
                true
            }
        }
        if (!acquired) return false to {}

        // cleanupMutex 保证同一时刻仅一个清理任务。
        cleanupMutex.lock()
        val releaseFn: suspend () -> Unit = {
            cleanupMutex.unlock()
            gateMutex.withLock {
                cleanupInProgress = false
                Log.d(TAG, "cleanupExclusive: cleanupInProgress=false")
            }
        }
        return true to releaseFn
    }

    /** 是否有任何 DPM 操作正在进行（清理或非清理）。 */
    val isAnyActive: Boolean get() = cleanupInProgress || activeOperations > 0

    /** 测试专用：重置门禁状态到初始值，必须在测试 setUp/tearDown 中调用。 */
    fun resetForTesting() {
        activeOperations = 0
        cleanupInProgress = false
    }
}
