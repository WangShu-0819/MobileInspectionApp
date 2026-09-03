package com.wearable.inspection.mobile.data.dao

import androidx.room.*
import com.wearable.inspection.mobile.data.entity.CaptureBatchEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureBatchDao {
    @Query("SELECT * FROM capture_batches ORDER BY startTime DESC")
    fun observeAll(): Flow<List<CaptureBatchEntity>>

    @Query("SELECT * FROM capture_batches WHERE batchId = :batchId")
    suspend fun getById(batchId: String): CaptureBatchEntity?

    @Query("SELECT * FROM capture_batches WHERE partId = :partId ORDER BY startTime DESC")
    fun observeByPartId(partId: String): Flow<List<CaptureBatchEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(batch: CaptureBatchEntity)

    @Update
    suspend fun update(batch: CaptureBatchEntity)

    @Delete
    suspend fun delete(batch: CaptureBatchEntity)

    @Query("DELETE FROM capture_batches WHERE batchId = :batchId")
    suspend fun deleteById(batchId: String)
}
