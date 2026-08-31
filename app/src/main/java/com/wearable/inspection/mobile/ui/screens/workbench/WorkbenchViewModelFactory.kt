package com.wearable.inspection.mobile.ui.screens.workbench

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.wearable.inspection.mobile.MobileInspectionApp
import com.wearable.inspection.mobile.data.repository.InspectionRepository
import com.wearable.inspection.mobile.data.settings.SettingsStore

/**
 * WorkbenchViewModel 的工厂类，提供所需的依赖
 */
class WorkbenchViewModelFactory(
    private val repository: InspectionRepository,
    private val settings: SettingsStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WorkbenchViewModel::class.java)) {
            return WorkbenchViewModel(repository, settings) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

/**
 * 便捷方法：从 Application 上下文创建 WorkbenchViewModelFactory
 */
fun createWorkbenchViewModelFactory(context: Application): WorkbenchViewModelFactory {
    val app = context as MobileInspectionApp
    return WorkbenchViewModelFactory(
        repository = app.repository,
        settings = app.settings
    )
}
