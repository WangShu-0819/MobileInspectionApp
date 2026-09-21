package com.wearable.inspection.mobile.registration

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 配准质量门禁。集中定义所有检查逻辑和阈值，不散落在调用方。
 *
 * 检查项：
 * 1. 内点数量
 * 2. 内点比例
 * 3. 重投影误差
 * 4. 空间覆盖率
 * 5. 投影四边形凸性
 * 6. 投影面积
 * 7. 图像边界
 * 8. NaN/Infinity 异常值
 */
object RegistrationQualityGates {

    data class GateResult(
        val passed: Boolean,
        val failureReason: String?,
    )

    /**
     * 执行全部质量门禁检查。
     *
     * @param inlierCount 内点数量
     * @param goodMatchCount good matches 总数（Lowe 后）
     * @param medianReprojectionError 中位重投影误差（像素）
     * @param spatialCoverage 空间覆盖率
     * @param projectedCorners 投影后的模板 ROI 四角
     * @param imageWidth 场景图像宽度
     * @param imageHeight 场景图像高度
     */
    fun checkAll(
        inlierCount: Int,
        goodMatchCount: Int,
        medianReprojectionError: Double,
        spatialCoverage: Double,
        projectedCorners: List<ProjectedPoint>?,
        imageWidth: Int,
        imageHeight: Int,
    ): GateResult {
        // 0. 指标非有限值（NaN/Infinity）
        if (!medianReprojectionError.isFinite()) {
            return GateResult(false, "重投影误差非有限值: $medianReprojectionError")
        }
        if (!spatialCoverage.isFinite()) {
            return GateResult(false, "空间覆盖率非有限值: $spatialCoverage")
        }

        // 1. 内点数量
        if (inlierCount < RegistrationConfig.MIN_INLIER_COUNT) {
            return GateResult(false, "内点不足: $inlierCount < ${RegistrationConfig.MIN_INLIER_COUNT}")
        }

        // 2. 内点比例
        if (goodMatchCount <= 0) {
            return GateResult(false, "内点比例无效: good matches=$goodMatchCount")
        }
        val ratio = inlierCount.toDouble() / goodMatchCount
        if (ratio < RegistrationConfig.MIN_INLIER_RATIO) {
            return GateResult(false, "内点比例不足: ${"%.3f".format(ratio)} < ${RegistrationConfig.MIN_INLIER_RATIO}")
        }

        // 3. 重投影误差
        if (medianReprojectionError > RegistrationConfig.MAX_MEDIAN_REPROJECTION_ERROR) {
            return GateResult(false, "重投影误差过大: ${"%.2f".format(medianReprojectionError)} > ${RegistrationConfig.MAX_MEDIAN_REPROJECTION_ERROR}")
        }

        // 4. 空间覆盖率
        if (spatialCoverage < RegistrationConfig.MIN_SPATIAL_COVERAGE) {
            return GateResult(false, "空间覆盖率不足: ${"%.3f".format(spatialCoverage)} < ${RegistrationConfig.MIN_SPATIAL_COVERAGE}")
        }

        // 5-8. 投影四边形检查
        if (projectedCorners == null || projectedCorners.size != 4) {
            return GateResult(false, "投影四边形无效: corners=${projectedCorners?.size}")
        }

        // 8. NaN/Infinity
        for ((i, pt) in projectedCorners.withIndex()) {
            if (!pt.x.isFinite() || !pt.y.isFinite()) {
                return GateResult(false, "投影点包含非有限值: corner[$i]=(${pt.x}, ${pt.y})")
            }
        }

        // 5. 凸性
        if (!isConvexQuadrilateral(projectedCorners)) {
            return GateResult(false, "投影四边形非凸")
        }

        // 6. 面积
        val area = polygonArea(projectedCorners)
        val imageArea = imageWidth.toDouble() * imageHeight.toDouble()
        if (imageArea > 0) {
            val areaRatio = area / imageArea
            if (areaRatio < RegistrationConfig.MIN_PROJECTED_AREA_RATIO) {
                return GateResult(false, "投影面积过小: ${"%.6f".format(areaRatio)} < ${RegistrationConfig.MIN_PROJECTED_AREA_RATIO}")
            }
            if (areaRatio > RegistrationConfig.MAX_PROJECTED_AREA_RATIO) {
                return GateResult(false, "投影面积过大: ${"%.3f".format(areaRatio)} > ${RegistrationConfig.MAX_PROJECTED_AREA_RATIO}")
            }
        }

        // 7. 图像边界
        val margin = RegistrationConfig.BOUNDARY_MARGIN_PX
        for ((i, pt) in projectedCorners.withIndex()) {
            if (pt.x < -margin || pt.y < -margin || pt.x > imageWidth + margin || pt.y > imageHeight + margin) {
                return GateResult(false, "投影点超出图像边界: corner[$i]=(${pt.x}, ${pt.y}), image=${imageWidth}x$imageHeight")
            }
        }

        return GateResult(true, null)
    }

