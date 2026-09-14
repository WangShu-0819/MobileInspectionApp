package com.wearable.inspection.mobile.detection

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

data class NanoDetInputTransform(
    val width: Int,
    val height: Int,
    val resizedWidth: Int,
    val resizedHeight: Int,
    val scaleX: Double,
    val scaleY: Double
)

data class PreprocessedNanoDetImage(
    val tensorNchw: FloatArray,
    val transform: NanoDetInputTransform
)

object NanoDetImagePreprocessor {
    private val mean = floatArrayOf(103.53f, 116.28f, 123.675f)
    private val std = floatArrayOf(57.375f, 57.12f, 58.395f)
    private const val CHANNELS = 3

    fun letterboxTransform(width: Int, height: Int): NanoDetInputTransform {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        val scale = min(
            NanoDetModelContract.INPUT_SIZE.toDouble() / width,
            NanoDetModelContract.INPUT_SIZE.toDouble() / height
        )
        val resizedWidth = max(1, (width * scale).toInt())
        val resizedHeight = max(1, (height * scale).toInt())
        return NanoDetInputTransform(
            width = width,
            height = height,
            resizedWidth = resizedWidth,
            resizedHeight = resizedHeight,
            scaleX = resizedWidth.toDouble() / width,
            scaleY = resizedHeight.toDouble() / height
        )
    }

    /** OpenCV's three-channel input/output order remains BGR throughout preprocessing. */
    fun preprocess(sourceBgr: Mat): PreprocessedNanoDetImage {
        require(!sourceBgr.empty() && sourceBgr.type() == CvType.CV_8UC3) {
            "Expected a non-empty CV_8UC3 BGR image"
        }
        val transform = letterboxTransform(sourceBgr.cols(), sourceBgr.rows())
        val resized = Mat()
        val canvas = Mat.zeros(
            NanoDetModelContract.INPUT_SIZE,
            NanoDetModelContract.INPUT_SIZE,
            CvType.CV_8UC3
        )
        try {
            Imgproc.resize(
                sourceBgr,
                resized,
                Size(transform.resizedWidth.toDouble(), transform.resizedHeight.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_LINEAR
            )
            val content = canvas.submat(
                org.opencv.core.Rect(0, 0, transform.resizedWidth, transform.resizedHeight)
            )
            try {
                resized.copyTo(content)
            } finally {
                content.release()
            }
            val interleavedBgr = ByteArray(NanoDetModelContract.INPUT_SIZE * NanoDetModelContract.INPUT_SIZE * CHANNELS)
            val copied = canvas.get(0, 0, interleavedBgr)
            check(copied == interleavedBgr.size) { "Could not read complete letterbox image" }
            return PreprocessedNanoDetImage(packBgrToNchw(interleavedBgr, transform), transform)
        } finally {
            resized.release()
            canvas.release()
        }
    }

    /**
     * Pack an already letterboxed 416x416 BGR byte image into normalized NCHW.
     * Padding is kept at zero after normalization, matching the verified Android path.
     */
    fun packBgrToNchw(interleavedBgr: ByteArray, transform: NanoDetInputTransform): FloatArray {
        val size = NanoDetModelContract.INPUT_SIZE
        require(interleavedBgr.size == size * size * CHANNELS) { "Expected a 416x416 BGR canvas" }
        require(transform.resizedWidth in 1..size && transform.resizedHeight in 1..size)
        val planeSize = size * size
        val output = FloatArray(planeSize * CHANNELS)
        for (y in 0 until transform.resizedHeight) {
            for (x in 0 until transform.resizedWidth) {
                val source = (y * size + x) * CHANNELS
                val target = y * size + x
                for (channel in 0 until CHANNELS) {
                    output[channel * planeSize + target] =
                        ((interleavedBgr[source + channel].toInt() and 0xff) - mean[channel]) / std[channel]
                }
            }
        }
        return output
    }
}
