package com.wearable.inspection.mobile.ui.screens

/**
 * FIT_CENTER 内容区域计算结果
 *
 * 不依赖 Android SDK，可在 JVM 单元测试中直接使用。
 */
data class ContentRectBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * 统一 FIT_CENTER contentRect 计算
 *
 * 在给定 PreviewView 尺寸和旋转后流尺寸的条件下，
 * 计算图像实际显示区域（去掉 letterbox 后的内容区域）。
 *
 * 公式：
 * scale = min(viewWidth / rotatedStreamWidth, viewHeight / rotatedStreamHeight)
 * contentWidth = round(rotatedStreamWidth * scale)
 * contentHeight = round(rotatedStreamHeight * scale)
 * left = round((viewWidth - contentWidth) / 2)
 * top = round((viewHeight - contentHeight) / 2)
 *
 * @param viewWidth PreviewView 宽度（px）
 * @param viewHeight PreviewView 高度（px）
 * @param rotatedStreamWidth 旋转后的流宽度
 * @param rotatedStreamHeight 旋转后的流高度
 * @return 图像内容区域边界
 * @throws IllegalArgumentException 参数 <= 0
 */
internal fun calculateContentRectBounds(
    viewWidth: Int,
    viewHeight: Int,
    rotatedStreamWidth: Int,
    rotatedStreamHeight: Int
): ContentRectBounds {
    require(viewWidth > 0 && viewHeight > 0) {
        "PreviewView 尺寸必须大于 0，实际: ${viewWidth}x${viewHeight}"
    }
    require(rotatedStreamWidth > 0 && rotatedStreamHeight > 0) {
        "流尺寸必须大于 0，实际: ${rotatedStreamWidth}x${rotatedStreamHeight}"
    }

    val scale = minOf(
        viewWidth.toFloat() / rotatedStreamWidth,
        viewHeight.toFloat() / rotatedStreamHeight
    )

    val contentWidth = Math.round(rotatedStreamWidth * scale)
    val contentHeight = Math.round(rotatedStreamHeight * scale)
    val left = Math.round((viewWidth - contentWidth) / 2f).coerceAtLeast(0)
    val top = Math.round((viewHeight - contentHeight) / 2f).coerceAtLeast(0)
    val right = (left + contentWidth).coerceAtMost(viewWidth)
    val bottom = (top + contentHeight).coerceAtMost(viewHeight)

    return ContentRectBounds(left, top, right, bottom)
}
