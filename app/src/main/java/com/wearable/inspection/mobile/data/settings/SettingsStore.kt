package com.wearable.inspection.mobile.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.wearable.inspection.mobile.dpm.DpmDimensionMode

enum class PreviewDisplayMode(
    val label: String,
    val description: String,
) {
    ORIGINAL("原比例", "完整显示相机画面，可能保留黑边"),
    FILL("填充预览", "铺满预览区域，边缘可能被裁切");

    companion object {
        fun parse(value: String?): PreviewDisplayMode =
            entries.firstOrNull { it.name == value } ?: ORIGINAL
    }
}

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mobile_inspection_settings", Context.MODE_PRIVATE)

    var realtimeMatchEnabled: Boolean
        get() = prefs.getBoolean("realtime_match", true)
        set(value) = prefs.edit().putBoolean("realtime_match", value).apply()

    var matchFeedbackEnabled: Boolean
        get() = prefs.getBoolean("match_feedback_enabled", true)
        set(value) = prefs.edit().putBoolean("match_feedback_enabled", value).apply()

    var selectedPartId: String?
        get() = prefs.getString("selected_part_id", null)
        set(value) = prefs.edit().putString("selected_part_id", value).apply()

    /**
     * DPM 网格重建尺寸模式（持久化 DpmDimensionMode.name）。
     * 默认 AUTO，非法值回退 AUTO。
     */
    var dpmDimensionMode: DpmDimensionMode
        get() {
            val name = prefs.getString("dpm_dimension_mode", null)
                ?: return DpmDimensionMode.AUTO
            return DpmDimensionMode.parse(name)
        }
        set(value) = prefs.edit().putString("dpm_dimension_mode", value.name).apply()

    /**
     * 实时预览显示模式。只影响 PreviewView 的显示缩放，不改变相机流和拍照文件。
     */
    var previewDisplayMode: PreviewDisplayMode
        get() = PreviewDisplayMode.parse(prefs.getString("preview_display_mode", null))
        set(value) = prefs.edit().putString("preview_display_mode", value.name).apply()
}
