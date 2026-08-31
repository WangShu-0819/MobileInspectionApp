package com.wearable.inspection.mobile.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * ROI 检测记录实体
 */
@Entity(
    tableName = "roi_inspection_records",
    foreignKeys = [
        ForeignKey(
            entity = InspectionSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = RoiDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["roiId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class RoiInspectionRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,
    val roiId: String?,
    val roiName: String,
    val roiSnapshot: String, // JSON: 快照时的归一化坐标
    val inspectionType: String,
    val algorithmVersion: String = "1.0",
    val autoStatus: String, // InspectionStatus name
    val finalStatus: String? = null, // InspectionStatus name
    val score: Float? = null,
    val metricsJson: String? = null, // Map<String, Double> 的 JSON
    val durationMs: Long,
    val roiCropPath: String? = null,
    val preprocessedPath: String? = null,
    val errorMessage: String? = null
)
