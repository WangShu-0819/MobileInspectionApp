package com.wearable.inspection.mobile.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * DPM 导出包记录实体
 *
 * 记录每次 SAF 导出的 ZIP 包信息，支持后续按 packageId 精确删除。
 * 不存储 cacheDir 临时文件路径；只记录 SAF URI 和导出元数据。
 */
@Entity(
    tableName = "exported_packages",
    indices = [
        Index(value = ["batchId"]),
        Index(value = ["packageType"])
    ]
)
data class ExportedPackageEntity(
    /** 稳定主键，autoGenerate Long */
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 包类型：DPM_EVIDENCE / BATCH_INSPECTION */
    val packageType: String,
    /** 显示名称（文件名或人工可读描述） */
    val displayName: String,
    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis(),
    /** 导出状态：EXPORTING / SUCCESS / FAILED / CANCELLED */
    val status: String = STATUS_EXPORTING,
    /** SAF document URI 字符串；导出中或取消时为 null */
    val safUri: String? = null,
    /** 是否已取得持久化 URI 权限 */
    val persistedPermission: Boolean = false,
    /** 关联批次 ID（BATCH_INSPECTION 时非空） */
    val batchId: String? = null,
    /** 关联扫码会话 ID（可选） */
    val sessionId: String? = null,
    /** 导出文件字节数（成功时记录） */
    val byteSize: Long = 0,
    /** 错误信息（失败时记录） */
    val errorMessage: String? = null
) {
    companion object {
        const val TYPE_DPM_EVIDENCE = "DPM_EVIDENCE"
        const val TYPE_BATCH_INSPECTION = "BATCH_INSPECTION"

        const val STATUS_EXPORTING = "EXPORTING"
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}