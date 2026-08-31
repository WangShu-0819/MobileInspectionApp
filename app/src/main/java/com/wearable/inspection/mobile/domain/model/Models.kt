package com.wearable.inspection.mobile.domain.model

/**
 * 检测状态
 */
enum class InspectionStatus {
    PASS,       // 通过
    FAIL,       // 不通过
    REVIEW,     // 需复核
    ERROR,      // 执行异常
    SKIPPED     // 跳过
}

/**
 * 检测类型
 */
enum class InspectionType {
    PRESENCE_GENERIC,   // 通用有无
    HOLE_PRESENCE,      // 孔有无
    SCREW_PRESENCE,     // 螺钉有无
    THREAD_PRESENCE,    // 螺纹有无
    SMALL_PART_PRESENCE // 小部件有无
}

/**
 * 零件信息
 */
data class PartInfo(
    val id: String,
    val name: String,
    val model: String? = null,
    val dpmCode: String? = null
)

/**
 * 设备状态
 */
sealed class DeviceStatus {
    object Disconnected : DeviceStatus()
    object Connecting : DeviceStatus()
    object Connected : DeviceStatus()
    data class Error(val message: String) : DeviceStatus()
}
