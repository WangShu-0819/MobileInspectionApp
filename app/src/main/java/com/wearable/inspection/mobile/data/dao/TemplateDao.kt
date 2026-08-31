package com.wearable.inspection.mobile.data.dao

import androidx.room.*
import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TemplateDao {
    @Query("SELECT * FROM inspection_templates WHERE partId = :partId ORDER BY createdAt DESC")
    fun observeByPartId(partId: String): Flow<List<InspectionTemplateEntity>>

    @Query("SELECT * FROM inspection_templates WHERE id = :id")
    suspend fun getById(id: String): InspectionTemplateEntity?

    @Query("SELECT * FROM inspection_templates WHERE partId = :partId")
    suspend fun getByPartId(partId: String): List<InspectionTemplateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(template: InspectionTemplateEntity)

    @Update
    suspend fun update(template: InspectionTemplateEntity)

    @Delete
    suspend fun delete(template: InspectionTemplateEntity)

    @Query("DELETE FROM inspection_templates WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM inspection_templates WHERE partId = :partId")
    suspend fun deleteByPartId(partId: String)
}
