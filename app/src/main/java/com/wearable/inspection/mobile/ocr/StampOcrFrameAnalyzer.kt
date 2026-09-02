package com.wearable.inspection.mobile.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageProxy
import com.wearable.inspection.mobile.camera.FrameAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * 钢印 OCR 帧分析器 — 桥接 [FrameAnalyzer] 接口与 [SteelStampOcrAnalyzer]。
 *
 * 设计约束（对齐 DpmFrameAnalyzer）：
 * - 使用 SupervisorJob 隔离子协程，stop() 取消所有子 Job
 * - 统一输出 upright Bitmap，Analyzer 接收 rotation=0
 * - YUV 转换正确处理 cropRect/rowStride/pixelStride/奇偶宽高
 * - 不修改 ImageProxy buffer 的共享 position
 * - stop() 后所有迟到结果丢弃
 * - 不在 analyze() 中关闭 ImageProxy，由 CameraController 统一关闭
 * - ImageProxy 先转 Bitmap，再传入分析器；分析器内部使用后 recycle
 */
class StampOcrFrameAnalyzer(
    private val ocrAnalyzer: SteelStampOcrAnalyzer,
    private val scope: CoroutineScope,
) : FrameAnalyzer {

    private val _results = MutableSharedFlow<SteelStampResult>(extraBufferCapacity = 4)
    val results: SharedFlow<SteelStampResult> = _results.asSharedFlow()

    @Volatile
    private var isStopped = false

    // 专属 SupervisorJob：stop() 时取消所有子协程
    private val analyzerJob = SupervisorJob()
    private val analyzerScope = CoroutineScope(scope.coroutineContext + analyzerJob)

    override fun analyze(image: ImageProxy) {
        if (isStopped) {
            Log.w(TAG, "analyze: isStopped=true, skipping frame")
            return
        }
        Log.d(TAG, "analyze: frame received, format=${image.format}, size=${image.width}x${image.height}, rotation=${image.imageInfo.rotationDegrees}")
        val bitmap = imageProxyToUprightBitmap(image)
        if (bitmap == null) {
            Log.w(TAG, "analyze: imageProxyToUprightBitmap returned null")
            return
        }
        Log.d(TAG, "analyze: bitmap converted, size=${bitmap.width}x${bitmap.height}")
        analyzerScope.launch {
            try {
                val result = ocrAnalyzer.analyzeStructured(bitmap)
                Log.d(TAG, "analyze: result status=${result.status}, stage=${result.stage}, lines=${result.detectedLineCount}")
                if (!isStopped) {
                    _results.emit(result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "analyze: exception during ocrAnalyzer.analyzeStructured", e)
            } finally {
                bitmap.recycle()
            }
        }
    }

    override fun stop() {
        isStopped = true
        analyzerJob.cancelChildren()
        Log.d(TAG, "stop: cancelled all child jobs")
    }

    companion object {
        private const val TAG = "StampOcrFrameAnalyzer"

        /**
         * ImageProxy (YUV_420_888) → upright Bitmap (ARGB_8888)。
         *
         * 复用 DpmFrameAnalyzer 的 YUV 转换逻辑：
         * - cropRect、rowStride/pixelStride、position/limit、奇偶宽高
         * - 旋转为 upright（rotation=0 输出）
         */
        fun imageProxyToUprightBitmap(image: ImageProxy): Bitmap? {
            val planes = image.planes
            if (planes.size < 3) return null
            if (image.format != ImageFormat.YUV_420_888) return null

            val cropRect = image.cropRect
            val w = cropRect.width()
            val h = cropRect.height()
            if (w <= 0 || h <= 0) return null

            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]
            val yRowStride = yPlane.rowStride
            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride

            // NV21: Y + interleaved VU
            val uvHeight = (h + 1) / 2
            val nv21Size = w * h + w * uvHeight
            val nv21 = ByteArray(nv21Size)

            // 复制 Y 分量
            val yBuffer = yPlane.buffer
            val yBufferPos = yBuffer.position()
            for (row in 0 until h) {
                yBuffer.position(cropRect.left + (cropRect.top + row) * yRowStride)
                yBuffer.get(nv21, row * w, minOf(w, yBuffer.remaining()))
            }
            yBuffer.position(yBufferPos)

            // 复制 UV 分量
            val vBuffer = vPlane.buffer
            val uBuffer = uPlane.buffer
            val vBufferPos = vBuffer.position()
            val uBufferPos = uBuffer.position()

            var offset = w * h
            val uvCropLeft = cropRect.left / 2
            val uvCropTop = cropRect.top / 2

            if (uvPixelStride == 2) {
                for (row in 0 until uvHeight) {
                    val srcOffset = uvCropLeft + (uvCropTop + row) * uvRowStride
                    for (col in 0 until w / 2) {
                        val idx = srcOffset + col * uvPixelStride
                        if (idx < vBuffer.limit()) {
                            nv21[offset++] = vBuffer.get(idx)
                        }
                        if (idx < uBuffer.limit()) {
                            nv21[offset++] = uBuffer.get(idx)
                        }
                    }
                }
            } else {
                for (row in 0 until uvHeight) {
                    val srcOffset = uvCropLeft + (uvCropTop + row) * uvRowStride
                    vBuffer.position(srcOffset)
                    vBuffer.get(nv21, offset, minOf(w / 2, vBuffer.remaining()))
                    offset += w / 2
                }
                for (row in 0 until uvHeight) {
                    val srcOffset = uvCropLeft + (uvCropTop + row) * uvRowStride
                    uBuffer.position(srcOffset)
                    uBuffer.get(nv21, offset, minOf(w / 2, uBuffer.remaining()))
                    offset += w / 2
                }
            }

            vBuffer.position(vBufferPos)
            uBuffer.position(uBufferPos)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, w, h), 85, out)
            val jpegBytes = out.toByteArray()
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null

            // 旋转为 upright
            val rotation = image.imageInfo.rotationDegrees
            return if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) bitmap.recycle()
                rotated
            } else {
                bitmap
            }
        }
    }
}
