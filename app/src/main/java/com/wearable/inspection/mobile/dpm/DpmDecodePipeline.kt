package com.wearable.inspection.mobile.dpm

import android.graphics.Bitmap

/**
 * DPM 解码管线
 *
 * 流程：
 * 1. 主解码器（ML Kit）解码 DATA_MATRIX
 * 2. 主解码无结果 → 兜底解码器（ZXing DataMatrixReader）
 * 3. 空白结果（rawValue.isBlank）视为失败
 * 4. 两者异常不导致分析流程崩溃（返回 null）
 *
 * 只接受 DATA_MATRIX 格式。其他格式（QR Code 等）不在本管线处理。
 */
class DpmDecodePipeline(
    private val primaryDecoder: DpmPrimaryDecoder,
    private val fallbackDecoder: DpmFallbackDecoder
) {
    /**
     * 解码一帧图像
     *
     * @param bitmap 待解码图像
     * @return 解码成功返回结果，失败返回 null
     */
    suspend fun decode(bitmap: Bitmap): DpmScanResult? {
        // 主解码
        val primaryResult = tryDecodePrimary(bitmap)
        if (primaryResult != null) {
            return primaryResult
        }

        // 兜底解码
        val fallbackResult = tryDecodeFallback(bitmap)
        if (fallbackResult != null) {
            return fallbackResult
        }

        return null
    }

    private suspend fun tryDecodePrimary(bitmap: Bitmap): DpmScanResult? {
        return try {
            val result = primaryDecoder.decode(bitmap)
            if (result != null && result.rawValue.isNotBlank()) {
                result
            } else {
                null
            }
        } catch (_: Exception) {
            // 主解码异常不崩溃，降级到兜底
            null
        }
    }

    private suspend fun tryDecodeFallback(bitmap: Bitmap): DpmScanResult? {
        return try {
            val result = fallbackDecoder.decode(bitmap)
            if (result != null && result.rawValue.isNotBlank()) {
                result
            } else {
                null
            }
        } catch (_: Exception) {
            // 兜底解码异常不崩溃
            null
        }
    }
}
