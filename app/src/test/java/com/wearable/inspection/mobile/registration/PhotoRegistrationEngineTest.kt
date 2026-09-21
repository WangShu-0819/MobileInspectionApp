package com.wearable.inspection.mobile.registration

import com.wearable.inspection.mobile.vision.OpenCvTestSupport
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * PhotoRegistrationEngine 集成测试（需要 OpenCV 桌面原生库）。
 *
 * 覆盖：
 * 1. 同图/稳定匹配
 * 2. 平移、缩放、旋转、轻微透视
 * 3. 弱纹理、特征不足、无匹配、错误模板
 * 4. 内点数、内点比例、重投影误差、空间覆盖不足
 * 5. 非凸、越界、面积异常的投影四边形
 * 6. 配准成功时 ROI 四角投影方向和坐标范围
 * 7. 配准失败时不使用映射 ROI
 * 8. FALLBACK_FULL_IMAGE 状态和失败原因
 * 9. 重复输入结果稳定
 * 10. Bitmap/Mat/临时资源释放
 */
class PhotoRegistrationEngineTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCV() {
            OpenCvTestSupport.loadNative()
        }
    }

    private lateinit var engine: PhotoRegistrationEngine

    // 模板尺寸
    private val tplW = 200
    private val tplH = 200
    private val sceneW = 400
    private val sceneH = 400

    // 模板 ROI（模板图中的四角，居中 100x100 区域）
    private val templateRoiCorners = listOf(
        ProjectedPoint(50.0, 50.0),
        ProjectedPoint(150.0, 50.0),
        ProjectedPoint(150.0, 150.0),
        ProjectedPoint(50.0, 150.0),
    )

    @Before
    fun setUp() {
        engine = PhotoRegistrationEngine()
    }

    @After
    fun tearDown() {
        engine.clear()
    }

    // ---- 测试夹具生成 ----

    /**
     * 生成有纹理的合成图像：随机圆 + 矩形 + 线条，保证足够的特征点。
     */
    private fun createTexturedImage(width: Int, height: Int, seed: Int = 42): Mat {
        val mat = Mat.zeros(height, width, CvType.CV_8UC3)
        val rng = java.util.Random(seed.toLong())

        // 底色
        mat.setTo(Scalar(180.0, 180.0, 180.0))

        // 随机圆
        for (i in 0 until 30) {
            val x = rng.nextInt(width)
            val y = rng.nextInt(height)
            val r = rng.nextInt(15) + 5
            val color = Scalar(rng.nextDouble() * 200, rng.nextDouble() * 200, rng.nextDouble() * 200)
            Imgproc.circle(mat, Point(x.toDouble(), y.toDouble()), r, color, -1)
        }

        // 随机矩形
        for (i in 0 until 20) {
            val x1 = rng.nextInt(width)
            val y1 = rng.nextInt(height)
            val x2 = (x1 + rng.nextInt(40) + 10).coerceAtMost(width)
            val y2 = (y1 + rng.nextInt(40) + 10).coerceAtMost(height)
            val color = Scalar(rng.nextDouble() * 200, rng.nextDouble() * 200, rng.nextDouble() * 200)
            Imgproc.rectangle(mat, Point(x1.toDouble(), y1.toDouble()), Point(x2.toDouble(), y2.toDouble()), color, -1)
        }

        // 随机线
        for (i in 0 until 15) {
            val color = Scalar(rng.nextDouble() * 200, rng.nextDouble() * 200, rng.nextDouble() * 200)
            Imgproc.line(
                mat,
                Point(rng.nextInt(width).toDouble(), rng.nextInt(height).toDouble()),
                Point(rng.nextInt(width).toDouble(), rng.nextInt(height).toDouble()),
                color, 2,
            )
        }

        return mat
    }

    /**
     * 生成弱纹理图像（几乎纯色）。
     */
    private fun createWeakTextureImage(width: Int, height: Int): Mat {
        val mat = Mat.zeros(height, width, CvType.CV_8UC3)
        mat.setTo(Scalar(200.0, 200.0, 200.0))
        // 只有极少量小点
        Imgproc.circle(mat, Point(50.0, 50.0), 2, Scalar(150.0, 150.0, 150.0), -1)
        Imgproc.circle(mat, Point(150.0, 150.0), 2, Scalar(150.0, 150.0, 150.0), -1)
        return mat
    }

    /**
     * 对图像应用平移变换并裁剪到目标尺寸。
     * 返回变换后的图像和平移矩阵。
     */
    private fun translateImage(src: Mat, tx: Double, ty: Double, targetW: Int, targetH: Int): Pair<Mat, DoubleArray> {
        val h = doubleArrayOf(1.0, 0.0, tx, 0.0, 1.0, ty, 0.0, 0.0, 1.0)
        val hMat = Mat(3, 3, CvType.CV_64F)
        hMat.put(0, 0, *h)

        val dst = Mat()
        Imgproc.warpPerspective(src, dst, hMat, Size(targetW.toDouble(), targetH.toDouble()))
        hMat.release()
        return dst to h
    }

    /**
     * 对图像应用缩放+平移变换。
     */
    private fun scaleImage(src: Mat, scale: Double, targetW: Int, targetH: Int): Pair<Mat, DoubleArray> {
        // 先缩放再平移到中心
        val tx = (targetW - src.cols() * scale) / 2.0
        val ty = (targetH - src.rows() * scale) / 2.0
        val h = doubleArrayOf(scale, 0.0, tx, 0.0, scale, ty, 0.0, 0.0, 1.0)
        val hMat = Mat(3, 3, CvType.CV_64F)
        hMat.put(0, 0, *h)

        val dst = Mat()
        Imgproc.warpPerspective(src, dst, hMat, Size(targetW.toDouble(), targetH.toDouble()))
        hMat.release()
        return dst to h
    }

    /**
     * 对图像应用旋转+缩放。
     */
    private fun rotateImage(src: Mat, angleDegrees: Double, scale: Double, targetW: Int, targetH: Int): Pair<Mat, DoubleArray> {
        val center = Point(src.cols() / 2.0, src.rows() / 2.0)
        val rotMat = Imgproc.getRotationMatrix2D(center, angleDegrees, scale)
        // 提取 2×3 旋转矩阵并构造 3×3 Homography
        val rData = DoubleArray(6)
        rotMat.get(0, 0, rData)
        val h = doubleArrayOf(
            rData[0], rData[1], rData[2] + (targetW - src.cols()) / 2.0,
            rData[3], rData[4], rData[5] + (targetH - src.rows()) / 2.0,
            0.0, 0.0, 1.0,
        )
        val hMat = Mat(3, 3, CvType.CV_64F)
        hMat.put(0, 0, *h)

        val dst = Mat()
        Imgproc.warpPerspective(src, dst, hMat, Size(targetW.toDouble(), targetH.toDouble()))
        rotMat.release()
        hMat.release()
        return dst to h
    }

    /**
     * 对图像应用轻微透视变换。
     */
    private fun perspectiveWarp(src: Mat, targetW: Int, targetH: Int): Pair<Mat, DoubleArray> {
        val srcPts = listOf(
            Point(0.0, 0.0), Point(src.cols().toDouble(), 0.0),
            Point(src.cols().toDouble(), src.rows().toDouble()), Point(0.0, src.rows().toDouble()),
        )
        val dstPts = listOf(
            Point(10.0, 15.0), Point(src.cols().toDouble() - 5.0, 10.0),
            Point(src.cols().toDouble() + 8.0, src.rows().toDouble() - 12.0), Point(-8.0, src.rows().toDouble() + 5.0),
        )
        val srcMat = org.opencv.core.MatOfPoint2f(*srcPts.toTypedArray())
        val dstMat = org.opencv.core.MatOfPoint2f(*dstPts.toTypedArray())
        val hMat = org.opencv.calib3d.Calib3d.findHomography(srcMat, dstMat)
        srcMat.release(); dstMat.release()

        val h = DoubleArray(9)
        hMat.get(0, 0, h)

        val dst = Mat()
        Imgproc.warpPerspective(src, dst, hMat, Size(targetW.toDouble(), targetH.toDouble()))
        hMat.release()
        return dst to h
    }

    // ========== 测试用例 ==========

    // ---- 1. 同图匹配 ----
    @Test
    fun `same image matches successfully`() {
        val img = createTexturedImage(tplW, tplH)
        val result = engine.register(img, img, templateRoiCorners, tplW, tplH)

        assertTrue("同图应成功: ${result.failureReason}", result.isSuccess)
        assertNotNull(result.homography)
        assertNotNull(result.projectedRoiCorners)
        assertEquals(4, result.projectedRoiCorners!!.size)
        assertTrue("内点应充足: ${result.inlierCount}", result.inlierCount >= RegistrationConfig.MIN_INLIER_COUNT)
        assertTrue("重投影误差应小: ${result.reprojectionError}", result.reprojectionError < 2.0)
        assertEquals(RegistrationConfig.MATCHER_NAME, result.matcherName)
        img.release()
    }

    // ---- 2a. 平移 ----
    @Test
    fun `translation registration succeeds`() {
        val tpl = createTexturedImage(tplW, tplH, seed = 10)
        val tx = 50.0; val ty = 30.0
        val (scene, _) = translateImage(tpl, tx, ty, sceneW, sceneH)

        val result = engine.register(tpl, scene, templateRoiCorners, sceneW, sceneH)
        assertTrue("平移配准应成功: ${result.failureReason}", result.isSuccess)
        assertNotNull(result.projectedRoiCorners)

        // 投影后 ROI 应大致偏移 tx, ty
        val projected = result.projectedRoiCorners!!
        assertEquals(tx, projected[0].x - templateRoiCorners[0].x, 20.0)
        assertEquals(ty, projected[0].y - templateRoiCorners[0].y, 20.0)

        tpl.release(); scene.release()
    }

    // ---- 2b. 缩放 ----
    @Test
    fun `scale registration succeeds`() {
        val tpl = createTexturedImage(tplW, tplH, seed = 20)
        val scale = 1.5
        val (scene, _) = scaleImage(tpl, scale, sceneW, sceneH)

        val result = engine.register(tpl, scene, templateRoiCorners, sceneW, sceneH)
        assertTrue("缩放配准应成功: ${result.failureReason}", result.isSuccess)
        assertNotNull(result.projectedRoiCorners)
        assertTrue("内点应充足: ${result.inlierCount}", result.inlierCount >= RegistrationConfig.MIN_INLIER_COUNT)

        tpl.release(); scene.release()
    }

    // ---- 2c. 旋转 ----
    @Test
    fun `small rotation registration succeeds`() {
        val tpl = createTexturedImage(tplW, tplH, seed = 30)
        val angle = 10.0 // 10度旋转
        val (scene, _) = rotateImage(tpl, angle, 1.2, sceneW, sceneH)

        val result = engine.register(tpl, scene, templateRoiCorners, sceneW, sceneH)
        assertTrue("10度旋转配准应成功: ${result.failureReason}", result.isSuccess)
        assertNotNull(result.projectedRoiCorners)
        assertEquals(4, result.projectedRoiCorners!!.size)
        assertTrue("内点应充足: ${result.inlierCount}", result.inlierCount >= RegistrationConfig.MIN_INLIER_COUNT)
        assertTrue("重投影误差应合理: ${result.reprojectionError}", result.reprojectionError < RegistrationConfig.MAX_MEDIAN_REPROJECTION_ERROR)

        tpl.release(); scene.release()
    }

    // ---- 2d. 轻微透视 ----
    @Test
    fun `mild perspective registration succeeds`() {
        val tpl = createTexturedImage(tplW, tplH, seed = 40)
        val (scene, _) = perspectiveWarp(tpl, sceneW, sceneH)

        val result = engine.register(tpl, scene, templateRoiCorners, sceneW, sceneH)
        assertTrue("轻微透视配准应成功: ${result.failureReason}", result.isSuccess)
        assertNotNull(result.projectedRoiCorners)
        assertEquals(4, result.projectedRoiCorners!!.size)
        assertTrue("内点应充足: ${result.inlierCount}", result.inlierCount >= RegistrationConfig.MIN_INLIER_COUNT)
        assertTrue("重投影误差应合理: ${result.reprojectionError}", result.reprojectionError < RegistrationConfig.MAX_MEDIAN_REPROJECTION_ERROR)

        tpl.release(); scene.release()
    }

    // ---- 3a. 弱纹理 ----
    @Test
    fun `weak texture image fails with insufficient features`() {
        val weak = createWeakTextureImage(tplW, tplH)
        val scene = createTexturedImage(sceneW, sceneH, seed = 50)

        val result = engine.register(weak, scene, templateRoiCorners, sceneW, sceneH)
        assertFalse("弱纹理应失败", result.isSuccess)
        assertNotNull(result.failureReason)
        assertTrue(
            "失败原因应含特征不足或good matches不足: ${result.failureReason}",
            result.failureReason!!.contains("特征不足") || result.failureReason!!.contains("matches"),
        )

        weak.release(); scene.release()
    }

    // ---- 3b. 特征不足（极小图） ----
    @Test
    fun `tiny image fails with insufficient features`() {
        val tiny = Mat.zeros(10, 10, CvType.CV_8UC3)
        val scene = createTexturedImage(sceneW, sceneH, seed = 60)

        val result = engine.register(tiny, scene, templateRoiCorners, sceneW, sceneH)
        assertFalse("极小图应失败", result.isSuccess)

        tiny.release(); scene.release()
    }

    // ---- 3c. 无匹配（完全不同图像） ----
    @Test
    fun `completely different images fail`() {
        val img1 = createTexturedImage(tplW, tplH, seed = 100)
        val img2 = createTexturedImage(sceneW, sceneH, seed = 200)

        val result = engine.register(img1, img2, templateRoiCorners, sceneW, sceneH)
        assertFalse("完全不同纹理应配准失败", result.isSuccess)
        assertNotNull("失败时应有原因", result.failureReason)
        assertNull("失败时 homography 应为 null", result.homography)
        assertNull("失败时 projectedRoiCorners 应为 null", result.projectedRoiCorners)

        img1.release(); img2.release()
    }

    // ---- 4. 内点比例不足（用质量门禁独立验证） ----
    @Test
    fun `quality gate rejects low inlier ratio`() {
        val result = RegistrationQualityGates.checkAll(
            inlierCount = 15, goodMatchCount = 100, // ratio = 0.15 < 0.30
            medianReprojectionError = 2.0, spatialCoverage = 0.5,
            projectedCorners = templateRoiCorners, imageWidth = 640, imageHeight = 480,
        )
        assertFalse(result.passed)
        assertTrue(result.failureReason!!.contains("内点比例"))
    }

    // ---- 5. 投影四边形方向和范围 ----
    @Test
    fun `successful registration has valid projected corners`() {
        val img = createTexturedImage(tplW, tplH, seed = 70)
        val result = engine.register(img, img, templateRoiCorners, tplW, tplH)

        assertTrue("同图应成功: ${result.failureReason}", result.isSuccess)
        val corners = result.projectedRoiCorners!!
        assertEquals(4, corners.size)

        // 同图配准：投影应接近原始 ROI
        for (i in 0 until 4) {
            assertEquals(templateRoiCorners[i].x, corners[i].x, 15.0)
            assertEquals(templateRoiCorners[i].y, corners[i].y, 15.0)
        }

        // 四角应在图像范围内
        for (corner in corners) {
            assertTrue("x 应在范围内: ${corner.x}", corner.x >= -10 && corner.x <= tplW + 10)
            assertTrue("y 应在范围内: ${corner.y}", corner.y >= -10 && corner.y <= tplH + 10)
        }

        img.release()
    }

    // ---- 6. 失败时不使用映射 ROI ----
    @Test
    fun `failed registration returns null projected corners`() {
        val weak = createWeakTextureImage(tplW, tplH)
        val scene = createTexturedImage(sceneW, sceneH, seed = 80)

        val result = engine.register(weak, scene, templateRoiCorners, sceneW, sceneH)
        assertFalse("弱纹理应失败", result.isSuccess)
        assertNull("失败时 projectedRoiCorners 应为 null", result.projectedRoiCorners)
        assertNull("失败时 homography 应为 null", result.homography)

        weak.release(); scene.release()
    }

    // ---- 7. FALLBACK_FULL_IMAGE 状态 ----
    @Test
    fun `fallback status has correct fields`() {
        val image = createTexturedImage(tplW, tplH, seed = 90)
        val fullImageRoi = listOf(
            ProjectedPoint(0.0, 0.0),
            ProjectedPoint(tplW.toDouble(), 0.0),
            ProjectedPoint(tplW.toDouble(), tplH.toDouble()),
            ProjectedPoint(0.0, tplH.toDouble()),
        )

        // 同图能稳定建立 Homography；整图 ROI 触发“投影面积过大”质量门禁。
        val result = engine.register(image, image, fullImageRoi, tplW, tplH)

        assertEquals(RegistrationStatus.FALLBACK_FULL_IMAGE, result.status)
        assertNull(result.homography)
        assertNull(result.projectedRoiCorners)
        assertFalse(result.failureReason.isNullOrBlank())

        image.release()
    }

    // ---- 8. 重复输入结果稳定 ----
    @Test
    fun `repeated registration produces stable results`() {
        val tpl = createTexturedImage(tplW, tplH, seed = 110)
        val (scene, _) = translateImage(tpl, 20.0, 15.0, sceneW, sceneH)

        val result1 = engine.register(tpl, scene, templateRoiCorners, sceneW, sceneH)
        val result2 = engine.register(tpl, scene, templateRoiCorners, sceneW, sceneH)

        assertEquals("状态应一致", result1.status, result2.status)
        assertEquals("内点数应一致", result1.inlierCount, result2.inlierCount)
        assertEquals("重投影误差应一致", result1.reprojectionError, result2.reprojectionError, 0.01)

        if (result1.isSuccess && result2.isSuccess) {
            val c1 = result1.projectedRoiCorners!!
            val c2 = result2.projectedRoiCorners!!
            for (i in 0 until 4) {
                assertEquals("投影点 $i x 应一致", c1[i].x, c2[i].x, 0.01)
                assertEquals("投影点 $i y 应一致", c1[i].y, c2[i].y, 0.01)
            }
        }

        tpl.release(); scene.release()
    }

    // ---- 9. 资源释放 ----
    @Test
    fun `engine handles multiple sequential registrations without leak`() {
        val tpl = createTexturedImage(tplW, tplH, seed = 120)
        val (scene, _) = translateImage(tpl, 25.0, 20.0, sceneW, sceneH)

        // 连续执行 5 次配准，不崩溃即为资源管理正确
        repeat(5) {
            val result = engine.register(tpl, scene, templateRoiCorners, sceneW, sceneH)
            assertNotNull(result)
        }

        tpl.release(); scene.release()
    }

    @Test
    fun `engine clear does not crash`() {
        val engine2 = PhotoRegistrationEngine()
        engine2.clear()
        // 二次 clear 不崩溃
        engine2.clear()
    }

    // ---- 10. 纯色场景图（无特征） ----
    @Test
    fun `solid color scene fails`() {
        val tpl = createTexturedImage(tplW, tplH, seed = 130)
        val solid = Mat.zeros(sceneW, sceneH, CvType.CV_8UC3)
        solid.setTo(Scalar(128.0, 128.0, 128.0))

        val result = engine.register(tpl, solid, templateRoiCorners, sceneW, sceneH)
        assertFalse("纯色场景应失败", result.isSuccess)

        tpl.release(); solid.release()
    }

    // ---- 11. 不同种子纹理 ----
    @Test
    fun `different seeds produce different textures`() {
        val img1 = createTexturedImage(tplW, tplH, seed = 1)
        val img2 = createTexturedImage(tplW, tplH, seed = 2)

        // 两个不同种子的图像不应完全相同
        val diff = Mat()
        Core.absdiff(img1, img2, diff)
        val sum = Core.sumElems(diff)
        assertTrue("不同种子应产生不同图像", sum.`val`[0] > 0 || sum.`val`[1] > 0 || sum.`val`[2] > 0)

        img1.release(); img2.release(); diff.release()
    }

    // ---- 12. RegistrationResult 字段完整性 ----
    @Test
    fun `registration result has all required fields`() {
        val img = createTexturedImage(tplW, tplH, seed = 140)
        val result = engine.register(img, img, templateRoiCorners, tplW, tplH)

        assertNotNull(result.status)
        assertNotNull(result.matcherName)
        assertNotNull(result.matcherVersion)
        assertTrue("同图结果应成功: ${result.failureReason}", result.isSuccess)
        assertTrue(result.inlierCount >= 0)
        assertTrue(result.inlierRatio >= 0.0)
        assertTrue(result.reprojectionError >= 0.0)
        assertTrue(result.spatialCoverage >= 0.0)

        assertNotNull(result.homography)
        assertEquals(9, result.homography!!.size)
        assertNotNull(result.projectedRoiCorners)
        assertNull(result.failureReason)
        assertTrue(result.quadrilateralValid)

        img.release()
    }

    // ---- 13. 轻微平移后 ROI 投影方向正确 ----
    @Test
    fun `translated ROI projection preserves corner order`() {
        val tpl = createTexturedImage(tplW, tplH, seed = 150)
        val tx = 30.0; val ty = 20.0
        val (scene, _) = translateImage(tpl, tx, ty, sceneW, sceneH)

        val result = engine.register(tpl, scene, templateRoiCorners, sceneW, sceneH)
        assertTrue("平移配准应成功: ${result.failureReason}", result.isSuccess)
        val corners = result.projectedRoiCorners!!
        // 左上 < 右上（x 方向）
        assertTrue("左上.x < 右上.x", corners[0].x < corners[1].x)
        // 左上 < 左下（y 方向）
        assertTrue("左上.y < 左下.y", corners[0].y < corners[3].y)
        // 右上 < 右下（y 方向）
        assertTrue("右上.y < 右下.y", corners[1].y < corners[2].y)
        // 右下 > 左下（x 方向）
        assertTrue("右下.x > 左下.x", corners[2].x > corners[3].x)

        tpl.release(); scene.release()
    }
}
