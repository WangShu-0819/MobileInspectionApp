package com.wearable.inspection.mobile.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 采集批次实体
 *
 * 记录同一零件一次采集会话。每次用户在 LiveInspectionScreen 开始拍照时创建，
 * 所有视角照片归属到同一批次。
 */
@Entity(
    tableName = "capture_batches",
    foreignKeys = [
        ForeignKey(
            entity = PartEntity::class,
            parentColumns = ["id"],
            childColumns = ["partId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class CaptureBatchEntity(
    @PrimaryKey
    val batchId: String,
    val partId: String?,
    val partName: String?,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val viewCount: Int = 0
)
