package com.wearable.inspection.mobile.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

private const val TAG = "BATTERY_OPT"

/**
 * 电池优化白名单助手（后台保活的辅助通道）。
 *
 * 前台服务 + PARTIAL_WAKE_LOCK 已能保证息屏运行；把应用加入电池优化白名单
 * 可进一步避免 OEM 厂商的激进省电策略（如深度休眠/冻结）杀掉后台进程，
 * 属「最佳努力」增强，非必需。
 */
object BatteryOptimizationHelper {

    /** 当前应用是否已被系统加入电池优化白名单。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
    }

    /**
     * 一键申请加入白名单：
     * 优先走系统「忽略电池优化」授权对话框（需要 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限）；
     * 权限/页面不可用时回退到系统电池优化设置页，由用户手动选择。
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val appContext = context.applicationContext
        val packageUri = Uri.parse("package:${appContext.packageName}")
        val granted = runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            true
        }.getOrElse {
            Log.w(TAG, "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS unavailable: ${it.message}")
            false
        }
        if (!granted) {
            runCatching {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
            }.onFailure {
                Log.w(TAG, "battery settings unavailable: ${it.message}")
            }
        }
    }
}
