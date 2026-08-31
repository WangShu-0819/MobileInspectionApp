package com.wearable.inspection.mobile.camera

/**
 * 相机错误类型
 */
sealed class CameraError(
    val code: String,
    val message: String,
    val recoverable: Boolean = true
) {
    /** 权限被拒绝 */
    object PermissionDenied : CameraError(
        code = "PERMISSION_DENIED",
        message = "相机权限被拒绝",
        recoverable = true
    )

    /** 权限被永久拒绝（勾选"不再询问"） */
    object PermissionPermanentlyDenied : CameraError(
        code = "PERMISSION_PERMANENTLY_DENIED",
        message = "相机权限被永久拒绝，请前往系统设置开启",
        recoverable = true
    )

    /** 未找到后置相机 */
    object NoBackCamera : CameraError(
        code = "NO_BACK_CAMERA",
        message = "未找到后置相机",
        recoverable = false
    )

    /** 相机启动超时 */
    object CameraTimeout : CameraError(
        code = "CAMERA_TIMEOUT",
        message = "相机启动超时，请重试",
        recoverable = true
    )

    /** 相机被其他应用占用 */
    object CameraInUse : CameraError(
        code = "CAMERA_IN_USE",
        message = "相机被其他应用占用",
        recoverable = true
    )

    /** 未知错误 */
    data class Unknown(val errorMessage: String) : CameraError(
        code = "UNKNOWN",
        message = errorMessage,
        recoverable = false
    )
}
