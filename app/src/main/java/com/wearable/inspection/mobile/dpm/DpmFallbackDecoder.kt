package com.wearable.inspection.mobile.dpm

import android.graphics.Bitmap

/**
 * DPM 兜底解码器接口（ZXing DataMatrixReader）
 *
 * 主解码器无结果时调用。
 * 只处理 DATA_MATRIX 格式。
 */
interface DpmFallbackDecoder {
    /**
     * 同步解码
     *
     * @param bitmap 待解码图像
     * @return 解码成功返回结果，无 DATA_MATRIX 或解码失败返回 null
     * @throws Exception 实现者不应抛出异常；调用方会捕获
     */
    suspend fun decode(bitmap: Bitmap): DpmScanResult?
}
