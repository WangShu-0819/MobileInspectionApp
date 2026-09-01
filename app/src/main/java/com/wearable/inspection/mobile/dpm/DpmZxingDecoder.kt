package com.wearable.inspection.mobile.dpm

import android.graphics.Bitmap

/**
 * ZXing DataMatrixReader 解码器接口
 *
 * 用于 DPM 主解码。实现者使用 ZXing DataMatrixReader 解码 DATA_MATRIX。
 * 只返回 DATA_MATRIX 格式结果，其他格式返回 null。
 */
interface DpmZxingDecoder {
    /**
     * 同步解码
     *
     * @param bitmap 待解码图像
     * @return 解码成功返回结果，无 DATA_MATRIX 或解码失败返回 null
     * @throws Exception 实现者不应抛出异常；调用方会捕获
     */
    suspend fun decode(bitmap: Bitmap): DpmScanResult?
}
