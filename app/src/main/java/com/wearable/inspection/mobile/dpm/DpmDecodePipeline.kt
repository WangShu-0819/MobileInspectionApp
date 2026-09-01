package com.wearable.inspection.mobile.dpm

import android.graphics.Bitmap

/**
 * DPM 解码管线
 *
 * 流程（ZXing 优先，与旧工程生产实现一致）：
 * 1. ZXing DataMatrixReader 主解码
 * 2. ZXing 无结果、空白或异常 → ML Kit 兜底解码
 * 3. ML Kit 只配置 FORMAT_DATA_MATRIX，不处理 QR Code
 * 4. 空白结果（rawValue.isBlank）视为失败
 * 5. 两者异常不导致分析流程崩溃（返回 null）
 * 6. 非 DATA_MATRIX 结果不能通过
 */
class DpmDecodePipeline(
    private val zxingDecoder: DpmZxingDecoder,
    private val mlKitDecoder: DpmMlKitDecoder
) {
    /**
     * 解码一帧图像
     *
     * @param bitmap 待解码图像
     * @return 解码成功返回结果，失败返回 null
     */
    suspend fun decode(bitmap: Bitmap): DpmScanResult? {
        // ZXing 主解码
        val zxingResult = tryDecodeZxing(bitmap)
        if (zxingResult != null) {
            return zxingResult
        }

        // ML Kit 兜底解码
        val mlKitResult = tryDecodeMlKit(bitmap)
        if (mlKitResult != null) {
            return mlKitResult
        }

        return null
    }

    private suspend fun tryDecodeZxing(bitmap: Bitmap): DpmScanResult? {
        return try {
            val result = zxingDecoder.decode(bitmap)
            if (result != null && result.rawValue.isNotBlank()) {
                result
            } else {
                null
            }
        } catch (_: Exception) {
            // ZXing 异常不崩溃，降级到 ML Kit
            null
        }
    }

    private suspend fun tryDecodeMlKit(bitmap: Bitmap): DpmScanResult? {
        return try {
            val result = mlKitDecoder.decode(bitmap)
            if (result != null && result.rawValue.isNotBlank()) {
                result
            } else {
                null
            }
        } catch (_: Exception) {
            // ML Kit 异常不崩溃
            null
        }
    }
}
