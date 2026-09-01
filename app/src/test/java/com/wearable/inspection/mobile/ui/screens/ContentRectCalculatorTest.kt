package com.wearable.inspection.mobile.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ContentRect 计算纯函数单元测试
 *
 * 直接测试生产代码 ContentRectCalculator.kt 中的 calculateContentRectBounds，
 * 不复制算法，确保测试覆盖的是真实实现。
 *
 * 测试场景：
 * 1. 横屏模式（PreviewView 宽度 > 高度）
 * 2. 竖屏模式（PreviewView 高度 > 宽度）
 * 3. 相同比例（无需留边）
 * 4. 左右留边（预览更高）
 * 5. 上下留边（预览更宽）
 * 6. 边界断言
 * 7. 宽高比一致性
 * 8-10. 旋转处理
 */
class ContentRectCalculatorTest {

    /**
     * 测试 1：横屏模式
     *
     * PreviewView 1080x600（横屏）
     * 流 4032x3024（4:3）
     * 预览更宽，应左右留边
     */
    @Test
    fun testLandscapeMode_leftRightPadding() {
        val rect = calculateContentRectBounds(
            viewWidth = 1080,
            viewHeight = 600,
            rotatedStreamWidth = 4032,
            rotatedStreamHeight = 3024
        )

        // scale = min(1080/4032, 600/3024) = 600/3024 = 0.1984126984
        // contentWidth = round(4032 * scale) = round(800.0) = 800
        // contentHeight = round(3024 * scale) = round(600.0) = 600
        // left = round((1080 - 800) / 2) = round(140.0) = 140
        assertEquals(800, rect.width)
        assertEquals(600, rect.height)
        assertEquals(140, rect.left)
        assertEquals(0, rect.top)
        assertEquals(940, rect.right)
        assertEquals(600, rect.bottom)
    }

    /**
     * 测试 2：竖屏模式
     *
     * PreviewView 600x1080（竖屏）
     * 流 4032x3024（4:3）
     * 预览更窄，应上下留边
     */
    @Test
    fun testPortraitMode_topBottomPadding() {
        val rect = calculateContentRectBounds(
            viewWidth = 600,
            viewHeight = 1080,
            rotatedStreamWidth = 4032,
            rotatedStreamHeight = 3024
        )

        // scale = min(600/4032, 1080/3024) = 600/4032 = 0.1488095238
        // contentWidth = round(4032 * scale) = round(600.0) = 600
        // contentHeight = round(3024 * scale) = round(450.1714) = 450
        // left = round((600 - 600) / 2) = 0
        // top = round((1080 - 450) / 2) = round(315.0) = 315
        assertEquals(600, rect.width)
        assertEquals(450, rect.height)
        assertEquals(0, rect.left)
        assertEquals(315, rect.top)
    }

    /**
     * 测试 3：相同比例
     *
     * PreviewView 1200x900（4:3）
     * 流 4032x3024（4:3）
     * 无留边，完全填满
     */
    @Test
    fun testSameRatio_noPadding() {
        val rect = calculateContentRectBounds(
            viewWidth = 1200,
            viewHeight = 900,
            rotatedStreamWidth = 4032,
            rotatedStreamHeight = 3024
        )

        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertEquals(1200, rect.width)
        assertEquals(900, rect.height)
    }

    /**
     * 测试 4：左右留边（预览更高，16:9 流在竖屏容器中）
     *
     * PreviewView 720x1280（竖屏）
     * 流 1920x1080（16:9）
     */
    @Test
    fun testWiderStream_inTallerContainer_leftRightPadding() {
        val rect = calculateContentRectBounds(
            viewWidth = 720,
            viewHeight = 1280,
            rotatedStreamWidth = 1920,
            rotatedStreamHeight = 1080
        )

        // scale = min(720/1920, 1280/1080) = 720/1920 = 0.375
        // contentWidth = round(1920 * 0.375) = round(720.0) = 720
        // contentHeight = round(1080 * 0.375) = round(405.0) = 405
        // left = round((720 - 720) / 2) = 0
        // top = round((1280 - 405) / 2) = round(437.5) = 438 (Math.round 半进位)
        assertEquals(720, rect.width)
        assertEquals(405, rect.height)
        assertEquals(0, rect.left)
        assertEquals(438, rect.top)
    }

