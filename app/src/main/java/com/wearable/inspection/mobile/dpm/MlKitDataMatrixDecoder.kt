package com.wearable.inspection.mobile.dpm

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * ML Kit Data Matrix 解码器实现（兜底解码器）。
 *
 * 限定 FORMAT_DATA_MATRIX（不处理 QR Code）。
 * 单帧超时 600ms 兜底（防异常场景卡死分析线程）。
 */
class MlKitDataMatrixDecoder : DpmMlKitDecoder {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX)
            .build()
    )

    override suspend fun decode(bitmap: Bitmap): DpmScanResult? = withContext(Dispatchers.Default) {
        val input = InputImage.fromBitmap(bitmap, 0)
        val barcodes = runCatching {
            Tasks.await(scanner.process(input), AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrNull() ?: return@withContext null

        barcodes
            .asSequence()
            .mapNotNull { it.rawValue?.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.let { text ->
                DpmScanResult(
                    rawValue = text,
                    format = BarcodeFormat.DATA_MATRIX,
                    timestampMs = System.currentTimeMillis(),
                    source = DecodeSource.ML_KIT,
                )
            }
    }

    companion object {
        private const val AWAIT_TIMEOUT_MS = 600L
    }
}
