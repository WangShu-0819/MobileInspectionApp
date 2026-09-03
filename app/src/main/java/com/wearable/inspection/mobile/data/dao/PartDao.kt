package com.wearable.inspection.mobile.data.dao

import androidx.room.*
import com.wearable.inspection.mobile.data.entity.PartEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PartDao {
    @Query("SELECT * FROM parts ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PartEntity>>

    @Query("SELECT * FROM parts ORDER BY updatedAt DESC")
    suspend fun getAll(): List<PartEntity>

    @Query("SELECT * FROM parts WHERE id = :id")
    suspend fun getById(id: String): PartEntity?

    @Query("SELECT * FROM parts WHERE dpmCode = :dpmCode LIMIT 1")
    suspend fun getByDpmCode(dpmCode: String): PartEntity?

    @Query("UPDATE parts SET dpmCode = :dpmCode, updatedAt = :updatedAt WHERE id = :partId")
    suspend fun updateDpmCode(partId: String, dpmCode: String?, updatedAt: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(part: PartEntity)

    @Update
    suspend fun update(part: PartEntity)

    @Delete
    suspend fun delete(part: PartEntity)

    @Query("DELETE FROM parts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM parts")
    suspend fun count(): Int
}
