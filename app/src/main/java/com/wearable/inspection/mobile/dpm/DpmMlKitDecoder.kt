package com.wearable.inspection.mobile.dpm

import android.graphics.Bitmap

/**
 * ML Kit Barcode Scanning 解码器接口
 *
 * 用于 DPM 兜底解码。ZXing 无结果、空白或异常时调用。
 * 实现者只配置 FORMAT_DATA_MATRIX，不处理 QR Code。
 */
interface DpmMlKitDecoder {
    /**
     * 同步解码
     *
     * @param bitmap 待解码图像
     * @return 解码成功返回结果，无 DATA_MATRIX 或解码失败返回 null
     * @throws Exception 实现者不应抛出异常；调用方会捕获
     */
    suspend fun decode(bitmap: Bitmap): DpmScanResult?
}
