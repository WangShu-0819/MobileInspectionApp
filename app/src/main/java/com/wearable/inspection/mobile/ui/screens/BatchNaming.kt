package com.wearable.inspection.mobile.ui.screens

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 采集批次命名工具。
 *
 * 批次显示名称格式: `<安全零件码>_yyyyMMdd_HHmmss_SSS`
 * - 零件码优先级: dpmCode > partId > partName > 短 batchId
 * - 非法文件名字符（\/:*?"<>|）替换为下划线
 * - 结果不得为空；全部缺失时回退到 batchId 前 8 位
 */

/** 清理文件名非法字符（\/:*?"<>|），替换为下划线。 */
internal fun sanitizeFileNamePart(raw: String): String =
    raw.replace(Regex("[\\\\/:*?\"<>|]"), "_")

/**
 * 解析批次有效零件码。
 * 优先级: dpmCode > partId > partName > batchId.take(8)。
 * 清理非法字符后若为空则回退到 batchId.take(8)。
 */
internal fun resolveBatchPartCode(
    dpmCode: String?,
    partId: String?,
    partName: String?,
    batchId: String,
): String {
    val raw = dpmCode?.takeIf { it.isNotBlank() }
        ?: partId?.takeIf { it.isNotBlank() }
        ?: partName?.takeIf { it.isNotBlank() }
        ?: batchId.take(8)
    val sanitized = sanitizeFileNamePart(raw.trim())
    return sanitized.ifBlank { batchId.take(8) }
}

/**
 * 生成批次显示名称: `<安全零件码>_yyyyMMdd_HHmmss_SSS`
 *
 * 时间使用 [startTime]（即 batch.startTime），确保同一名称稳定。
 */
internal fun batchDisplayName(
    dpmCode: String?,
    partId: String?,
    partName: String?,
    batchId: String,
    startTime: Long,
): String {
    val code = resolveBatchPartCode(dpmCode, partId, partName, batchId)
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(startTime))
    return "${code}_$ts"
}
