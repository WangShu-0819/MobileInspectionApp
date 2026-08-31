package com.wearable.inspection.mobile.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 零件实体
 */
@Entity(tableName = "parts")
data class PartEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val model: String? = null,
    val dpmCode: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
