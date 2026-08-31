package com.wearable.inspection.mobile.data.dao

import androidx.room.*
import com.wearable.inspection.mobile.data.entity.InspectionSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InspectionSessionDao {
    @Query("SELECT * FROM inspection_sessions ORDER BY startTime DESC")
    fun observeAll(): Flow<List<InspectionSessionEntity>>

    @Query("SELECT * FROM inspection_sessions WHERE id = :id")
    suspend fun getById(id: String): InspectionSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: InspectionSessionEntity)

    @Update
    suspend fun update(session: InspectionSessionEntity)

    @Delete
    suspend fun delete(session: InspectionSessionEntity)

    @Query("DELETE FROM inspection_sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM inspection_sessions WHERE partId = :partId")
    suspend fun countByPartId(partId: String): Int
}
