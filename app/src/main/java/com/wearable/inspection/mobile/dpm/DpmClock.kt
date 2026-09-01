package com.wearable.inspection.mobile.dpm

/**
 * 时钟接口（可注入，便于测试）
 */
interface DpmClock {
    /** 当前时间戳（毫秒） */
    fun currentTimeMs(): Long
}

/**
 * 系统时钟实现
 */
class SystemDpmClock : DpmClock {
    override fun currentTimeMs(): Long = System.currentTimeMillis()
}
