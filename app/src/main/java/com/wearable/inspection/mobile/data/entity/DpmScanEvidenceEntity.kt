package com.wearable.inspection.mobile.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * DPM 扫码会话图像证据实体
 *
 * 每次 DPM 扫码会话退出时保存一组证据：
 * - 解码成功：保存解码成功的那帧原始图 + ROI 裁切图
 * - 未解码：保存最后一个有效分析帧的原始图 + ROI 裁切图
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
    /** 帧来源：CAMERA（实时帧）、LAST_VALID（最后有效分析帧） */
    val frameSource: String,
    /** 解码内容（NO_READ 时为 null） */
    val decodedContent: String? = null,
    /** 解码状态：SUCCESS / NO_READ */
    val status: String,
    /** 解码来源：ZXING / ML_KIT / GRID（NO_READ 时为 null） */
    val decodeSource: String? = null,
    /** 原始帧 JPEG 文件路径 */
    val originalImagePath: String,
    /** ROI 裁切 JPEG 文件路径（可选） */
    val roiImagePath: String? = null,
    /** 记录创建时间 */
    val createdAt: Long = System.currentTimeMillis()
)
