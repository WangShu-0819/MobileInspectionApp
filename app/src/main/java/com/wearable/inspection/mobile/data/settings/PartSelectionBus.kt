package com.wearable.inspection.mobile.data.settings

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 跨二级页面通知现场采集页切换零件。
 * SharedPreferences 负责持久化，事件负责让已经存在的 WorkbenchViewModel 立即刷新。
 */
object PartSelectionBus {
    private val _flow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val flow = _flow.asSharedFlow()

    fun emit(partId: String) {
        _flow.tryEmit(partId)
    }
}
