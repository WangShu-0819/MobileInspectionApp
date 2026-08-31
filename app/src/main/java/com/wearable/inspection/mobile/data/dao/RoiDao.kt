package com.wearable.inspection.mobile.data.dao

import androidx.room.*
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RoiDao {
    @Query("SELECT * FROM roi_definitions WHERE templateId = :templateId ORDER BY `order` ASC")
    fun observeByTemplateId(templateId: String): Flow<List<RoiDefinitionEntity>>

    @Query("SELECT * FROM roi_definitions WHERE templateId = :templateId ORDER BY `order` ASC")
    suspend fun getByTemplateId(templateId: String): List<RoiDefinitionEntity>

    @Query("SELECT * FROM roi_definitions WHERE id = :id")
    suspend fun getById(id: String): RoiDefinitionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(roi: RoiDefinitionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rois: List<RoiDefinitionEntity>)

    @Update
    suspend fun update(roi: RoiDefinitionEntity)

    @Delete
    suspend fun delete(roi: RoiDefinitionEntity)

    @Query("DELETE FROM roi_definitions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM roi_definitions WHERE templateId = :templateId")
    suspend fun deleteByTemplateId(templateId: String)

    @Query("SELECT COUNT(*) FROM roi_definitions WHERE templateId = :templateId")
    suspend fun countByTemplateId(templateId: String): Int
}
