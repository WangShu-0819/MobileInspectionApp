package com.wearable.inspection.mobile.ocr

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc

/**
 * 清晰度评估（Blur Detection）— 拉普拉斯算子方差法。
 *
 * Score = Var(∇²I_gray)：方差越大说明高频边缘越丰富（画面越清晰），
 * 运动模糊 / 失焦画面的方差趋近 0。
 *
 * 内存约定：全部临时 Mat 严格在 finally 中 release；输入 Mat 只读、不修改、不释放，
 * 所有权始终归调用方。
 */
object BlurDetector {

    /**
     * 计算单帧清晰度分值。
     * @param mat 相机帧（CV_8UC4 RGBA）或模板图（BGR），亦支持单通道灰度。
     * @return 拉普拉斯灰度方差（≥0，越大越清晰）
     */
    fun computeBlurScore(mat: Mat): Double {
        val gray = Mat()
        val laplacian = Mat()
        val mean = MatOfDouble()
        val stddev = MatOfDouble()
        try {
            when (mat.channels()) {
                4 -> Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
                3 -> Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
                1 -> mat.copyTo(gray)
                else -> throw IllegalArgumentException("unsupported channels=${mat.channels()}")
            }
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
            Core.meanStdDev(laplacian, mean, stddev)
            val std = stddev.get(0, 0)[0]
            return std * std
        } finally {
            gray.release()
            laplacian.release()
            mean.release()
            stddev.release()
        }
    }
}