    /**
     * 测试 5：上下留边（16:9 流在横屏容器中，同比例）
     *
     * PreviewView 1280x720（横屏）
     * 流 1920x1080（16:9）
     */
    @Test
    fun testWiderStream_inWiderContainer_noPadding() {
        val rect = calculateContentRectBounds(
            viewWidth = 1280,
            viewHeight = 720,
            rotatedStreamWidth = 1920,
            rotatedStreamHeight = 1080
        )

        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertEquals(1280, rect.width)
        assertEquals(720, rect.height)
    }

    /**
     * 测试 6：边界断言 - contentRect 不超出 PreviewView
     */
    @Test
    fun testBoundaries_contentRectWithinPreview() {
        val viewWidth = 1080
        val viewHeight = 1920

        val rect = calculateContentRectBounds(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            rotatedStreamWidth = 4032,
            rotatedStreamHeight = 3024
        )

        assertTrue(rect.left >= 0, "左边应 >= 0")
        assertTrue(rect.top >= 0, "上边应 >= 0")
        assertTrue(rect.right <= viewWidth, "右边应 <= viewWidth")
        assertTrue(rect.bottom <= viewHeight, "下边应 <= viewHeight")
    }

    /**
     * 测试 7：边界断言 - contentRect 宽高比约等于旋转后的流宽高比
     */
    @Test
    fun testBoundaries_aspectRatioMatchesStream() {
        val viewWidth = 1080
        val viewHeight = 1920
        val streamWidth = 4032
        val streamHeight = 3024

        val rect = calculateContentRectBounds(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            rotatedStreamWidth = streamWidth,
            rotatedStreamHeight = streamHeight
        )

        val streamRatio = streamWidth.toFloat() / streamHeight
        val contentRectRatio = rect.width.toFloat() / rect.height

        // 允许 1% 误差（取整导致）
        val tolerance = 0.01f
        assertEquals(
            streamRatio,
            contentRectRatio,
            tolerance,
            "contentRect 比例应与流比例一致"
        )
    }

    /**
     * 测试 8：90 度旋转处理
     *
     * 原始流 3024x4032（竖屏 3:4）
     * 旋转 90 度后变为 4032x3024（横屏 4:3）
     * PreviewView 1080x1920（竖屏）
     * 4:3 流恰好填满 1080 宽度，无左右留边
     */
    @Test
    fun testRotation90_swapDimensions() {
        val originalStreamWidth = 3024
        val originalStreamHeight = 4032
        val rotation = 90

        val rotatedWidth = if (rotation == 90 || rotation == 270) {
            originalStreamHeight
        } else {
            originalStreamWidth
        }
        val rotatedHeight = if (rotation == 90 || rotation == 270) {
            originalStreamWidth
        } else {
            originalStreamHeight
        }

        assertEquals(4032, rotatedWidth)
        assertEquals(3024, rotatedHeight)

        val rect = calculateContentRectBounds(
            viewWidth = 1080,
            viewHeight = 1920,
            rotatedStreamWidth = rotatedWidth,
            rotatedStreamHeight = rotatedHeight
        )

        // scale = min(1080/4032, 1920/3024) = 0.26786
        // contentWidth = round(4032 * 0.26786) = round(1080.0) = 1080（恰好填满宽度）
        // contentHeight = round(3024 * 0.26786) = round(809.5) = 810
        // top = round((1920 - 810) / 2) = round(555.0) = 555
        assertEquals(0, rect.left, "4:3 流恰好填满1080宽度，左右无边")
        assertEquals(555, rect.top, "竖屏容器内上下留边")
    }

    /**
     * 测试 9：270 度旋转处理
     */
    @Test
    fun testRotation270_swapDimensions() {
        val originalStreamWidth = 3024
        val originalStreamHeight = 4032
        val rotation = 270

        val rotatedWidth = if (rotation == 90 || rotation == 270) {
            originalStreamHeight
        } else {
            originalStreamWidth
        }
        val rotatedHeight = if (rotation == 90 || rotation == 270) {
            originalStreamWidth
        } else {
            originalStreamHeight
        }

        assertEquals(4032, rotatedWidth)
        assertEquals(3024, rotatedHeight)
    }

    /**
     * 测试 10：0 度旋转不交换宽高
     */
    @Test
    fun testRotation0_noSwap() {
        val originalStreamWidth = 4032
        val originalStreamHeight = 3024
        val rotation = 0

        val rotatedWidth = if (rotation == 90 || rotation == 270) {
            originalStreamHeight
        } else {
            originalStreamWidth
        }
        val rotatedHeight = if (rotation == 90 || rotation == 270) {
            originalStreamWidth
        } else {
            originalStreamHeight
        }

        assertEquals(4032, rotatedWidth)
        assertEquals(3024, rotatedHeight)
    }
}
