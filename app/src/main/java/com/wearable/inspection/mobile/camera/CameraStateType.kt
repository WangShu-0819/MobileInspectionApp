package com.wearable.inspection.mobile.camera

/**
 * 相机状态类型（对应 CameraX CameraState.Type）
 */
enum class CameraStateType {
    /** 等待打开 */
    PENDING_OPEN,

    /** 已打开，预览可用 */
    OPEN,

    /** 已关闭 */
    CLOSED,

    /** 发生错误 */
    ERROR
}
