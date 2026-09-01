package com.wearable.inspection.mobile.dpm

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat as ZxingBarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.datamatrix.DataMatrixReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ZXing DataMatrixReader 解码器实现（主解码器）。
 *
 * 配置：TRY_HARDER + 限 DATA_MATRIX 格式。
 * 输入 Bitmap → 灰度亮度数组 → PlanarYUVLuminanceSource（零 RGB 往返）。
 * 双二值化器尝试：GlobalHistogramBinarizer + HybridBinarizer。
 * 每次解码尝试正常极性 + 反色双试兜底。
 */
class ZxingDataMatrixDecoder : DpmZxingDecoder {

    private val reader = DataMatrixReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(ZxingBarcodeFormat.DATA_MATRIX),
        DecodeHintType.TRY_HARDER to true,
    )

    override suspend fun decode(bitmap: Bitmap): DpmScanResult? = withContext(Dispatchers.Default) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = ByteArray(w * h) { i ->
            val argb = pixels[i]
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            ((r * 299 + g * 587 + b * 114) / 1000).toByte()
        }

        // 正常极性 + 反色双试
        for (invert in booleanArrayOf(false, true)) {
            val data = if (invert) {
                ByteArray(gray.size) { i -> (255 - (gray[i].toInt() and 0xFF)).toByte() }
            } else {
                gray
            }
            // GlobalHistogramBinarizer
            decodeWithBinarizer(data, w, h, useHybrid = false)?.let { return@withContext it }
            // HybridBinarizer
            decodeWithBinarizer(data, w, h, useHybrid = true)?.let { return@withContext it }
        }
        null
    }

    private fun decodeWithBinarizer(data: ByteArray, w: Int, h: Int, useHybrid: Boolean): DpmScanResult? = runCatching {
        val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
        val binarizer = if (useHybrid) HybridBinarizer(source) else GlobalHistogramBinarizer(source)
        val result = reader.decode(BinaryBitmap(binarizer), hints)
        val text = result.text?.trim()?.takeIf { it.isNotEmpty() } ?: return@runCatching null
        DpmScanResult(
            rawValue = text,
            format = BarcodeFormat.DATA_MATRIX,
            timestampMs = System.currentTimeMillis(),
            source = DecodeSource.ZXING,
        )
    }.getOrNull()
}
