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
     * 查询未绑定任何批次的证据（batchId IS NULL）。
     * 独立扫码产生的证据无归属，不得被包删除操作清理。
     */
    @Query("SELECT * FROM dpm_scan_evidence WHERE batchId IS NULL ORDER BY createdAt DESC")
    suspend fun getUnbound(): List<DpmScanEvidenceEntity>

    /**
     * 查询某 scanSessionId 下是否有多个不同 batchId 的证据。
     * 用于判断证据行是否为共享引用场景（同一会话被多个批次引用）。
     *
     * @return 该 session 下出现的不同 batchId 数量（排除 NULL）
     */
    @Query(
        """
        SELECT COUNT(DISTINCT batchId)
        FROM dpm_scan_evidence
        WHERE scanSessionId = :scanSessionId
          AND batchId IS NOT NULL
        """
    )
    suspend fun countDistinctBatchIdsForSession(scanSessionId: String): Int

    /**
     * 精确删除单条证据行。
     * 调用方必须先确认该行不被其他批次引用（非共享场景）。
     * 不按全局 batchId 批量删除。
     */
    @Query("DELETE FROM dpm_scan_evidence WHERE id = :id")
    suspend fun deleteById(id: Long): Int

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

    // ---- 清理支持 ----

    /** 统计 DPM 证据总行数 */
    @Query("SELECT COUNT(*) FROM dpm_scan_evidence")
    suspend fun count(): Int

    /** 获取所有证据行的文件路径投影，用于统计字节和清理 */
    @Query("SELECT id, originalImagePath, roiImagePath FROM dpm_scan_evidence")
    suspend fun getAllPathProjections(): List<DpmEvidencePathProjection>

    /**
     * 按精确 evidenceId 查询单条证据行。
     * 清理流程不得依赖 getAll() 后内存筛选。
     */
    @Query("SELECT * FROM dpm_scan_evidence WHERE id = :evidenceId")
    suspend fun getByEvidenceId(evidenceId: Long): DpmScanEvidenceEntity?

    /**
     * 按精确 evidenceId 删除单条证据行。
     * 清理流程仅在该行所有文件删除成功或已缺失后调用。
     * @return 受影响行数（0 或 1）
     */
    @Query("DELETE FROM dpm_scan_evidence WHERE id = :evidenceId")
    suspend fun deleteByEvidenceId(evidenceId: Long): Int

    /**
     * 获取所有证据行的 evidenceId 快照，按 id ASC 排序。
     * 用于清理流程的稳定遍历，不使用 getAll() 后内存筛选。
     */
    @Query("SELECT id FROM dpm_scan_evidence ORDER BY id ASC")
    suspend fun getAllEvidenceIds(): List<Long>

    /** 获取所有证据行（含文件路径），用于精确清理快照 */
    @Query("SELECT * FROM dpm_scan_evidence ORDER BY id ASC")
    suspend fun getAllForCleanup(): List<DpmScanEvidenceEntity>
}

/** DPM 证据文件路径投影，仅用于统计和清理 */
data class DpmEvidencePathProjection(
    val id: Long,
    val originalImagePath: String,
    val roiImagePath: String?
)
