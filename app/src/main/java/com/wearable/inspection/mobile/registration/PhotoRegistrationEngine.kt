package com.wearable.inspection.mobile.registration

import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.features2d.AKAZE
import org.opencv.features2d.BFMatcher
import org.opencv.imgproc.Imgproc

/**
 * V4/AKAZE 单张照片配准引擎。
 *
 * 输入：模板参考图 + 现场采集照片 + 模板 ROI 的规范坐标
 * 流程：AKAZE → BFMatcher + Lowe ratio → GMS → Homography → 质量门禁 → ROI 四角投影
 *
 * 一次性静态配准，不维护历史状态、不追踪帧序列。
 * 配准失败时返回 [RegistrationStatus.FAILED] 或 [RegistrationStatus.FALLBACK_FULL_IMAGE]，
 * 禁止返回看似有效的错误投影坐标。
 */
class PhotoRegistrationEngine {

    companion object {
        private const val TAG = "PhotoRegEngine"

        /** Diff_PM_G2 = 1（Java 绑定未暴露 DIFF_* 常量，用 C++ enum 字面量） */
        private const val AKAZE_DIFFUSIVITY = 1
    }

    private val akaze: AKAZE = AKAZE.create(
        AKAZE.DESCRIPTOR_MLDB,
        RegistrationConfig.AKAZE_DESCRIPTOR_SIZE,
        RegistrationConfig.AKAZE_DESCRIPTOR_CHANNELS,
        RegistrationConfig.AKAZE_THRESHOLD,
        4, 4,
        AKAZE_DIFFUSIVITY,
    )

    private val bfMatcher: BFMatcher = BFMatcher.create(Core.NORM_HAMMING)

