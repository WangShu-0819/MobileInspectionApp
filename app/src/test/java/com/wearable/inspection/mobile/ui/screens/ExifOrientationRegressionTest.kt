package com.wearable.inspection.mobile.ui.screens

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * 真实 EXIF Orientation=6/8 JPEG 回归测试。
 *
 * 验证链路：写入带 EXIF 方向的非对称 JPEG → getImageGeometry 读取原始尺寸/方向
 * → loadUprightBitmap 输出 upright Bitmap → 宽高/方向与 PhotoGeometry 一致
 * → mapToImagePixels / mapImageBoxToCanvas 用 upright 尺寸做确定性坐标映射。
 *
 * 不接受仅直接构造 portrait Bitmap 的测试作为方向证据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ExifOrientationRegressionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ═══════════════════════════════════════════════════════════════════
    // ORIENTATION_ROTATE_90 (6) — 最常见的手机竖拍场景
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `orientation 6 - getImageGeometry reads raw dimensions and exif tag`() {
        // 创建非对称 JPEG：raw 200x100（横），EXIF orientation=6 → upright 100x200（竖）
        val file = createJpegWithExifOrientation(
            rawWidth = 200, rawHeight = 100,
            exifOrientation = ExifInterface.ORIENTATION_ROTATE_90, // 6
            fileName = "orient6_geometry.jpg",
        )
        val geometry = RoiCoordinateMapper.getImageGeometry(file.absolutePath)
        assertNotNull("getImageGeometry should succeed", geometry)
        assertEquals("rawWidth should be 200", 200, geometry!!.rawWidth)
        assertEquals("rawHeight should be 100", 100, geometry.rawHeight)
        assertEquals("exifOrientation should be 6", ExifInterface.ORIENTATION_ROTATE_90, geometry.exifOrientation)
        // PhotoGeometry.width/height 在 ORIENTATION_ROTATE_90 时应互换
        assertEquals("upright width = rawHeight", 100, geometry.width)
        assertEquals("upright height = rawWidth", 200, geometry.height)
    }

    @Test
    fun `orientation 6 - loadUprightBitmap produces portrait bitmap`() {
        val file = createJpegWithExifOrientation(
            rawWidth = 200, rawHeight = 100,
            exifOrientation = ExifInterface.ORIENTATION_ROTATE_90,
            fileName = "orient6_upright.jpg",
        )
        val bitmap = RoiCoordinateMapper.loadUprightBitmap(file.absolutePath)
        assertNotNull("loadUprightBitmap should return non-null", bitmap)
        // Robolectric 的 shadow BitmapFactory 可能不执行真实像素旋转，
        // 但 orientBitmap() 会应用 Matrix 变换 → 宽高应与 PhotoGeometry 一致
        val geometry = RoiCoordinateMapper.getImageGeometry(file.absolutePath)!!
        assertEquals(
            "upright bitmap width should match PhotoGeometry.width",
            geometry.width, bitmap!!.width
        )
        assertEquals(
            "upright bitmap height should match PhotoGeometry.height",
            geometry.height, bitmap.height
        )
        assertTrue("bitmap should be valid", !bitmap.isRecycled)
        bitmap.recycle()
    }

    @Test
    fun `orientation 6 - upright dimensions correlate with FullImageInferResult and Canvas mapping`() {
        val file = createJpegWithExifOrientation(
            rawWidth = 200, rawHeight = 100,
            exifOrientation = ExifInterface.ORIENTATION_ROTATE_90,
            fileName = "orient6_canvas.jpg",
        )
        val geometry = RoiCoordinateMapper.getImageGeometry(file.absolutePath)!!
        val bitmap = RoiCoordinateMapper.loadUprightBitmap(file.absolutePath)!!

        // ── 模拟 FullImageInferResult 的 imageWidth/imageHeight ──
        // 推理引擎接收 upright Bitmap → imageWidth/Height = bitmap.width/height
        val imageWidth = bitmap.width  // 100
        val imageHeight = bitmap.height // 200
        assertEquals("FullImageInferResult.imageWidth", geometry.width, imageWidth)
        assertEquals("FullImageInferResult.imageHeight", geometry.height, imageHeight)

        // ── mapToImagePixels：upright 尺寸下的确定性坐标映射 ──
        val roiRect = NormalizedRect(0.1f, 0.2f, 0.5f, 0.8f)
        val pixelRect = RoiCoordinateMapper.mapToImagePixels(roiRect, imageWidth, imageHeight)
        assertEquals("left = floor(0.1*100)", 10, pixelRect.left)
        assertEquals("top = floor(0.2*200)", 40, pixelRect.top)
        assertEquals("right = ceil(0.5*100)", 50, pixelRect.right)
        assertEquals("bottom = ceil(0.8*200)", 160, pixelRect.bottom)
        assertEquals("pixel width", 40, pixelRect.width)
        assertEquals("pixel height", 120, pixelRect.height)

        // ── mapImageBoxToCanvas：imageBox → Canvas 坐标 ──
        // 1:1 映射（Canvas 尺寸 = 图片尺寸）
        val canvasResult = mapImageBoxToCanvas(
            boxLeft = 0f, boxTop = 0f,
            canvasWidth = imageWidth.toFloat(), canvasHeight = imageHeight.toFloat(),
            imageWidth = imageWidth, imageHeight = imageHeight,
        )
        assertClose("scaleX 1:1", 1.0, canvasResult.scaleX)
        assertClose("scaleY 1:1", 1.0, canvasResult.scaleY)
        assertClose("offsetX 1:1", 0.0, canvasResult.offsetX.toDouble())
        assertClose("offsetY 1:1", 0.0, canvasResult.offsetY.toDouble())

        // imageBox 像素 (10,40)→(50,160) 在 1:1 Canvas 中应直接映射
        val canvasLeft = canvasResult.offsetX + (pixelRect.left * canvasResult.scaleX).toFloat()
        val canvasTop = canvasResult.offsetY + (pixelRect.top * canvasResult.scaleY).toFloat()
        assertClose("canvasLeft", 10.0, canvasLeft.toDouble())
        assertClose("canvasTop", 40.0, canvasTop.toDouble())

        // ── 非1:1 Canvas 映射（竖图放入横容器）──
        val canvasResult2 = mapImageBoxToCanvas(
            boxLeft = 0f, boxTop = 0f,
            canvasWidth = 200f, canvasHeight = 200f,
            imageWidth = imageWidth, imageHeight = imageHeight,
        )
        // fitScale = min(200/100, 200/200) = min(2.0, 1.0) = 1.0
        assertClose("fitScale for 200x200 canvas", 1.0, canvasResult2.scaleX)
        // offsetX = (200 - 100*1.0) / 2 = 50
        assertClose("offsetX centered", 50.0, canvasResult2.offsetX.toDouble())

        bitmap.recycle()
    }

    // ═══════════════════════════════════════════════════════════════════
    // ORIENTATION_ROTATE_270 (8)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `orientation 8 - getImageGeometry reads raw dimensions and exif tag`() {
        val file = createJpegWithExifOrientation(
            rawWidth = 300, rawHeight = 150,
            exifOrientation = ExifInterface.ORIENTATION_ROTATE_270, // 8
            fileName = "orient8_geometry.jpg",
        )
        val geometry = RoiCoordinateMapper.getImageGeometry(file.absolutePath)
        assertNotNull("getImageGeometry should succeed", geometry)
        assertEquals("rawWidth should be 300", 300, geometry!!.rawWidth)
        assertEquals("rawHeight should be 150", 150, geometry.rawHeight)
        assertEquals("exifOrientation should be 8", ExifInterface.ORIENTATION_ROTATE_270, geometry.exifOrientation)
        // ORIENTATION_ROTATE_270 也在 ORIENTATIONS_SWAP_WIDTH_HEIGHT 中
        assertEquals("upright width = rawHeight", 150, geometry.width)
        assertEquals("upright height = rawWidth", 300, geometry.height)
    }

    @Test
    fun `orientation 8 - loadUprightBitmap produces portrait bitmap`() {
        val file = createJpegWithExifOrientation(
            rawWidth = 300, rawHeight = 150,
            exifOrientation = ExifInterface.ORIENTATION_ROTATE_270,
            fileName = "orient8_upright.jpg",
        )
        val bitmap = RoiCoordinateMapper.loadUprightBitmap(file.absolutePath)
        assertNotNull("loadUprightBitmap should return non-null", bitmap)
        val geometry = RoiCoordinateMapper.getImageGeometry(file.absolutePath)!!
        assertEquals(
            "upright bitmap width should match PhotoGeometry.width",
            geometry.width, bitmap!!.width
        )
        assertEquals(
            "upright bitmap height should match PhotoGeometry.height",
            geometry.height, bitmap.height
        )
        bitmap.recycle()
    }

    @Test
    fun `orientation 8 - upright dimensions correlate with FullImageInferResult and Canvas mapping`() {
        val file = createJpegWithExifOrientation(
            rawWidth = 300, rawHeight = 150,
            exifOrientation = ExifInterface.ORIENTATION_ROTATE_270,
            fileName = "orient8_canvas.jpg",
        )
        val geometry = RoiCoordinateMapper.getImageGeometry(file.absolutePath)!!
        val bitmap = RoiCoordinateMapper.loadUprightBitmap(file.absolutePath)!!

        val imageWidth = bitmap.width  // 150
        val imageHeight = bitmap.height // 300
        assertEquals("FullImageInferResult.imageWidth", geometry.width, imageWidth)
        assertEquals("FullImageInferResult.imageHeight", geometry.height, imageHeight)

        // mapToImagePixels 确定性坐标
        val roiRect = NormalizedRect(0.2f, 0.1f, 0.8f, 0.6f)
        val pixelRect = RoiCoordinateMapper.mapToImagePixels(roiRect, imageWidth, imageHeight)
        assertEquals("left = floor(0.2*150)", 30, pixelRect.left)
        assertEquals("top = floor(0.1*300)", 30, pixelRect.top)
        assertEquals("right = ceil(0.8*150)", 120, pixelRect.right)
        assertEquals("bottom = ceil(0.6*300)", 180, pixelRect.bottom)

        // 1:1 Canvas
        val canvasResult = mapImageBoxToCanvas(
            boxLeft = 0f, boxTop = 0f,
            canvasWidth = imageWidth.toFloat(), canvasHeight = imageHeight.toFloat(),
            imageWidth = imageWidth, imageHeight = imageHeight,
        )
        assertClose("scaleX 1:1", 1.0, canvasResult.scaleX)
        assertClose("scaleY 1:1", 1.0, canvasResult.scaleY)

        // 竖图放入方形容器：左右留白
        val canvasResult2 = mapImageBoxToCanvas(
            boxLeft = 0f, boxTop = 0f,
            canvasWidth = 300f, canvasHeight = 300f,
            imageWidth = imageWidth, imageHeight = imageHeight,
        )
        // fitScale = min(300/150, 300/300) = min(2.0, 1.0) = 1.0
        assertClose("fitScale", 1.0, canvasResult2.scaleX)
        // offsetX = (300 - 150*1.0) / 2 = 75
        assertClose("offsetX centered", 75.0, canvasResult2.offsetX.toDouble())

        bitmap.recycle()
    }

    // ═══════════════════════════════════════════════════════════════════
    // ORIENTATION_NORMAL (1) 对照组：不交换宽高
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `orientation 1 (normal) - dimensions are not swapped`() {
        val file = createJpegWithExifOrientation(
            rawWidth = 200, rawHeight = 100,
            exifOrientation = ExifInterface.ORIENTATION_NORMAL,
            fileName = "orient1_normal.jpg",
        )
        val geometry = RoiCoordinateMapper.getImageGeometry(file.absolutePath)!!
        assertEquals(200, geometry.width)
        assertEquals(100, geometry.height)
        val bitmap = RoiCoordinateMapper.loadUprightBitmap(file.absolutePath)!!
        assertEquals(200, bitmap.width)
        assertEquals(100, bitmap.height)
        bitmap.recycle()
    }

    // ═══════════════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 创建带指定 EXIF 方向的非对称 JPEG 文件。
     *
     * 流程：
     * 1. 创建 rawWidth×rawHeight ARGB_8888 Bitmap（左上角设红色像素，作为可识别标记）
     * 2. 压缩为 JPEG
     * 3. 使用 ExifInterface 写入 orientation 标签
     *
     * @param rawWidth  原始像素宽度（EXIF 旋转前）
     * @param rawHeight 原始像素高度（EXIF 旋转前）
     * @param exifOrientation EXIF 方向常量
     * @param fileName 临时文件名
     */
    private fun createJpegWithExifOrientation(
        rawWidth: Int,
        rawHeight: Int,
        exifOrientation: Int,
        fileName: String,
    ): File {
        val file = File(tempFolder.root, fileName)
        // 创建非对称 Bitmap
        val bitmap = Bitmap.createBitmap(rawWidth, rawHeight, Bitmap.Config.ARGB_8888)
        // 在左上角设红色像素作为可识别标记
        bitmap.setPixel(0, 0, Color.RED)
        // 在右下角设蓝色像素
        bitmap.setPixel(rawWidth - 1, rawHeight - 1, Color.BLUE)
        // 压缩为 JPEG
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        bitmap.recycle()
        // 写入 EXIF orientation 标签
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
        exif.saveAttributes()
        return file
    }

    private fun assertClose(label: String, expected: Double, actual: Double, tolerance: Double = 0.01) {
        assertTrue(
            "$label: expected=$expected, actual=$actual, diff=${abs(expected - actual)} > tolerance=$tolerance",
            abs(expected - actual) <= tolerance
        )
    }
}
