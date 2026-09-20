package com.wearable.inspection.mobile.data.entity

/**
 * ROI 目标属性类型
 *
 * 检测路由映射（NanoDetDecisionPolicy.classIndex）：
 * - NUT → class 0 / nut
 * - THREAD → class 1 / thread
 * - BOLT → class 2 / bolt
 * - NUTSERT → class 3 / nutsert
 * - FEATURE → 不支持检测 / 模型未执行
 */
enum class RoiTargetType(val displayName: String) {
    THREAD("螺纹"),
    NUT("螺母"),
    BOLT("螺栓"),
    NUTSERT("铆螺母"),
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
