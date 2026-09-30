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
import kotlin.math.max

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

        /**
         * 特征提取工作分辨率缩放系数（≤1.0）。
         * 长边超过 [RegistrationConfig.AKAZE_MAX_WORKING_SIDE] 时按比例缩小，
         * 否则 1.0（原图直通，既有小图语义逐位不变）。
         */
        internal fun featureWorkingScale(width: Int, height: Int): Double {
            val maxSide = max(width, height)
            return if (maxSide <= RegistrationConfig.AKAZE_MAX_WORKING_SIDE) {
                1.0
            } else {
                RegistrationConfig.AKAZE_MAX_WORKING_SIDE.toDouble() / maxSide
            }
        }

        /** 把工作分辨率下的关键点坐标换算回原图坐标系（factor = 1 / featureWorkingScale）。 */
        internal fun scaleKeyPoints(kp: MatOfKeyPoint, factor: Double) {
            if (factor == 1.0) return
            val arr = kp.toArray()
            for (kpt in arr) {
                kpt.pt.x *= factor
                kpt.pt.y *= factor
            }
            kp.fromArray(*arr)
        }
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
        var templateMask: Mat? = null
        var sceneMask: Mat? = null
        val allMatOfDMatch = mutableListOf<MatOfDMatch>()

        try {
            // 1. 转灰度（长边超过上限时降采样：AKAZE 尺度空间 native 内存与像素数成正比）
            val (tplGrayWork, tplScale) = toCappedGray(templateImage)
            templateGray = tplGrayWork
            val (sceneGrayWork, sceneScale) = toCappedGray(sceneImage)
            sceneGray = sceneGrayWork
            if (tplGrayWork.empty() || sceneGrayWork.empty()) {
                return failResult("图像转换灰度失败")
            }

            // 2. AKAZE 特征提取（工作分辨率）；关键点坐标换算回原图坐标系，
            //    之后的匹配、RANSAC、门禁与投影全部保持原图坐标和原阈值语义
            templateMask = Mat()
            sceneMask = Mat()
            templateKp = MatOfKeyPoint()
            templateDesc = Mat()
            akaze.detectAndCompute(tplGrayWork, templateMask, templateKp, templateDesc)
            scaleKeyPoints(templateKp, 1.0 / tplScale)

            sceneKp = MatOfKeyPoint()
            sceneDesc = Mat()
            akaze.detectAndCompute(sceneGrayWork, sceneMask, sceneKp, sceneDesc)
            scaleKeyPoints(sceneKp, 1.0 / sceneScale)

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
            templateMask?.release()
            sceneMask?.release()
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

    // ---- Session API: decouple homography from per-ROI projection ----

    /**
     * Result of global homography computation (steps 1-5 of [register]).
     * Does NOT project any ROI corners — use [projectRoiCorners] for that.
     */
    data class HomographyResult(
        val status: RegistrationStatus,
        /** 3×3 homography (row-major DoubleArray, length 9). Non-null only when [isSuccess]. */
        val homography: DoubleArray?,
        val inlierCount: Int,
        val inlierRatio: Double,
        val goodMatchCount: Int,
        val medianReprojectionError: Double,
        val spatialCoverage: Double,
        val matcherName: String,
        val matcherVersion: String,
        val failureReason: String?,
    ) {
        val isSuccess: Boolean get() = status == RegistrationStatus.SUCCESS
    }

    /**
     * Compute global homography from template to scene (AKAZE + BFMatcher + GMS + RANSAC).
     * Does NOT project any ROI corners — call [projectRoiCorners] per ROI.
     *
     * Steps 1-5 of [register]: grayscale → AKAZE detectAndCompute → capFeatures →
     * BFMatcher KNN + Lowe ratio → GMS → Homography estimation → reprojection error + spatial coverage.
     */
    fun computeHomography(
        templateImage: Mat,
        sceneImage: Mat,
        sceneWidth: Int = sceneImage.cols(),
        sceneHeight: Int = sceneImage.rows(),
    ): HomographyResult {
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
        var templateMask: Mat? = null
        var sceneMask: Mat? = null
        val allMatOfDMatch = mutableListOf<MatOfDMatch>()

        try {
            val (tplGrayWork, tplScale) = toCappedGray(templateImage)
            templateGray = tplGrayWork
            val (sceneGrayWork, sceneScale) = toCappedGray(sceneImage)
            sceneGray = sceneGrayWork
            if (tplGrayWork.empty() || sceneGrayWork.empty()) {
                return homographyFailResult("图像转换灰度失败")
            }

            templateMask = Mat(); sceneMask = Mat()
            templateKp = MatOfKeyPoint(); templateDesc = Mat()
            akaze.detectAndCompute(tplGrayWork, templateMask, templateKp, templateDesc)
            scaleKeyPoints(templateKp, 1.0 / tplScale)
            sceneKp = MatOfKeyPoint(); sceneDesc = Mat()
            akaze.detectAndCompute(sceneGrayWork, sceneMask, sceneKp, sceneDesc)
            scaleKeyPoints(sceneKp, 1.0 / sceneScale)

            val tplKpCount = templateKp.rows()
            val sceneKpCount = sceneKp.rows()
            if (tplKpCount < RegistrationConfig.MIN_TEMPLATE_KEYPOINTS) {
                return homographyFailResult("模板特征不足: $tplKpCount < ${RegistrationConfig.MIN_TEMPLATE_KEYPOINTS}")
            }
            if (sceneKpCount < RegistrationConfig.MIN_SCENE_KEYPOINTS) {
                return homographyFailResult("场景特征不足: $sceneKpCount < ${RegistrationConfig.MIN_SCENE_KEYPOINTS}")
            }

            val cappedTpl = capFeatures(templateKp, templateDesc, RegistrationConfig.AKAZE_MAX_FEATURES)
            cappedTplKp = cappedTpl.first; cappedTplDesc = cappedTpl.second
            templateKp = null; templateDesc = null
            val cappedScene = capFeatures(sceneKp, sceneDesc, RegistrationConfig.AKAZE_MAX_FEATURES)
            cappedSceneKp = cappedScene.first; cappedSceneDesc = cappedScene.second
            sceneKp = null; sceneDesc = null

            val tplKpts = cappedTplKp!!.toArray()
            val sceneKpts = cappedSceneKp!!.toArray()

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
                return homographyFailResult("good matches 不足: ${goodMatches.size} < ${RegistrationConfig.MIN_GOOD_MATCHES}")
            }

            var filteredMatches: List<DMatch> = goodMatches
            if (goodMatches.size >= RegistrationConfig.GMS_MIN_MATCHES_TO_APPLY) {
                val srcPts = goodMatches.map { GmsGridFilter.Point2D(tplKpts[it.queryIdx].pt.x, tplKpts[it.queryIdx].pt.y) }
                val dstPts = goodMatches.map { GmsGridFilter.Point2D(sceneKpts[it.trainIdx].pt.x, sceneKpts[it.trainIdx].pt.y) }
                val keep = GmsGridFilter.filter(srcPts, dstPts, templateImage.cols(), templateImage.rows(), sceneWidth, sceneHeight)
                val filtered = goodMatches.filterIndexed { i, _ -> keep[i] }
                if (filtered.size >= RegistrationConfig.MIN_GOOD_MATCHES) filteredMatches = filtered
            }

            val srcPts = filteredMatches.map { tplKpts[it.queryIdx].pt }
            val dstPts = filteredMatches.map { sceneKpts[it.trainIdx].pt }
            val srcMat = toMatOfPoint2f(srcPts)
            val dstMat = toMatOfPoint2f(dstPts)
            val mask = Mat()
            try {
                homography = Calib3d.findHomography(srcMat, dstMat, Calib3d.USAC_MAGSAC,
                    RegistrationConfig.RANSAC_THRESHOLD, mask,
                    RegistrationConfig.USAC_MAX_ITERS, RegistrationConfig.USAC_CONFIDENCE)

                if (homography == null || homography.empty()) {
                    return homographyFailResult("Homography 估计失败", RegistrationStatus.FALLBACK_FULL_IMAGE)
                }

                val inlierIndices = readMaskInliers(mask)
                val inlierCount = inlierIndices.size
                val inlierSrcPts = inlierIndices.map { ProjectedPoint(srcPts[it].x, srcPts[it].y) }
                val inlierDstPts = inlierIndices.map { ProjectedPoint(dstPts[it].x, dstPts[it].y) }
                val hArray = homographyToDoubleArray(homography)
                val reprojectionError = RegistrationQualityGates.computeMedianReprojectionError(hArray, inlierSrcPts, inlierDstPts)
                val spatialCoverage = RegistrationQualityGates.computeSpatialCoverage(inlierSrcPts, inlierDstPts)

                // 全局匹配质量门禁（与 register() 的 checkAll 1-4 同一组检查）：
                // 逐 ROI 投影几何门禁不能替代内点数/比例、重投影误差与空间覆盖率检查。
                val globalGate = RegistrationQualityGates.checkGlobalMatchQuality(
                    inlierCount = inlierCount,
                    goodMatchCount = filteredMatches.size,
                    medianReprojectionError = reprojectionError,
                    spatialCoverage = spatialCoverage,
                )
                if (!globalGate.passed) {
                    return HomographyResult(
                        status = RegistrationStatus.FALLBACK_FULL_IMAGE,
                        homography = null,
                        inlierCount = inlierCount,
                        inlierRatio = if (filteredMatches.isNotEmpty()) inlierCount.toDouble() / filteredMatches.size else 0.0,
                        goodMatchCount = filteredMatches.size,
                        medianReprojectionError = reprojectionError,
                        spatialCoverage = spatialCoverage,
                        matcherName = RegistrationConfig.MATCHER_NAME,
                        matcherVersion = RegistrationConfig.MATCHER_VERSION,
                        failureReason = globalGate.failureReason,
                    )
                }

                return HomographyResult(
                    status = RegistrationStatus.SUCCESS,
                    homography = hArray,
                    inlierCount = inlierCount,
                    inlierRatio = if (filteredMatches.isNotEmpty()) inlierCount.toDouble() / filteredMatches.size else 0.0,
                    goodMatchCount = filteredMatches.size,
                    medianReprojectionError = reprojectionError,
                    spatialCoverage = spatialCoverage,
                    matcherName = RegistrationConfig.MATCHER_NAME,
                    matcherVersion = RegistrationConfig.MATCHER_VERSION,
                    failureReason = null,
                )
            } finally {
                srcMat.release(); dstMat.release(); mask.release()
            }
        } finally {
            templateGray?.release(); sceneGray?.release()
            templateMask?.release(); sceneMask?.release()
            templateKp?.release(); templateDesc?.release()
            sceneKp?.release(); sceneDesc?.release()
            cappedTplKp?.release(); cappedTplDesc?.release()
            cappedSceneKp?.release(); cappedSceneDesc?.release()
            homography?.release()
            allMatOfDMatch.forEach { it.release() }
        }
    }

    private fun homographyFailResult(
        reason: String,
        status: RegistrationStatus = RegistrationStatus.FAILED,
    ) = HomographyResult(
        status = status, homography = null,
        inlierCount = 0, inlierRatio = 0.0, goodMatchCount = 0,
        medianReprojectionError = 0.0, spatialCoverage = 0.0,
        matcherName = RegistrationConfig.MATCHER_NAME,
        matcherVersion = RegistrationConfig.MATCHER_VERSION,
        failureReason = reason,
    )

    /**
     * Project template ROI corners through a precomputed homography, then run quality gates.
     * Used by the session API: compute homography once, project per ROI.
     *
     * @param homography 3×3 homography from [computeHomography]
     * @param templateRoiCorners ROI corners in template pixel coordinates [左上, 右上, 右下, 左下]
     * @param sceneWidth scene image width (for boundary checks)
     * @param sceneHeight scene image height (for boundary checks)
     */
    fun projectRoiCorners(
        homography: DoubleArray,
        templateRoiCorners: List<ProjectedPoint>,
        sceneWidth: Int,
        sceneHeight: Int,
    ): RegistrationResult {
        val projectedCorners = RegistrationQualityGates.transformPoints(homography, templateRoiCorners)
        // Only check per-ROI projected geometry; global match quality was already validated.
        val gateResult = RegistrationQualityGates.checkProjectedGeometry(
            projectedCorners = projectedCorners,
            imageWidth = sceneWidth,
            imageHeight = sceneHeight,
        )
        if (!gateResult.passed) {
            return RegistrationResult(
                status = RegistrationStatus.FALLBACK_FULL_IMAGE,
                homography = null, projectedRoiCorners = null,
                inlierCount = 0, inlierRatio = 0.0, reprojectionError = 0.0,
                spatialCoverage = 0.0, quadrilateralValid = false,
                matcherName = RegistrationConfig.MATCHER_NAME,
                matcherVersion = RegistrationConfig.MATCHER_VERSION,
                failureReason = gateResult.failureReason,
            )
        }
        return RegistrationResult(
            status = RegistrationStatus.SUCCESS,
            homography = homography,
            projectedRoiCorners = projectedCorners,
            inlierCount = 1, inlierRatio = 1.0, reprojectionError = 0.0,
            spatialCoverage = 1.0, quadrilateralValid = true,
            matcherName = RegistrationConfig.MATCHER_NAME,
            matcherVersion = RegistrationConfig.MATCHER_VERSION,
            failureReason = null,
        )
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

    /**
     * 转灰度并在长边超过上限时降采样（先降采样彩色再转灰度，避免全尺寸灰度副本）。
     *
     * @return (灰度 Mat（调用方负责 release）, 工作缩放系数 = 工作边 / 原图边)
     */
    private fun toCappedGray(src: Mat): Pair<Mat, Double> {
        val scale = featureWorkingScale(src.cols(), src.rows())
        if (scale == 1.0) return toGray(src) to 1.0
        val scaled = Mat()
        try {
            Imgproc.resize(src, scaled, Size(), scale, scale, Imgproc.INTER_AREA)
            return toGray(scaled) to scale
        } finally {
            scaled.release()
        }
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
