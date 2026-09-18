package com.wearable.inspection.mobile.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.wearable.inspection.mobile.data.entity.ExportedPackageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExportedPackageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(pkg: ExportedPackageEntity): Long

    @Update
    suspend fun update(pkg: ExportedPackageEntity)

    @Query("SELECT * FROM exported_packages ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ExportedPackageEntity>>

    @Query("SELECT * FROM exported_packages ORDER BY createdAt DESC")
    suspend fun getAll(): List<ExportedPackageEntity>

    @Query("SELECT * FROM exported_packages WHERE id = :id")
    suspend fun getById(id: Long): ExportedPackageEntity?

    @Query("SELECT * FROM exported_packages WHERE batchId = :batchId ORDER BY createdAt DESC")
    suspend fun getByBatchId(batchId: String): List<ExportedPackageEntity>

    @Query("DELETE FROM exported_packages WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT COUNT(*) FROM exported_packages WHERE status = 'EXPORTING'")
    suspend fun countExporting(): Int
}