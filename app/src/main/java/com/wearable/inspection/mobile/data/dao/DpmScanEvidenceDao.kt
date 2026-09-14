package com.wearable.inspection.mobile.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wearable.inspection.mobile.data.entity.DpmScanEvidenceEntity

@Dao
interface DpmScanEvidenceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(evidence: DpmScanEvidenceEntity): Long

    @Query("SELECT * FROM dpm_scan_evidence WHERE scanSessionId = :sessionId ORDER BY createdAt DESC")
    suspend fun getBySessionId(sessionId: String): List<DpmScanEvidenceEntity>

    @Query("SELECT * FROM dpm_scan_evidence ORDER BY createdAt DESC")
    suspend fun getAll(): List<DpmScanEvidenceEntity>
}
