package com.wearable.inspection.mobile.data.dao

import androidx.room.*
import com.wearable.inspection.mobile.data.entity.RoiInspectionRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RoiRecordDao {
    @Query("SELECT * FROM roi_inspection_records WHERE sessionId = :sessionId ORDER BY roiName ASC")
    fun observeBySessionId(sessionId: String): Flow<List<RoiInspectionRecordEntity>>

    @Query("SELECT * FROM roi_inspection_records WHERE sessionId = :sessionId")
    suspend fun getBySessionId(sessionId: String): List<RoiInspectionRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: RoiInspectionRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<RoiInspectionRecordEntity>)

    @Delete
    suspend fun delete(record: RoiInspectionRecordEntity)

    @Query("DELETE FROM roi_inspection_records WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)
}