    /**
     * 执行单张照片配准。
     *
     * @param templateImage 模板参考图（android.graphics.Bitmap 或 OpenCV Mat）
     * @param sceneImage 现场采集照片（android.graphics.Bitmap 或 OpenCV Mat）
     * @param templateRoiCorners 模板 ROI 在模板图中的四角坐标（像素），顺序：[左上, 右上, 右下, 左下]
     * @param sceneWidth 场景图像宽度（用于质量门禁边界检查）
     * @param sceneHeight 场景图像高度（用于质量门禁边界检查）
     * @return 配准结果
     */
    fun register(
        templateImage: Mat,
        sceneImage: Mat,
        templateRoiCorners: List<ProjectedPoint>,
        sceneWidth: Int = sceneImage.cols(),
        sceneHeight: Int = sceneImage.rows(),
    ): RegistrationResult {
        var templateGray: Mat? = null
        var sceneGray: Mat? = null
        var templateKp: MatOfKeyPoint? = null
        var templateDesc: Mat? = null
        var sceneKp: MatOfKeyPoint? = null
        var sceneDesc: Mat? = null
        var cappedTplKp: MatOfKeyPoint? = null
        var cappedTplDesc: Mat? = null
        var cappedSceneKp: MatOfKeyPoint? = null
        var cappedSceneDesc: Mat? = null
        var homography: Mat? = null
        val allMatOfDMatch = mutableListOf<MatOfDMatch>()

        try {
            // 1. 转灰度
            templateGray = toGray(templateImage)
            sceneGray = toGray(sceneImage)
            if (templateGray.empty() || sceneGray.empty()) {
                return failResult("图像转换灰度失败")
            }

            // 2. AKAZE 特征提取
            templateKp = MatOfKeyPoint()
            templateDesc = Mat()
            akaze.detectAndCompute(templateGray, Mat(), templateKp, templateDesc)

            sceneKp = MatOfKeyPoint()
            sceneDesc = Mat()
            akaze.detectAndCompute(sceneGray, Mat(), sceneKp, sceneDesc)

            val tplKpCount = templateKp.rows()
            val sceneKpCount = sceneKp.rows()

            if (tplKpCount < RegistrationConfig.MIN_TEMPLATE_KEYPOINTS) {
                return failResult("模板特征不足: $tplKpCount < ${RegistrationConfig.MIN_TEMPLATE_KEYPOINTS}")
            }
            if (sceneKpCount < RegistrationConfig.MIN_SCENE_KEYPOINTS) {
                return failResult("场景特征不足: $sceneKpCount < ${RegistrationConfig.MIN_SCENE_KEYPOINTS}")
            }

            // 按 response 截断特征；capFeatures 释放原 Mat
            val cappedTpl = capFeatures(templateKp, templateDesc, RegistrationConfig.AKAZE_MAX_FEATURES)
            cappedTplKp = cappedTpl.first; cappedTplDesc = cappedTpl.second
            templateKp = null; templateDesc = null
            val cappedScene = capFeatures(sceneKp, sceneDesc, RegistrationConfig.AKAZE_MAX_FEATURES)
            cappedSceneKp = cappedScene.first; cappedSceneDesc = cappedScene.second
            sceneKp = null; sceneDesc = null

            val tplKpts = cappedTplKp!!.toArray()
            val sceneKpts = cappedSceneKp!!.toArray()

            // 3. BFMatcher KNN + Lowe ratio
            val matchesList = mutableListOf<MatOfDMatch>()
            bfMatcher.knnMatch(cappedTplDesc!!, cappedSceneDesc!!, matchesList, 2)
            allMatOfDMatch.addAll(matchesList)

            val goodMatches = mutableListOf<DMatch>()
            for (matchPair in matchesList) {
                val matches = matchPair.toArray()
                if (matches.size >= 2 && matches[0].distance < RegistrationConfig.LOWE_RATIO * matches[1].distance) {
                    goodMatches.add(matches[0])
                }
            }

            if (goodMatches.size < RegistrationConfig.MIN_GOOD_MATCHES) {
                return failResult("good matches 不足: ${goodMatches.size} < ${RegistrationConfig.MIN_GOOD_MATCHES}")
            }

            // 4. GMS 滤波（匹配数充足时）
            var filteredMatches: List<DMatch> = goodMatches
            if (goodMatches.size >= RegistrationConfig.GMS_MIN_MATCHES_TO_APPLY) {
                val srcPts = goodMatches.map { GmsGridFilter.Point2D(tplKpts[it.queryIdx].pt.x, tplKpts[it.queryIdx].pt.y) }
                val dstPts = goodMatches.map { GmsGridFilter.Point2D(sceneKpts[it.trainIdx].pt.x, sceneKpts[it.trainIdx].pt.y) }
                val keep = GmsGridFilter.filter(
                    srcPts, dstPts,
                    templateImage.cols(), templateImage.rows(),
                    sceneWidth, sceneHeight,
                )
                val filtered = goodMatches.filterIndexed { i, _ -> keep[i] }
                if (filtered.size >= RegistrationConfig.MIN_GOOD_MATCHES) {
                    filteredMatches = filtered
                }
                // GMS 过滤后不足则回退未滤波（RANSAC 本身稳健抗离群）
            }

            // 5. Homography 估计
            val srcPts = filteredMatches.map { tplKpts[it.queryIdx].pt }
            val dstPts = filteredMatches.map { sceneKpts[it.trainIdx].pt }

            val srcMat = toMatOfPoint2f(srcPts)
            val dstMat = toMatOfPoint2f(dstPts)
            val mask = Mat()
            try {
                homography = Calib3d.findHomography(
                    srcMat, dstMat, Calib3d.USAC_MAGSAC,
                    RegistrationConfig.RANSAC_THRESHOLD, mask,
                    RegistrationConfig.USAC_MAX_ITERS, RegistrationConfig.USAC_CONFIDENCE,
                )

                if (homography == null || homography.empty()) {
                    return failResult("Homography 估计失败", RegistrationStatus.FALLBACK_FULL_IMAGE)
                }

                // 6. 读取内点
                val inlierIndices = readMaskInliers(mask)
                val inlierCount = inlierIndices.size

                // 7. 计算重投影误差
                val inlierSrcPts = inlierIndices.map { ProjectedPoint(srcPts[it].x, srcPts[it].y) }
                val inlierDstPts = inlierIndices.map { ProjectedPoint(dstPts[it].x, dstPts[it].y) }
                val hArray = homographyToDoubleArray(homography)
                val reprojectionError = RegistrationQualityGates.computeMedianReprojectionError(hArray, inlierSrcPts, inlierDstPts)

                // 8. 空间覆盖率
                val spatialCoverage = RegistrationQualityGates.computeSpatialCoverage(inlierSrcPts, inlierDstPts)

                // 9. 投影 ROI 四角
                val projectedCorners = RegistrationQualityGates.transformPoints(hArray, templateRoiCorners)

                // 10. 质量门禁
                val gateResult = RegistrationQualityGates.checkAll(
                    inlierCount = inlierCount,
                    goodMatchCount = filteredMatches.size,
                    medianReprojectionError = reprojectionError,
                    spatialCoverage = spatialCoverage,
                    projectedCorners = projectedCorners,
                    imageWidth = sceneWidth,
                    imageHeight = sceneHeight,
                )

                if (!gateResult.passed) {
                    return RegistrationResult(
                        status = RegistrationStatus.FALLBACK_FULL_IMAGE,
                        homography = null,
                        projectedRoiCorners = null,
                        inlierCount = inlierCount,
                        inlierRatio = if (filteredMatches.isNotEmpty()) inlierCount.toDouble() / filteredMatches.size else 0.0,
                        reprojectionError = reprojectionError,
                        spatialCoverage = spatialCoverage,
                        quadrilateralValid = false,
                        matcherName = RegistrationConfig.MATCHER_NAME,
                        matcherVersion = RegistrationConfig.MATCHER_VERSION,
                        failureReason = gateResult.failureReason,
                    )
                }

                return RegistrationResult(
                    status = RegistrationStatus.SUCCESS,
                    homography = hArray,
                    projectedRoiCorners = projectedCorners,
                    inlierCount = inlierCount,
                    inlierRatio = if (filteredMatches.isNotEmpty()) inlierCount.toDouble() / filteredMatches.size else 0.0,
                    reprojectionError = reprojectionError,
                    spatialCoverage = spatialCoverage,
                    quadrilateralValid = true,
                    matcherName = RegistrationConfig.MATCHER_NAME,
                    matcherVersion = RegistrationConfig.MATCHER_VERSION,
                    failureReason = null,
                )
            } finally {
                srcMat.release()
                dstMat.release()
                mask.release()
            }
        } finally {
            templateGray?.release()
            sceneGray?.release()
            templateKp?.release()
            templateDesc?.release()
            sceneKp?.release()
            sceneDesc?.release()
            cappedTplKp?.release()
            cappedTplDesc?.release()
            cappedSceneKp?.release()
            cappedSceneDesc?.release()
            homography?.release()
            allMatOfDMatch.forEach { it.release() }
        }
    }

