package com.wearable.inspection.mobile.ui.screens

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.wearable.inspection.mobile.detection.NanoDetBox
import com.wearable.inspection.mobile.detection.NanoDetDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * 整图检测框叠加组件回归测试。
 *
 * 覆盖场景：
 * - 坐标映射：imageBox 像素坐标 → Canvas 显示坐标（mapImageBoxToCanvas）
 * - ContentScale.Fit 留白偏移（非同宽高比）
 * - 竖拍/upright 图片
 * - 边界框（贴边检测框）
 * - 空检测
 * - 图片加载失败
 * - 推理失败
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FullImageDetectionOverlayTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ViewConfirmationTestActivity>()

    private val testBitmap: Bitmap
        get() = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)

    private fun sampleDetections() = listOf(
        NanoDetDetection(
            classIndex = 0, className = "螺母", score = 0.92f, point = 5,
            roiBox = NanoDetBox(10.0, 20.0, 110.0, 120.0),
            imageBox = NanoDetBox(50.0, 60.0, 200.0, 250.0)
        ),
        NanoDetDetection(
            classIndex = 1, className = "螺纹", score = 0.78f, point = 3,
            roiBox = NanoDetBox(200.0, 100.0, 400.0, 300.0),
            imageBox = NanoDetBox(300.0, 150.0, 500.0, 400.0)
        )
    )

    // ═══════════════════════════════════════════════════════════════════
    // 坐标映射纯函数测试（mapImageBoxToCanvas）
    // ═══════════════════════════════════════════════════════════════════

    /** 同宽高比：fitScale 填满，offset 为零 */
    @Test
    fun mapImageBoxToCanvas_sameAspectRatio_fillsExactly() {
        // 640x480 图片 → 320x240 Canvas（0.5x）
        val r = mapImageBoxToCanvas(
            boxLeft = 0f, boxTop = 0f,
            canvasWidth = 320f, canvasHeight = 240f,
            imageWidth = 640, imageHeight = 480,
        )
        assertClose("scaleX", 0.5, r.scaleX)
        assertClose("scaleY", 0.5, r.scaleY)
        assertClose("offsetX", 0.0, r.offsetX.toDouble())
        assertClose("offsetY", 0.0, r.offsetY.toDouble())

        // imageBox(50,60)→(200,250) 应映射到 Canvas(25,30)→(100,125)
        val det = NanoDetDetection(
            classIndex = 0, className = "test", score = 0.9f, point = 0,
            roiBox = NanoDetBox(0.0, 0.0, 0.0, 0.0),
            imageBox = NanoDetBox(50.0, 60.0, 200.0, 250.0)
        )
        val left = r.offsetX + (det.imageBox.left * r.scaleX).toFloat()
        val top = r.offsetY + (det.imageBox.top * r.scaleY).toFloat()
        val right = r.offsetX + (det.imageBox.right * r.scaleX).toFloat()
        val bottom = r.offsetY + (det.imageBox.bottom * r.scaleY).toFloat()
        assertClose("left", 25.0, left.toDouble())
        assertClose("top", 30.0, top.toDouble())
        assertClose("right", 100.0, right.toDouble())
        assertClose("bottom", 125.0, bottom.toDouble())
    }

    /** 非同宽高比（横图放入竖容器）：上下留黑边，offsetY > 0 */
    @Test
    fun mapImageBoxToCanvas_landscapeInPortraitContainer_appliesPadding() {
        // 640x480 图片 → 200x300 Canvas
        // fitScale = min(200/640, 300/480) = min(0.3125, 0.625) = 0.3125
        // displayedW = 640 * 0.3125 = 200, displayedH = 480 * 0.3125 = 150
        // offsetX = (200 - 200) / 2 = 0, offsetY = (300 - 150) / 2 = 75
        val r = mapImageBoxToCanvas(
            boxLeft = 0f, boxTop = 0f,
            canvasWidth = 200f, canvasHeight = 300f,
            imageWidth = 640, imageHeight = 480,
        )
        assertClose("scaleX", 0.3125, r.scaleX)
        assertClose("scaleY", 0.3125, r.scaleY)
        assertClose("offsetX", 0.0, r.offsetX.toDouble())
        assertClose("offsetY", 75.0, r.offsetY.toDouble())

        // imageBox(50,60)→(200,250) 应映射到：
        // left = 0 + 50 * 0.3125 = 15.625
        // top = 75 + 60 * 0.3125 = 93.75
        // right = 0 + 200 * 0.3125 = 62.5
        // bottom = 75 + 250 * 0.3125 = 153.125
        val left = r.offsetX + (50.0 * r.scaleX).toFloat()
        val top = r.offsetY + (60.0 * r.scaleY).toFloat()
        val right = r.offsetX + (200.0 * r.scaleX).toFloat()
        val bottom = r.offsetY + (250.0 * r.scaleY).toFloat()
        assertClose("left", 15.625, left.toDouble())
        assertClose("top", 93.75, top.toDouble())
        assertClose("right", 62.5, right.toDouble())
        assertClose("bottom", 153.125, bottom.toDouble())
    }

    /** 竖图放入横容器：左右留黑边，offsetX > 0 */
    @Test
    fun mapImageBoxToCanvas_portraitInLandscapeContainer_appliesPadding() {
        // 480x640 图片 → 400x200 Canvas
        // fitScale = min(400/480, 200/640) = min(0.8333, 0.3125) = 0.3125
        // displayedW = 480 * 0.3125 = 150, displayedH = 640 * 0.3125 = 200
        // offsetX = (400 - 150) / 2 = 125, offsetY = (200 - 200) / 2 = 0
        val r = mapImageBoxToCanvas(
            boxLeft = 0f, boxTop = 0f,
            canvasWidth = 400f, canvasHeight = 200f,
            imageWidth = 480, imageHeight = 640,
        )
        assertClose("scaleX", 0.3125, r.scaleX)
        assertClose("scaleY", 0.3125, r.scaleY)
        assertClose("offsetX", 125.0, r.offsetX.toDouble())
        assertClose("offsetY", 0.0, r.offsetY.toDouble())
    }

    /** 左上角检测框(0,0)→(100,100) */
    @Test
    fun mapImageBoxToCanvas_topLeftBox_mapsCorrectly() {
        val r = mapImageBoxToCanvas(
            boxLeft = 0f, boxTop = 0f,
            canvasWidth = 640f, canvasHeight = 480f,
            imageWidth = 640, imageHeight = 480,
        )
        val left = r.offsetX + (0.0 * r.scaleX).toFloat()
        val top = r.offsetY + (0.0 * r.scaleY).toFloat()
        assertClose("left", 0.0, left.toDouble())
        assertClose("top", 0.0, top.toDouble())
    }

    /** 右下角检测框(540,380)→(640,480) */
    @Test
    fun mapImageBoxToCanvas_bottomRightBox_mapsCorrectly() {
        val r = mapImageBoxToCanvas(
            boxLeft = 0f, boxTop = 0f,
            canvasWidth = 640f, canvasHeight = 480f,
            imageWidth = 640, imageHeight = 480,
        )
        val right = r.offsetX + (640.0 * r.scaleX).toFloat()
        val bottom = r.offsetY + (480.0 * r.scaleY).toFloat()
        assertClose("right", 640.0, right.toDouble())
        assertClose("bottom", 480.0, bottom.toDouble())
    }

    /** 贴边检测框(0,0)→(640,480)覆盖全图 */
    @Test
    fun mapImageBoxToCanvas_fullImageBox_mapsToFullCanvas() {
        val r = mapImageBoxToCanvas(
            boxLeft = 0f, boxTop = 0f,
            canvasWidth = 320f, canvasHeight = 240f,
            imageWidth = 640, imageHeight = 480,
        )
        val left = r.offsetX + (0.0 * r.scaleX).toFloat()
        val top = r.offsetY + (0.0 * r.scaleY).toFloat()
        val right = r.offsetX + (640.0 * r.scaleX).toFloat()
        val bottom = r.offsetY + (480.0 * r.scaleY).toFloat()
        assertClose("left", 0.0, left.toDouble())
        assertClose("top", 0.0, top.toDouble())
        assertClose("right", 320.0, right.toDouble())
        assertClose("bottom", 240.0, bottom.toDouble())
    }

    /** 竖拍图片 480x640 → 480x640 Canvas */
    @Test
    fun mapImageBoxToCanvas_portraitImage_correctMapping() {
        val r = mapImageBoxToCanvas(
            boxLeft = 0f, boxTop = 0f,
            canvasWidth = 480f, canvasHeight = 640f,
            imageWidth = 480, imageHeight = 640,
        )
        assertClose("scaleX", 1.0, r.scaleX)
        assertClose("scaleY", 1.0, r.scaleY)
        assertClose("offsetX", 0.0, r.offsetX.toDouble())
        assertClose("offsetY", 0.0, r.offsetY.toDouble())

        // 检测框(100,200)→(300,500)应直接映射
        val left = r.offsetX + (100.0 * r.scaleX).toFloat()
        val top = r.offsetY + (200.0 * r.scaleY).toFloat()
        val right = r.offsetX + (300.0 * r.scaleX).toFloat()
        val bottom = r.offsetY + (500.0 * r.scaleY).toFloat()
        assertClose("left", 100.0, left.toDouble())
        assertClose("top", 200.0, top.toDouble())
        assertClose("right", 300.0, right.toDouble())
        assertClose("bottom", 500.0, bottom.toDouble())
    }

    /** 无效输入（零尺寸）返回安全默认值 */
    @Test
    fun mapImageBoxToCanvas_zeroDimensions_returnsSafeDefaults() {
        val r = mapImageBoxToCanvas(
            boxLeft = 0f, boxTop = 0f,
            canvasWidth = 0f, canvasHeight = 0f,
            imageWidth = 640, imageHeight = 480,
        )
        assertClose("scaleX", 1.0, r.scaleX)
        assertClose("scaleY", 1.0, r.scaleY)
    }

    @Test
    fun mapImageBoxToCanvas_zeroImageSize_returnsSafeDefaults() {
        val r = mapImageBoxToCanvas(
            boxLeft = 0f, boxTop = 0f,
            canvasWidth = 320f, canvasHeight = 240f,
            imageWidth = 0, imageHeight = 0,
        )
        assertClose("scaleX", 1.0, r.scaleX)
        assertClose("scaleY", 1.0, r.scaleY)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Compose UI 渲染测试
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun overlayRendersWithDetections() {
        composeRule.setContent {
            MaterialTheme {
                FullImageDetectionOverlay(
                    photoBitmap = testBitmap,
                    photoLoadError = null,
                    detections = sampleDetections(),
                    imageWidth = 640,
                    imageHeight = 480,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun overlayWithEmptyDetectionsDoesNotCrash() {
        composeRule.setContent {
            MaterialTheme {
                FullImageDetectionOverlay(
                    photoBitmap = testBitmap,
                    photoLoadError = null,
                    detections = emptyList(),
                    imageWidth = 640,
                    imageHeight = 480,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun overlayShowsPhotoLoadError() {
        composeRule.setContent {
            MaterialTheme {
                FullImageDetectionOverlay(
                    photoBitmap = null,
                    photoLoadError = "模板图片不存在",
                    detections = sampleDetections(),
                    imageWidth = 640,
                    imageHeight = 480,
                )
            }
        }
        composeRule.onNodeWithText("照片加载失败：模板图片不存在").assertIsDisplayed()
    }

    @Test
    fun overlayShowsLoadingWhenBitmapNullAndNoError() {
        composeRule.setContent {
            MaterialTheme {
                FullImageDetectionOverlay(
                    photoBitmap = null,
                    photoLoadError = null,
                    detections = emptyList(),
                    imageWidth = 640,
                    imageHeight = 480,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun overlayWithZeroImageDimensionsDoesNotCrash() {
        composeRule.setContent {
            MaterialTheme {
                FullImageDetectionOverlay(
                    photoBitmap = testBitmap,
                    photoLoadError = null,
                    detections = sampleDetections(),
                    imageWidth = 0,
                    imageHeight = 0,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun overlayWithInferenceFailureShowsNoFakeBoxes() {
        composeRule.setContent {
            MaterialTheme {
                FullImageDetectionOverlay(
                    photoBitmap = testBitmap,
                    photoLoadError = null,
                    detections = emptyList(),
                    imageWidth = 640,
                    imageHeight = 480,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun overlayWithSingleDetectionRenders() {
        val singleDetection = listOf(
            NanoDetDetection(
                classIndex = 2, className = "螺栓", score = 0.65f, point = 1,
                roiBox = NanoDetBox(0.0, 0.0, 50.0, 50.0),
                imageBox = NanoDetBox(100.0, 100.0, 300.0, 300.0)
            )
        )
        composeRule.setContent {
            MaterialTheme {
                FullImageDetectionOverlay(
                    photoBitmap = testBitmap,
                    photoLoadError = null,
                    detections = singleDetection,
                    imageWidth = 640,
                    imageHeight = 480,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun overlayWithMultipleClassDetectionsRenders() {
        val multiClassDetections = listOf(
            NanoDetDetection(
                classIndex = 0, className = "螺母", score = 0.9f, point = 1,
                roiBox = NanoDetBox(0.0, 0.0, 50.0, 50.0),
                imageBox = NanoDetBox(10.0, 10.0, 100.0, 100.0)
            ),
            NanoDetDetection(
                classIndex = 1, className = "螺纹", score = 0.8f, point = 2,
                roiBox = NanoDetBox(50.0, 50.0, 100.0, 100.0),
                imageBox = NanoDetBox(200.0, 200.0, 300.0, 300.0)
            ),
            NanoDetDetection(
                classIndex = 2, className = "螺栓", score = 0.7f, point = 3,
                roiBox = NanoDetBox(100.0, 100.0, 150.0, 150.0),
                imageBox = NanoDetBox(400.0, 300.0, 500.0, 450.0)
            ),
            NanoDetDetection(
                classIndex = 3, className = "铆螺母", score = 0.6f, point = 4,
                roiBox = NanoDetBox(150.0, 150.0, 200.0, 200.0),
                imageBox = NanoDetBox(50.0, 350.0, 150.0, 450.0)
            )
        )
        composeRule.setContent {
            MaterialTheme {
                FullImageDetectionOverlay(
                    photoBitmap = testBitmap,
                    photoLoadError = null,
                    detections = multiClassDetections,
                    imageWidth = 640,
                    imageHeight = 480,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun overlayWithPortraitImageRendersCorrectly() {
        val portraitBitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        composeRule.setContent {
            MaterialTheme {
                FullImageDetectionOverlay(
                    photoBitmap = portraitBitmap,
                    photoLoadError = null,
                    detections = sampleDetections(),
                    imageWidth = 480,
                    imageHeight = 640,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun overlayWithEdgeDetectionBoxDoesNotCrash() {
        val edgeDetections = listOf(
            NanoDetDetection(
                classIndex = 0, className = "螺母", score = 0.95f, point = 1,
                roiBox = NanoDetBox(0.0, 0.0, 50.0, 50.0),
                imageBox = NanoDetBox(0.0, 0.0, 640.0, 480.0)
            )
        )
        composeRule.setContent {
            MaterialTheme {
                FullImageDetectionOverlay(
                    photoBitmap = testBitmap,
                    photoLoadError = null,
                    detections = edgeDetections,
                    imageWidth = 640,
                    imageHeight = 480,
                )
            }
        }
        composeRule.waitForIdle()
    }

    // ═══════════════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════════════

    private fun assertClose(label: String, expected: Double, actual: Double, tolerance: Double = 0.01) {
        assertTrue(
            "$label: expected=$expected, actual=$actual, diff=${abs(expected - actual)} > tolerance=$tolerance",
            abs(expected - actual) <= tolerance
        )
    }
}
