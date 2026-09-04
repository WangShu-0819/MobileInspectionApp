package com.wearable.inspection.mobile.data.dao

import androidx.room.*
import com.wearable.inspection.mobile.data.entity.ViewRoiConfirmEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ViewRoiConfirmDao {

    @Query("SELECT * FROM view_roi_confirms WHERE batchId = :batchId ORDER BY viewIndex ASC, roiId ASC")
    fun observeByBatchId(batchId: String): Flow<List<ViewRoiConfirmEntity>>

    @Query("SELECT * FROM view_roi_confirms WHERE batchId = :batchId ORDER BY viewIndex ASC, roiId ASC")
    suspend fun getByBatchId(batchId: String): List<ViewRoiConfirmEntity>

    @Query("SELECT * FROM view_roi_confirms WHERE batchId = :batchId AND viewIndex = :viewIndex ORDER BY roiId ASC")
    suspend fun getByBatchAndViewIndex(batchId: String, viewIndex: Int): List<ViewRoiConfirmEntity>

    @Query("SELECT DISTINCT viewIndex FROM view_roi_confirms WHERE batchId = :batchId ORDER BY viewIndex ASC")
    suspend fun getConfirmedViewIndices(batchId: String): List<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(confirms: List<ViewRoiConfirmEntity>)

    @Query("DELETE FROM view_roi_confirms WHERE batchId = :batchId AND viewIndex = :viewIndex")
    suspend fun deleteByBatchAndViewIndex(batchId: String, viewIndex: Int)

    @Query("DELETE FROM view_roi_confirms WHERE batchId = :batchId")
    suspend fun deleteByBatchId(batchId: String)
}
