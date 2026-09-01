package com.wearable.inspection.mobile.camera

/**
 * 相机工作模式
 *
 * 每个模式声明是否需要 ImageAnalysis 和 ImageCapture UseCase。
 * CameraController 根据模式配置构建和绑定对应的 UseCase 组合。
 *
 * @param needsAnalysis 是否需要 ImageAnalysis（帧分析）
 * @param needsCapture 是否需要 ImageCapture（拍照）
 */
enum class CameraMode(
    val needsAnalysis: Boolean,
    val needsCapture: Boolean
) {
    /** 空闲：仅 Preview，无分析和拍照 */
    IDLE(needsAnalysis = false, needsCapture = false),

    /** 现场检测：Preview + Analysis + Capture */
    INSPECTION(needsAnalysis = true, needsCapture = true),

    /** DPM 扫码：Preview + Analysis（实时扫码），无拍照 */
    DPM_SCAN(needsAnalysis = true, needsCapture = false),

    /** 钢印 OCR：Preview + Analysis + Capture */
    STAMP_OCR(needsAnalysis = true, needsCapture = true),

    /** 模板拍摄：Preview + Capture（拍照取样），无实时分析 */
    TEMPLATE_CAPTURE(needsAnalysis = false, needsCapture = true);
}
