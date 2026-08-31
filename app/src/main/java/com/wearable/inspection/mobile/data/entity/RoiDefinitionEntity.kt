package com.wearable.inspection.mobile.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * ROI 定义实体
 */
@Entity(
    tableName = "roi_definitions",
    foreignKeys = [
        ForeignKey(
            entity = InspectionTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RoiDefinitionEntity(
    @PrimaryKey
    val id: String,
    val templateId: String,
    val name: String,
    val order: Int,
    val shapeType: String = "RECT", // RECT / POLYGON
    val normalizedRect: String, // JSON: {"left":0.1,"top":0.2,"right":0.9,"bottom":0.8}
    val points: String? = null, // POLYGON 时使用，JSON 格式的点集
    val inspectionType: String, // InspectionType name
    val expectedValue: String? = null,
    val configJson: String? = null, // 算法参数
    val preprocessJson: String? = null, // 预处理配置
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