    /**
     * 判断四个点是否构成凸四边形（叉积法）。
     */
    fun isConvexQuadrilateral(corners: List<ProjectedPoint>): Boolean {
        if (corners.size != 4) return false
        var sign = 0
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val c = corners[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-10) continue // 共线点
            val currentSign = if (cross > 0) 1 else -1
            if (sign == 0) sign = currentSign
            else if (sign != currentSign) return false
        }
        return true
    }

    /**
     * 多边形面积（Shoelace 公式）。
     */
    fun polygonArea(points: List<ProjectedPoint>): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        for (i in points.indices) {
            val cur = points[i]
            val next = points[(i + 1) % points.size]
            sum += cur.x * next.y - next.x * cur.y
        }
        return abs(sum) / 2.0
    }

    /**
     * 计算空间覆盖率：inlier 凸包面积 / inlier bbox 面积。
     * 纯 Kotlin 实现，不依赖 OpenCV。
     */
    fun computeSpatialCoverage(inlierSrcPts: List<ProjectedPoint>, inlierDstPts: List<ProjectedPoint>): Double {
        if (inlierDstPts.size < 3) return 0.0
        val hullArea = convexHullArea(inlierDstPts)
        val bbox = boundingRect(inlierDstPts)
        val bboxArea = bbox.width * bbox.height
        return if (bboxArea > 0) hullArea / bboxArea else 0.0
    }

    private data class BBox(val x: Double, val y: Double, val width: Double, val height: Double)

    private fun boundingRect(pts: List<ProjectedPoint>): BBox {
        var xMin = Double.MAX_VALUE; var yMin = Double.MAX_VALUE
        var xMax = -Double.MAX_VALUE; var yMax = -Double.MAX_VALUE
        for (pt in pts) {
            xMin = min(xMin, pt.x); yMin = min(yMin, pt.y)
            xMax = max(xMax, pt.x); yMax = max(yMax, pt.y)
        }
        return BBox(xMin, yMin, max(0.0, xMax - xMin), max(0.0, yMax - yMin))
    }

    /**
     * 计算点集凸包面积（Graham scan 简化版）。
     */
    private fun convexHullArea(pts: List<ProjectedPoint>): Double {
        if (pts.size < 3) return 0.0
        val hull = convexHull(pts)
        return polygonArea(hull)
    }

    /**
     * Graham scan 凸包（Andrew's monotone chain 变体）。
     */
    internal fun convexHull(pts: List<ProjectedPoint>): List<ProjectedPoint> {
        if (pts.size < 3) return pts.toList()
        val sorted = pts.sortedWith(compareBy({ it.x }, { it.y }))

        val lower = mutableListOf<ProjectedPoint>()
        for (pt in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], pt) <= 0) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(pt)
        }

        val upper = mutableListOf<ProjectedPoint>()
        for (i in sorted.indices.reversed()) {
            val pt = sorted[i]
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], pt) <= 0) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(pt)
        }

        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    private fun cross(o: ProjectedPoint, a: ProjectedPoint, b: ProjectedPoint): Double =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    /**
     * 计算中位重投影误差。
     */
    fun computeMedianReprojectionError(
        homography: DoubleArray,
        inlierSrcPts: List<ProjectedPoint>,
        inlierDstPts: List<ProjectedPoint>,
    ): Double {
        if (inlierSrcPts.isEmpty()) return 0.0
        val projected = transformPoints(homography, inlierSrcPts)
        val errors = inlierSrcPts.indices.map { i ->
            val dx = projected[i].x - inlierDstPts[i].x
            val dy = projected[i].y - inlierDstPts[i].y
            sqrt(dx * dx + dy * dy)
        }.sorted()
        return if (errors.size % 2 == 0) {
            (errors[errors.size / 2 - 1] + errors[errors.size / 2]) / 2.0
        } else {
            errors[errors.size / 2]
        }
    }

    /**
     * 用 Homography 矩阵变换点。
     * homography: 3×3 行主序 DoubleArray（长度 9）
     */
    fun transformPoints(homography: DoubleArray, points: List<ProjectedPoint>): List<ProjectedPoint> {
        return points.map { pt ->
            val w = homography[6] * pt.x + homography[7] * pt.y + homography[8]
            if (abs(w) < 1e-12) {
                ProjectedPoint(Double.NaN, Double.NaN)
            } else {
                ProjectedPoint(
                    (homography[0] * pt.x + homography[1] * pt.y + homography[2]) / w,
                    (homography[3] * pt.x + homography[4] * pt.y + homography[5]) / w,
                )
            }
        }
    }
}
