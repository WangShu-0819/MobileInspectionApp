package com.wearable.inspection.mobile.dpm

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
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
import java.nio.ByteBuffer

/**
 * CameraX 帧分析器 — 桥接 [FrameAnalyzer] 接口与 [DpmAnalyzer]。
 *
 * 设计约束（Batch 3 整改）：
 * - 使用 SupervisorJob 隔离子协程，stop() 取消所有子 Job
 * - 统一输出 upright Bitmap，Analyzer 接收 rotation=0
 * - YUV 转换正确处理 cropRect/rowStride/pixelStride/position/limit/奇偶宽高
 * - 不修改 ImageProxy buffer 的共享 position
 * - stop() 后所有迟到结果丢弃
 * - 不调用 resetForTest()（生产路径）
 */
class DpmFrameAnalyzer(
    private val dpmAnalyzer: DpmAnalyzer,
    private val scope: CoroutineScope,
    private val onLensRefocus: () -> Unit = {},
) : FrameAnalyzer {

    private val _results = MutableSharedFlow<DpmAnalyzeResult>(extraBufferCapacity = 16)
    val results: SharedFlow<DpmAnalyzeResult> = _results.asSharedFlow()

    @Volatile
    private var isStopped = false

    @Volatile
    private var scanRoi: Rect? = null

    // 专属 SupervisorJob：stop() 时取消所有子协程
    private val analyzerJob = SupervisorJob()
    private val analyzerScope = CoroutineScope(scope.coroutineContext + analyzerJob)

    /**
     * 动态更新扫描 ROI
     */
    fun updateScanRoi(roi: Rect?) {
        scanRoi = roi
    }

    override fun analyze(image: ImageProxy) {
        if (isStopped) return
        val bitmap = imageProxyToUprightBitmap(image) ?: return
        analyzerScope.launch {
            try {
                val result = dpmAnalyzer.analyze(
                    frame = bitmap,
                    frameRotation = 0, // 已输出 upright Bitmap
                    scanRoi = scanRoi,
                )
                if (!isStopped && result.status != DpmAnalyzeStatus.PROCEED) {
                    _results.emit(result)
                }
            } catch (_: Exception) {
                // 分析异常静默降级，不中断帧流
            } finally {
                bitmap.recycle()
            }
        }
    }

    override fun stop() {
        isStopped = true
        scanRoi = null
        analyzerJob.cancelChildren()
    }

    companion object {
        /**
         * ImageProxy (YUV_420_888) → upright Bitmap (ARGB_8888)。
         *
         * 正确处理：
         * - cropRect（ImageProxy 的裁切区域）
         * - Y/U/V plane 的 rowStride 和 pixelStride
         * - buffer 的 position/limit（不修改共享 buffer）
         * - 奇偶宽高边界（NV21 的 UV 行数 = ceil(h/2)）
         * - 旋转（输出 upright Bitmap，Analyzer 接收 rotation=0）
         *
         * 使用 YuvImage JPEG 编码→解码路径（兼容性最好）。
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
            // UV 行数 = ceil(h / 2)
            val uvHeight = (h + 1) / 2
            val nv21Size = w * h + w * uvHeight
            val nv21 = ByteArray(nv21Size)

            // 复制 Y 分量（按行拷贝，处理 rowStride != width 的情况）
            val yBuffer = yPlane.buffer
            val yBufferPos = yBuffer.position()
            for (row in 0 until h) {
                yBuffer.position(cropRect.left + (cropRect.top + row) * yRowStride)
                yBuffer.get(nv21, row * w, minOf(w, yBuffer.remaining()))
            }
            yBuffer.position(yBufferPos) // 恢复 position

            // 复制 UV 分量（交错 VU for NV21）
            val vBuffer = vPlane.buffer
            val uBuffer = uPlane.buffer
            val vBufferPos = vBuffer.position()
            val uBufferPos = uBuffer.position()

            var offset = w * h
            val uvCropLeft = cropRect.left / 2
            val uvCropTop = cropRect.top / 2

            if (uvPixelStride == 2) {
                // 交错 UV plane：逐行提取 V 和 U
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
                // 非交错：逐行拷贝 V，再逐行拷贝 U
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
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null

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
