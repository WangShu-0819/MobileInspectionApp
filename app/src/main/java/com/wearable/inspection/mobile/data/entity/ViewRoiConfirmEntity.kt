package com.wearable.inspection.mobile.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * View ROI 人工确认记录实体
 *
 * 每次拍照确认后，每个 ROI 生成一条记录。
 * 总体结果 (overallResult) 冗余存储在每行上，方便导出查询。
 *
 * 关联关系：
 * - batchId → CaptureBatchEntity.batchId
 * - templateId → InspectionTemplateEntity.id
 * - roiId → RoiDefinitionEntity.id
 */
@Entity(
    tableName = "view_roi_confirms",
    foreignKeys = [
        ForeignKey(
            entity = CaptureBatchEntity::class,
            parentColumns = ["batchId"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ViewRoiConfirmEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val batchId: String,
    val photoId: Long,
    val photoPath: String,
    val viewIndex: Int,
    val templateId: String,
    val templateName: String,
    val roiId: String,
    val roiName: String,
    val roiTargetType: String?,
    val roiNormalizedRect: String,
    val roiPixelRect: String,
    val softwareResult: String? = null,
    val humanResult: String,
    val confirmTime: Long,
    val overallResult: String,
    val overallConfirmTime: Long
)
