package com.wearable.inspection.mobile.data.dao

import androidx.room.*
import com.wearable.inspection.mobile.data.entity.CapturedPhotoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CapturedPhotoDao {
    @Query("SELECT * FROM captured_photos WHERE batchId = :batchId ORDER BY viewIndex ASC")
    fun observeByBatchId(batchId: String): Flow<List<CapturedPhotoEntity>>

    @Query("SELECT * FROM captured_photos WHERE batchId = :batchId ORDER BY viewIndex ASC")
    suspend fun getByBatchId(batchId: String): List<CapturedPhotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(photo: CapturedPhotoEntity)

    @Delete
    suspend fun delete(photo: CapturedPhotoEntity)

    @Query("DELETE FROM captured_photos WHERE batchId = :batchId")
    suspend fun deleteByBatchId(batchId: String)
}
