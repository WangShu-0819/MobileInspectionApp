package com.wearable.inspection.mobile.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 已采集照片实体
 *
 * 记录每次拍照的文件路径和所属批次。拍照保存时写入，
 * 导出时按 batchId 查询。
 */
@Entity(
    tableName = "captured_photos",
    foreignKeys = [
        ForeignKey(
            entity = CaptureBatchEntity::class,
            parentColumns = ["batchId"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class CapturedPhotoEntity(
    @PrimaryKey(autoGenerate = true)
    val photoId: Long = 0,
    val batchId: String,
    val filePath: String,
    val viewIndex: Int,
    val templateId: String?,
    val templateName: String?,
    val capturedAt: Long = System.currentTimeMillis()
)
