package com.wearable.inspection.mobile.data.entity

/**
 * ROI 目标属性类型
 *
 * 用于后续选择一致的 ROI 检测算法：
 * - THREAD → Thread 检测
 * - NUT → Nut 检测
 * - FEATURE → Feature 检测
 */
enum class RoiTargetType(val displayName: String) {
    THREAD("螺纹"),
    NUT("螺母"),
    FEATURE("部件");

    companion object {
        /**
         * 从枚举名称解析，无效值返回 null
         */
        fun fromName(name: String?): RoiTargetType? {
            if (name == null) return null
            return try {
                valueOf(name)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
