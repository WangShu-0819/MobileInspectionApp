package com.wearable.inspection.mobile.data.settings

import android.content.Context
import android.content.SharedPreferences

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
}
