package com.wearable.inspection.mobile

import android.app.Application
import android.content.Context
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.wearable.inspection.mobile.data.db.AppDatabase
import com.wearable.inspection.mobile.data.repository.InspectionRepository
import com.wearable.inspection.mobile.data.settings.SettingsStore

/**
 * 应用入口：提供数据库 / 仓库 / 设置的单例
 */
class MobileInspectionApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val repository: InspectionRepository by lazy {
        InspectionRepository(
            database = database,
            context = applicationContext,
            partDao = database.partDao(),
            templateDao = database.templateDao(),
            roiDao = database.roiDao(),
            sessionDao = database.inspectionSessionDao(),
            roiRecordDao = database.roiRecordDao()
        )
    }
    val settings: SettingsStore by lazy { SettingsStore(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 初始化 OpenCV - 使用 try-catch 防止崩溃
        try {
            val cvOk = OpenCVLoader.initLocal()
            Log.i("MobileInspectionApp", "OpenCV initialized=$cvOk")
            if (cvOk) {
                Core.setNumThreads(2)
            } else {
                Log.w("MobileInspectionApp", "OpenCV initialization failed - some features may not work")
            }
        } catch (e: Exception) {
            Log.e("MobileInspectionApp", "OpenCV initialization error", e)
        }

        // 预填充数据 - 使用 SupervisorJob 防止种子数据异常导致应用崩溃
        appScope.launch {
            try {
                repository.seedIfEmpty()
            } catch (e: Exception) {
                Log.e("MobileInspectionApp", "Failed to seed database", e)
            }
        }
    }

    companion object {
        lateinit var instance: MobileInspectionApp
            private set

        fun repository(context: Context): InspectionRepository =
            (context.applicationContext as MobileInspectionApp).repository

        fun settings(context: Context): SettingsStore =
            (context.applicationContext as MobileInspectionApp).settings
    }
}
