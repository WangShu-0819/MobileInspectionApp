package com.wearable.inspection.mobile.data.dao

import androidx.room.*
import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TemplateDao {
    @Query("SELECT * FROM inspection_templates WHERE partId = :partId ORDER BY displayOrder ASC, id ASC")
    fun observeByPartId(partId: String): Flow<List<InspectionTemplateEntity>>

    @Query("SELECT * FROM inspection_templates WHERE id = :id")
    suspend fun getById(id: String): InspectionTemplateEntity?

    @Query("SELECT * FROM inspection_templates WHERE partId = :partId ORDER BY displayOrder ASC, id ASC")
    suspend fun getByPartId(partId: String): List<InspectionTemplateEntity>

    @Query("SELECT * FROM inspection_templates ORDER BY partId ASC, displayOrder ASC, id ASC")
    fun observeAll(): Flow<List<InspectionTemplateEntity>>

    @Query("SELECT * FROM inspection_templates ORDER BY partId ASC, displayOrder ASC, id ASC")
    suspend fun getAll(): List<InspectionTemplateEntity>

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

    @Query("SELECT COUNT(*) FROM inspection_templates")
    suspend fun count(): Int

    @Query("UPDATE inspection_templates SET displayOrder = :displayOrder WHERE id = :id")
    suspend fun updateDisplayOrder(id: String, displayOrder: Int)

    /**
     * 批量更新 displayOrder
     *
     * 在事务内逐条更新。调用方需确保 orders 中的 id 存在且 order 值不重复。
     */
    @androidx.room.Transaction
    suspend fun reorderTemplates(orders: List<Pair<String, Int>>) {
        orders.forEach { (id, order) -> updateDisplayOrder(id, order) }
    }
}
