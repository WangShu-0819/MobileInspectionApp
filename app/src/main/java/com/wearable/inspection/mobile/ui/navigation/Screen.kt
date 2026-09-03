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
    object TemplateConfig : Screen("template_config")              // 模板配置（重定向到 PartList）
    object PartList : Screen("part_list")                          // 零件列表
    object PartDetail : Screen("part_detail/{partId}") {           // 零件详情（视角网格）
        const val ARG_PART_ID = "partId"
        fun createRoute(partId: String) = "part_detail/${android.net.Uri.encode(partId)}"
    }
    object TemplateDetail : Screen("template_detail/{templateId}") {
        const val ARG_TEMPLATE_ID = "templateId"
        fun createRoute(templateId: String) = "template_detail/$templateId"
    }
    object RoiEditor : Screen("roi_editor/{templateId}") {         // ROI 编辑器
        const val ARG_TEMPLATE_ID = "templateId"
        fun createRoute(templateId: String) = "roi_editor/${android.net.Uri.encode(templateId)}"
    }
    object AppSettings : Screen("app_settings")                    // 应用设置
    object PartManagement : Screen("part_management")              // 零件管理
    object DpmScan : Screen("dpm_scan")                            // DPM 扫码
    object DpmBind : Screen("dpm_bind/{partId}") {
        const val ARG_PART_ID = "partId"
        fun createRoute(partId: String) = "dpm_bind/${android.net.Uri.encode(partId)}"
    }
    object StampOcr : Screen("stamp_ocr")                          // 钢印 OCR
    object TemplatePackages : Screen("template_packages")          // 模板包
    object ResultManagement : Screen("result_management")          // 检测结果管理
    object TemplateCapture : Screen("template_capture/{partId}/{templateId}") {
        const val ARG_PART_ID = "partId"
        const val ARG_TEMPLATE_ID = "templateId"
        /** 新增 View 拍摄 */
        fun createRoute(partId: String) = "template_capture/$partId/"
        /** 指定 View 重拍 */
        fun createRecaptureRoute(partId: String, templateId: String) =
            "template_capture/${android.net.Uri.encode(partId)}/${android.net.Uri.encode(templateId)}"
    }
}
