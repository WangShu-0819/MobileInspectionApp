package com.wearable.inspection.mobile.dpm

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import com.wearable.inspection.mobile.camera.FrameAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * CameraX 帧分析器 — 桥接 [FrameAnalyzer] 接口与 [DpmAnalyzer]。
 *
 * CameraController 以 ImageProxy 回调驱动（单线程 Executor），
 * 本类负责：
 * 1. ImageProxy → Bitmap 转换（YUV_420_888 → JPEG → Bitmap，含旋转）
 * 2. 委托 DpmAnalyzer 执行完整解码流水线
 * 3. 通过 [results] SharedFlow 发射解码结果（供 ViewModel/UI 收集）
 *
 * 线程模型：
 * - [analyze] 在 CameraController 的分析线程调用（单线程串行）
 * - DpmAnalyzer 内部使用协程 + AtomicBoolean 单飞保护
 * - 结果通过 SharedFlow 发射，下游在 Main 收集
 *
 * 生命周期：
 * - [stop] 取消协程 scope 并清理 DpmAnalyzer 状态
 * - 不持有 ImageProxy 引用（CameraController 在 finally 中 close）
 */
class DpmFrameAnalyzer(
    private val dpmAnalyzer: DpmAnalyzer,
    private val scope: CoroutineScope,
    private val scanRoi: Rect? = null,
    private val onLensRefocus: () -> Unit = {},
) : FrameAnalyzer {

    private val _results = MutableSharedFlow<DpmAnalyzeResult>(extraBufferCapacity = 16)
    val results: SharedFlow<DpmAnalyzeResult> = _results.asSharedFlow()

    @Volatile
    private var isStopped = false

    private var frameCount = 0L

    override fun analyze(image: ImageProxy) {
        if (isStopped) return
        val rotation = image.imageInfo.rotationDegrees
        val bitmap = imageProxyToBitmap(image) ?: return
        scope.launch {
            try {
                val result = dpmAnalyzer.analyze(
                    frame = bitmap,
                    frameRotation = rotation,
                    scanRoi = scanRoi,
                )
                if (result.status != DpmAnalyzeStatus.PROCEED) {
                    _results.emit(result)
                }
            } catch (e: Exception) {
                // 分析异常静默降级，不中断帧流
            } finally {
                bitmap.recycle()
            }
        }
    }

    override fun stop() {
        isStopped = true
        dpmAnalyzer.resetForTest()
    }

    companion object {
        /**
         * ImageProxy (YUV_420_888) → Bitmap (ARGB_8888)。
         *
         * 使用 YuvImage JPEG 编码→解码路径：
         * 1. 提取 Y/U/V planes 为 NV21 字节数组
         * 2. YuvImage.compressToJPEG → ByteArray
         * 3. BitmapFactory.decodeByteArray → Bitmap
         * 4. 按 rotationDegrees 旋转
         *
         * 注意：此方法在分析线程执行，JPEG 编码有 CPU 开销。
         * 对于 DPM 扫码（~5fps 分析帧率）可接受。
         */
        fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
            val planes = image.planes
            if (planes.size < 3) return null
            if (image.format != ImageFormat.YUV_420_888) return null

            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]
            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            val w = image.width
            val h = image.height

            // NV21: Y + interleaved VU
            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            // NV21 交错 VU：vBuffer 在前，uBuffer 在后，按 pixelStride 间隔
            val uvPixelStride = uPlane.pixelStride
            if (uvPixelStride == 2) {
                // 交错 UV，逐行拷贝
                val uvRowStride = uPlane.rowStride
                var offset = ySize
                for (row in 0 until h / 2) {
                    for (col in 0 until w / 2) {
                        val uvIndex = row * uvRowStride + col * uvPixelStride
                        nv21[offset++] = vBuffer.get(uvIndex)
                        nv21[offset++] = uBuffer.get(uvIndex)
                    }
                }
            } else {
                // 非交错（少见），直接追加
                vBuffer.position(0)
                vBuffer.get(nv21, ySize, vSize)
                uBuffer.position(0)
                uBuffer.get(nv21, ySize + vSize, uSize)
            }

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, w, h), 85, out)
            val jpegBytes = out.toByteArray()
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: return null

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
