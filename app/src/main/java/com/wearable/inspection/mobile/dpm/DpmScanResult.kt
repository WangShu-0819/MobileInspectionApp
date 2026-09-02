package com.wearable.inspection.mobile.dpm

/**
 * DPM 扫码结果
 *
 * @param rawValue 解码后的原始字符串
 * @param format 条码格式（当前只接受 DATA_MATRIX）
 * @param timestampMs 解码时间戳（毫秒）
 * @param source 解码来源：ML_KIT 或 ZXING
 */
data class DpmScanResult(
    val rawValue: String,
    val format: BarcodeFormat,
    val timestampMs: Long,
    val source: DecodeSource
)

/**
 * 支持的条码格式
 *
 * 当前阶段只支持 DATA_MATRIX。
 */
enum class BarcodeFormat {
    DATA_MATRIX
}

/**
 * 解码来源
 */
enum class DecodeSource {
    /** ZXing DataMatrixReader 主解码 */
    ZXING,

    /** ML Kit Barcode Scanning 兜底解码 */
    ML_KIT,

    /** 网格重建解码（ImportedDpmScanner） */
    GRID,
}
