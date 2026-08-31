package com.wearable.inspection.mobile.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 检测模板实体
 */
@Entity(
    tableName = "inspection_templates",
    foreignKeys = [
        ForeignKey(
            entity = PartEntity::class,
            parentColumns = ["id"],
            childColumns = ["partId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class InspectionTemplateEntity(
    @PrimaryKey
    val id: String,
    val partId: String,
    val name: String,
    val mainImagePath: String,
    val outlineData: String? = null, // JSON 格式的轮廓数据
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val enabled: Boolean = true
)
