package com.wearable.inspection.mobile.ui.navigation

sealed class Screen(val route: String) {
    // 一级页面
    object LiveInspection : Screen("live_inspection")  // 现场采集
    object TraceRecords : Screen("trace_records")      // 追溯记录
    object Profile : Screen("profile")                 // 我的

    // 二级页面
    object CameraPreview : Screen("camera_preview/{partId}") {
        const val ARG_PART_ID = "partId"
        fun createRoute(partId: String) = "camera_preview/$partId"
    }
    object InspectionResult : Screen("inspection_result/{sessionId}") {
        const val ARG_SESSION_ID = "sessionId"
        fun createRoute(sessionId: String) = "inspection_result/$sessionId"
    }
    object TemplateConfig : Screen("template_config")              // 模板配置
    object TemplateDetail : Screen("template_detail/{templateId}") {
        const val ARG_TEMPLATE_ID = "templateId"
        fun createRoute(templateId: String) = "template_detail/$templateId"
    }
    object AppSettings : Screen("app_settings")                    // 应用设置
    object PartManagement : Screen("part_management")              // 零件管理
    object DpmScan : Screen("dpm_scan")                            // DPM 扫码
    object StampOcr : Screen("stamp_ocr")                          // 钢印 OCR
    object TemplatePackages : Screen("template_packages")          // 模板包
    object ResultManagement : Screen("result_management")          // 检测结果管理
}