    // ---- 辅助方法 ----

    private fun toGray(src: Mat): Mat {
        val gray = Mat()
        when (src.channels()) {
            1 -> src.copyTo(gray)
            3 -> Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGB2GRAY)
            4 -> Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            else -> src.copyTo(gray)
        }
        return gray
    }

    private fun homographyToDoubleArray(h: Mat): DoubleArray {
        val data = DoubleArray(9)
        h.get(0, 0, data)
        return data
    }

    private fun readMaskInliers(mask: Mat): List<Int> {
        val count = mask.rows()
        if (count == 0) return emptyList()
        val data = ByteArray(count)
        mask.get(0, 0, data)
        return data.indices.filter { data[it].toInt() != 0 }
    }

    private fun toMatOfPoint2f(points: List<Point>): MatOfPoint2f =
        MatOfPoint2f(*points.toTypedArray())

    /**
     * 按 response 降序截断特征。释放原 Mat，返回新实例。
     */
    private fun capFeatures(kp: MatOfKeyPoint, desc: Mat, cap: Int): Pair<MatOfKeyPoint, Mat> {
        if (kp.rows() <= cap) return kp to desc
        val arr = kp.toArray()
        val indices = arr.indices.sortedByDescending { arr[it].response }.take(cap)
        val cappedKp = MatOfKeyPoint(*indices.map { arr[it] }.toTypedArray())
        val cappedDesc = Mat(indices.size, desc.cols(), desc.type())
        try {
            for ((i, idx) in indices.withIndex()) {
                val src = desc.row(idx)
                val dst = cappedDesc.row(i)
                src.copyTo(dst)
                src.release()
                dst.release()
            }
        } catch (e: Exception) {
            cappedDesc.release()
            cappedKp.release()
            throw e
        }
        kp.release()
        desc.release()
        return cappedKp to cappedDesc
    }

    private fun failResult(
        reason: String,
        status: RegistrationStatus = RegistrationStatus.FAILED,
    ) = RegistrationResult(
        status = status,
        homography = null,
        projectedRoiCorners = null,
        inlierCount = 0,
        inlierRatio = 0.0,
        reprojectionError = 0.0,
        spatialCoverage = 0.0,
        quadrilateralValid = false,
        matcherName = RegistrationConfig.MATCHER_NAME,
        matcherVersion = RegistrationConfig.MATCHER_VERSION,
        failureReason = reason,
    )

    /**
     * 释放引擎持有的 OpenCV 原生资源。
     * clear() 后实例不再可用。
     */
    fun clear() {
        try { akaze.clear() } catch (_: Exception) {}
        try { bfMatcher.clear() } catch (_: Exception) {}
    }
}
