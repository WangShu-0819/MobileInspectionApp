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

    @Query("SELECT * FROM dpm_scan_evidence WHERE batchId = :batchId ORDER BY scanSessionId ASC, id ASC")
    suspend fun getByBatchId(batchId: String): List<DpmScanEvidenceEntity>

    /**
     * 将 scanSessionId 下所有成功且未绑定批次的证据批量关联到指定 batchId。
     * 条件 batchId IS NULL 保证幂等；条件 status='SUCCESS' 只绑定成功帧。
     *
     * @return 实际更新的行数
     */
    @Query(
        """
        UPDATE dpm_scan_evidence
        SET batchId = :batchId
        WHERE scanSessionId = :scanSessionId
          AND batchId IS NULL
          AND status = 'SUCCESS'
        """
    )
    suspend fun bindSessionToBatch(scanSessionId: String, batchId: String): Int
}
