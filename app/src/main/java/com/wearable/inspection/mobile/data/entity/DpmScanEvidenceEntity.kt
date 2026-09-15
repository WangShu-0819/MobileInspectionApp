package com.wearable.inspection.mobile.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * DPM 扫码会话图像证据实体
 *
 * 仅保存解码器内部 ECC 通过且成功读出的源帧：
 * - 保存成功源帧原始图 + 对应 ROI 裁切图
 * - 无 ECC 成功时不创建记录或图片
 *
 * 文件存储在 filesDir/dpm_evidence/ 目录，由 MobileImageStore 管理。
 */
@Entity(
    tableName = "dpm_scan_evidence",
    indices = [
        Index(value = ["scanSessionId"])
    ]
)
data class DpmScanEvidenceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 扫描会话 ID（与 CameraSession.sessionId 对应） */
    val scanSessionId: String,
    /** 帧时间戳（毫秒） */
    val frameTimeMs: Long,
    /** 帧来源：CAMERA（实时 ECC 成功源帧） */
    val frameSource: String,
    /** 解码内容（SUCCESS 时非空） */
    val decodedContent: String? = null,
    /** 解码状态：当前仅 SUCCESS */
    val status: String,
    /** 解码来源：ZXING / ML_KIT / GRID */
    val decodeSource: String? = null,
    /** 原始帧 JPEG 文件路径 */
    val originalImagePath: String,
    /** ROI 裁切 JPEG 文件路径（可选） */
    val roiImagePath: String? = null,
    /** 记录创建时间 */
    val createdAt: Long = System.currentTimeMillis()
)
