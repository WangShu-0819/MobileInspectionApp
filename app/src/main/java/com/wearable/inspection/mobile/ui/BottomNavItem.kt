package com.wearable.inspection.mobile.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    object LiveInspection : BottomNavItem("live_inspection", "现场采集", Icons.Filled.CameraAlt)
    object TraceRecords : BottomNavItem("trace_records", "追溯记录", Icons.Filled.History)
    object Profile : BottomNavItem("profile", "我的", Icons.Filled.Person)
}
