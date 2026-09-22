package com.wearable.inspection.mobile.ui.screens

import com.wearable.inspection.mobile.registration.RegistrationStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程级内存缓存，用于在 CaptureComparison 和 ViewConfirmation 之间传递 Session ROI 投影坐标。
 *
 * Session ROI 是内存态临时数据（不持久化到数据库或路由参数），
 * 使用此单例在页面导航期间暂存，ViewConfirmation 读取即消费。
 */
object SessionRoiRegistry {

    data class Entry(
        val sessionRois: List<SessionRoi>,
        val registrationStatus: RegistrationStatus,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    private fun key(batchId: String, photoId: Long, viewIndex: Int) =
        "${batchId}_${photoId}_$viewIndex"

    /** 写入 Session ROI 数据。覆盖同 key 的已有条目。 */
    fun write(
        batchId: String,
        photoId: Long,
        viewIndex: Int,
        sessionRois: List<SessionRoi>,
        registrationStatus: RegistrationStatus,
    ) {
        cache[key(batchId, photoId, viewIndex)] = Entry(sessionRois, registrationStatus)
    }

    /** 读取并消费（删除）Session ROI 数据。返回 null 表示无缓存。 */
    fun readAndConsume(batchId: String, photoId: Long, viewIndex: Int): Entry? =
        cache.remove(key(batchId, photoId, viewIndex))

    /** 清除指定 key 的缓存（幂等）。 */
    fun clear(batchId: String, photoId: Long, viewIndex: Int) {
        cache.remove(key(batchId, photoId, viewIndex))
    }

    /** 清除全部缓存（测试用）。 */
    fun clearAll() {
        cache.clear()
    }
}
