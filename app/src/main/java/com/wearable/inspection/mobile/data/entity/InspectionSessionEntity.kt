package com.wearable.inspection.mobile.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 检测会话实体
 */
@Entity(
    tableName = "inspection_sessions",
    foreignKeys = [
        ForeignKey(
            entity = PartEntity::class,
            parentColumns = ["id"],
            childColumns = ["partId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = InspectionTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class InspectionSessionEntity(
    @PrimaryKey
    val id: String,
    val partId: String?,
    val partName: String?,
    val templateId: String?,
    val templateName: String?,
    val originalImagePath: String,
    val annotatedImagePath: String? = null,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val autoOverallStatus: String, // InspectionStatus name
    val finalOverallStatus: String? = null, // InspectionStatus name
    val alignmentScore: Float? = null,
    val alignmentOverride: Boolean = false,
    val notes: String? = null
)
