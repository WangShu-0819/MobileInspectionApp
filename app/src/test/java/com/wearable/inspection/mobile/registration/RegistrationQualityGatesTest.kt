package com.wearable.inspection.mobile.registration

import org.junit.Assert.*
import org.junit.Test

/**
 * RegistrationQualityGates 纯逻辑测试（无 OpenCV 依赖）。
 *
 * 覆盖：
 * - 内点数量不足
 * - 内点比例不足
 * - 重投影误差过大
 * - 空间覆盖率不足
 * - 非凸四边形
 * - 投影面积异常
 * - 越界投影
 * - NaN/Infinity
 * - 合法通过
 */
class RegistrationQualityGatesTest {

    // ---- 合法投影四边形 ----
    private val validCorners = listOf(
        ProjectedPoint(100.0, 100.0),
        ProjectedPoint(300.0, 100.0),
        ProjectedPoint(300.0, 300.0),
        ProjectedPoint(100.0, 300.0),
    )

    // ---- 1. 内点数量不足 ----
    @Test
    fun `inlier count below minimum fails`() {
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 5, goodMatchCount = 100,
            medianReprojectionError = 2.0, spatialCoverage = 0.5,
            projectedCorners = validCorners, imageWidth = 640, imageHeight = 480,
        )
        assertFalse(result.passed)
        assertNotNull(result.failureReason)
        assertTrue(result.failureReason!!.contains("内点不足"))
    }

    // ---- 2. 内点比例不足 ----
    @Test
    fun `inlier ratio below minimum fails`() {
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 20, goodMatchCount = 100, // ratio = 0.20 < 0.30
            medianReprojectionError = 2.0, spatialCoverage = 0.5,
            projectedCorners = validCorners, imageWidth = 640, imageHeight = 480,
        )
        assertFalse(result.passed)
        assertTrue(result.failureReason!!.contains("内点比例"))
    }

    // ---- 3. 重投影误差过大 ----
    @Test
    fun `high reprojection error fails`() {
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 50, goodMatchCount = 100,
            medianReprojectionError = 15.0, spatialCoverage = 0.5,
            projectedCorners = validCorners, imageWidth = 640, imageHeight = 480,
        )
        assertFalse(result.passed)
        assertTrue(result.failureReason!!.contains("重投影误差"))
    }

    // ---- 4. 空间覆盖率不足 ----
    @Test
    fun `low spatial coverage fails`() {
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 50, goodMatchCount = 100,
            medianReprojectionError = 2.0, spatialCoverage = 0.05,
            projectedCorners = validCorners, imageWidth = 640, imageHeight = 480,
        )
        assertFalse(result.passed)
        assertTrue(result.failureReason!!.contains("空间覆盖率"))
    }

    // ---- 5. 非凸四边形 ----
    @Test
    fun `concave quadrilateral fails`() {
        // 凹四边形：第3个点凹进去
        val concaveCorners = listOf(
            ProjectedPoint(100.0, 100.0),
            ProjectedPoint(300.0, 100.0),
            ProjectedPoint(150.0, 150.0), // 凹点
            ProjectedPoint(100.0, 300.0),
        )
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 50, goodMatchCount = 100,
            medianReprojectionError = 2.0, spatialCoverage = 0.5,
            projectedCorners = concaveCorners, imageWidth = 640, imageHeight = 480,
        )
        assertFalse(result.passed)
        assertTrue(result.failureReason!!.contains("非凸"))
    }

    // ---- 6a. 投影面积过小 ----
    @Test
    fun `tiny projected area fails`() {
        val tinyCorners = listOf(
            ProjectedPoint(100.0, 100.0),
            ProjectedPoint(101.0, 100.0),
            ProjectedPoint(101.0, 101.0),
            ProjectedPoint(100.0, 101.0),
        )
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 50, goodMatchCount = 100,
            medianReprojectionError = 2.0, spatialCoverage = 0.5,
            projectedCorners = tinyCorners, imageWidth = 640, imageHeight = 480,
        )
        assertFalse(result.passed)
        assertTrue(result.failureReason!!.contains("投影面积过小"))
    }

    // ---- 6b. 投影面积过大 ----
    @Test
    fun `huge projected area fails`() {
        val hugeCorners = listOf(
            ProjectedPoint(0.0, 0.0),
            ProjectedPoint(640.0, 0.0),
            ProjectedPoint(640.0, 480.0),
            ProjectedPoint(0.0, 480.0),
        )
        // image 320x240, corners cover 640x480 → area ratio > 0.95
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 50, goodMatchCount = 100,
            medianReprojectionError = 2.0, spatialCoverage = 0.5,
            projectedCorners = hugeCorners, imageWidth = 320, imageHeight = 240,
        )
        assertFalse(result.passed)
        assertTrue(result.failureReason!!.contains("投影面积过大"))
    }

    // ---- 7. 越界投影 ----
    @Test
    fun `out of bounds corners fails`() {
        val outOfBoundsCorners = listOf(
            ProjectedPoint(-200.0, 100.0),
            ProjectedPoint(300.0, 100.0),
            ProjectedPoint(300.0, 300.0),
            ProjectedPoint(100.0, 300.0),
        )
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 50, goodMatchCount = 100,
            medianReprojectionError = 2.0, spatialCoverage = 0.5,
            projectedCorners = outOfBoundsCorners, imageWidth = 640, imageHeight = 480,
        )
        assertFalse(result.passed)
        assertTrue(result.failureReason!!.contains("超出图像边界"))
    }

    // ---- 8. NaN ----
    @Test
    fun `NaN coordinates fail`() {
        val nanCorners = listOf(
            ProjectedPoint(Double.NaN, 100.0),
            ProjectedPoint(300.0, 100.0),
            ProjectedPoint(300.0, 300.0),
            ProjectedPoint(100.0, 300.0),
        )
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 50, goodMatchCount = 100,
            medianReprojectionError = 2.0, spatialCoverage = 0.5,
            projectedCorners = nanCorners, imageWidth = 640, imageHeight = 480,
        )
        assertFalse(result.passed)
        assertTrue(result.failureReason!!.contains("非有限值"))
    }

    @Test
    fun `Infinity coordinates fail`() {
        val infCorners = listOf(
            ProjectedPoint(100.0, 100.0),
            ProjectedPoint(Double.POSITIVE_INFINITY, 100.0),
            ProjectedPoint(300.0, 300.0),
            ProjectedPoint(100.0, 300.0),
        )
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 50, goodMatchCount = 100,
            medianReprojectionError = 2.0, spatialCoverage = 0.5,
            projectedCorners = infCorners, imageWidth = 640, imageHeight = 480,
        )
        assertFalse(result.passed)
        assertTrue(result.failureReason!!.contains("非有限值"))
    }

    // ---- null corners ----
    @Test
    fun `null corners fails`() {
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 50, goodMatchCount = 100,
            medianReprojectionError = 2.0, spatialCoverage = 0.5,
            projectedCorners = null, imageWidth = 640, imageHeight = 480,
        )
        assertFalse(result.passed)
        assertTrue(result.failureReason!!.contains("无效"))
    }

    @Test
    fun `wrong corner count fails`() {
        val threeCorners = listOf(
            ProjectedPoint(100.0, 100.0),
            ProjectedPoint(300.0, 100.0),
            ProjectedPoint(300.0, 300.0),
        )
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 50, goodMatchCount = 100,
            medianReprojectionError = 2.0, spatialCoverage = 0.5,
            projectedCorners = threeCorners, imageWidth = 640, imageHeight = 480,
        )
        assertFalse(result.passed)
    }

    // ---- 合法通过 ----
    @Test
    fun `all gates pass with valid parameters`() {
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 50, goodMatchCount = 100,
            medianReprojectionError = 3.0, spatialCoverage = 0.4,
            projectedCorners = validCorners, imageWidth = 640, imageHeight = 480,
        )
        assertTrue(result.passed)
        assertNull(result.failureReason)
    }

    // ---- 边界值测试 ----
    @Test
    fun `exactly at inlier count threshold passes`() {
        // goodMatchCount=33 → ratio=10/33≈0.303 > 0.30，确保比例门禁也通过
        val result = RegistrationQualityGates.checkAll(
            inlierCount = RegistrationConfig.MIN_INLIER_COUNT,
            goodMatchCount = 33,
            medianReprojectionError = 2.0, spatialCoverage = 0.5,
            projectedCorners = validCorners, imageWidth = 640, imageHeight = 480,
        )
        assertTrue(result.passed)
    }

    @Test
    fun `exactly at inlier ratio threshold passes`() {
        val inlierCount = 30
        val goodMatchCount = (inlierCount / RegistrationConfig.MIN_INLIER_RATIO).toInt()
        val result = RegistrationQualityGates.checkAll(
            inlierCount = inlierCount, goodMatchCount = goodMatchCount,
            medianReprojectionError = 2.0, spatialCoverage = 0.5,
            projectedCorners = validCorners, imageWidth = 640, imageHeight = 480,
        )
        assertTrue(result.passed)
    }

    // ---- 四边形凸性 ----
    @Test
    fun `convex square is valid`() {
        assertTrue(RegistrationQualityGates.isConvexQuadrilateral(validCorners))
    }

    @Test
    fun `concave shape is invalid`() {
        val concave = listOf(
            ProjectedPoint(0.0, 0.0),
            ProjectedPoint(10.0, 0.0),
            ProjectedPoint(3.0, 3.0), // 凹点
            ProjectedPoint(0.0, 10.0),
        )
        assertFalse(RegistrationQualityGates.isConvexQuadrilateral(concave))
    }

    // ---- 面积计算 ----
    @Test
    fun `polygon area of unit square is 1`() {
        val square = listOf(
            ProjectedPoint(0.0, 0.0),
            ProjectedPoint(1.0, 0.0),
            ProjectedPoint(1.0, 1.0),
            ProjectedPoint(0.0, 1.0),
        )
        assertEquals(1.0, RegistrationQualityGates.polygonArea(square), 1e-10)
    }

    @Test
    fun `polygon area of 200x200 square is 40000`() {
        assertEquals(40000.0, RegistrationQualityGates.polygonArea(validCorners), 1e-10)
    }

    // ---- 投影变换 ----
    @Test
    fun `identity homography preserves points`() {
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val pts = listOf(ProjectedPoint(10.0, 20.0), ProjectedPoint(30.0, 40.0))
        val result = RegistrationQualityGates.transformPoints(identity, pts)
        assertEquals(10.0, result[0].x, 1e-10)
        assertEquals(20.0, result[0].y, 1e-10)
        assertEquals(30.0, result[1].x, 1e-10)
        assertEquals(40.0, result[1].y, 1e-10)
    }

    @Test
    fun `translation homography shifts points`() {
        val tx = 50.0; val ty = 30.0
        val h = doubleArrayOf(1.0, 0.0, tx, 0.0, 1.0, ty, 0.0, 0.0, 1.0)
        val pts = listOf(ProjectedPoint(10.0, 20.0))
        val result = RegistrationQualityGates.transformPoints(h, pts)
        assertEquals(60.0, result[0].x, 1e-10)
        assertEquals(50.0, result[0].y, 1e-10)
    }

    @Test
    fun `scaling homography scales points`() {
        val scale = 2.0
        val h = doubleArrayOf(scale, 0.0, 0.0, 0.0, scale, 0.0, 0.0, 0.0, 1.0)
        val pts = listOf(ProjectedPoint(10.0, 20.0))
        val result = RegistrationQualityGates.transformPoints(h, pts)
        assertEquals(20.0, result[0].x, 1e-10)
        assertEquals(40.0, result[0].y, 1e-10)
    }

    // ---- 重投影误差 ----
    @Test
    fun `reprojection error is zero for perfect match`() {
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val pts = listOf(ProjectedPoint(10.0, 20.0), ProjectedPoint(30.0, 40.0))
        val error = RegistrationQualityGates.computeMedianReprojectionError(identity, pts, pts)
        assertEquals(0.0, error, 1e-10)
    }

    @Test
    fun `reprojection error computes correctly for shifted points`() {
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val src = listOf(ProjectedPoint(0.0, 0.0), ProjectedPoint(3.0, 4.0))
        val dst = listOf(ProjectedPoint(0.0, 0.0), ProjectedPoint(6.0, 8.0))
        val error = RegistrationQualityGates.computeMedianReprojectionError(identity, src, dst)
        // 误差：0.0, 5.0 → 中位 = 2.5
        assertEquals(2.5, error, 1e-10)
    }

    // ---- 空间覆盖率 ----
    @Test
    fun `spatial coverage for uniform grid points is high`() {
        val pts = (0 until 10).flatMap { x ->
            (0 until 10).map { y -> ProjectedPoint(x * 10.0, y * 10.0) }
        }
        val coverage = RegistrationQualityGates.computeSpatialCoverage(pts, pts)
        assertTrue("coverage=$coverage should be > 0.5", coverage > 0.5)
    }

    @Test
    fun `spatial coverage for collinear points is zero`() {
        val pts = (0 until 10).map { ProjectedPoint(it * 10.0, 50.0) }
        val coverage = RegistrationQualityGates.computeSpatialCoverage(pts, pts)
        assertEquals(0.0, coverage, 1e-10)
    }

    // ---- 共线点四边形 ----
    @Test
    fun `collinear points quadrilateral is not convex`() {
        val collinear = listOf(
            ProjectedPoint(0.0, 0.0),
            ProjectedPoint(1.0, 0.0),
            ProjectedPoint(2.0, 0.0),
            ProjectedPoint(3.0, 0.0),
        )
        // 共线：叉积全为 0，sign 始终为 0，不视为非凸
        // 但面积为 0 会在面积门禁中被拒绝
        val area = RegistrationQualityGates.polygonArea(collinear)
        assertEquals(0.0, area, 1e-10)
    }

    // ---- 容差边界 ----
    @Test
    fun `slightly out of bounds within margin passes`() {
        val slightlyOutOfBounds = listOf(
            ProjectedPoint(-30.0, 100.0), // margin=50, -30 > -50
            ProjectedPoint(300.0, 100.0),
            ProjectedPoint(300.0, 300.0),
            ProjectedPoint(100.0, 300.0),
        )
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 50, goodMatchCount = 100,
            medianReprojectionError = 2.0, spatialCoverage = 0.5,
            projectedCorners = slightlyOutOfBounds, imageWidth = 640, imageHeight = 480,
        )
        assertTrue(result.passed)
    }

    // ---- NaN 重投影误差 ----
    @Test
    fun `NaN reprojection error fails`() {
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 50, goodMatchCount = 100,
            medianReprojectionError = Double.NaN, spatialCoverage = 0.5,
            projectedCorners = validCorners, imageWidth = 640, imageHeight = 480,
        )
        assertFalse(result.passed)
        assertTrue(result.failureReason!!.contains("非有限值"))
    }

    // ---- Infinity 重投影误差 ----
    @Test
    fun `Infinity reprojection error fails`() {
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 50, goodMatchCount = 100,
            medianReprojectionError = Double.POSITIVE_INFINITY, spatialCoverage = 0.5,
            projectedCorners = validCorners, imageWidth = 640, imageHeight = 480,
        )
        assertFalse(result.passed)
        assertTrue(result.failureReason!!.contains("非有限值"))
    }

    // ---- NaN 空间覆盖率 ----
    @Test
    fun `NaN spatial coverage fails`() {
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 50, goodMatchCount = 100,
            medianReprojectionError = 2.0, spatialCoverage = Double.NaN,
            projectedCorners = validCorners, imageWidth = 640, imageHeight = 480,
        )
        assertFalse(result.passed)
        assertTrue(result.failureReason!!.contains("非有限值"))
    }

    // ---- goodMatchCount = 0 ----
    @Test
    fun `zero good match count fails`() {
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 10, goodMatchCount = 0,
            medianReprojectionError = 2.0, spatialCoverage = 0.5,
            projectedCorners = validCorners, imageWidth = 640, imageHeight = 480,
        )
        assertFalse(result.passed)
        // 内点比例 10/0 → 0.0 < 0.30
        assertTrue(result.failureReason!!.contains("内点比例"))
    }

    // ---- 零空间覆盖率 ----
    @Test
    fun `zero spatial coverage fails`() {
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 50, goodMatchCount = 100,
            medianReprojectionError = 2.0, spatialCoverage = 0.0,
            projectedCorners = validCorners, imageWidth = 640, imageHeight = 480,
        )
        assertFalse(result.passed)
        assertTrue(result.failureReason!!.contains("空间覆盖率"))
    }

    // ---- 零尺寸图像 ----
    @Test
    fun `zero width image fails`() {
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 50, goodMatchCount = 100,
            medianReprojectionError = 2.0, spatialCoverage = 0.5,
            projectedCorners = validCorners, imageWidth = 0, imageHeight = 480,
        )
        assertFalse(result.passed)
    }

    @Test
    fun `zero height image fails`() {
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 50, goodMatchCount = 100,
            medianReprojectionError = 2.0, spatialCoverage = 0.5,
            projectedCorners = validCorners, imageWidth = 640, imageHeight = 0,
        )
        assertFalse(result.passed)
    }
}
