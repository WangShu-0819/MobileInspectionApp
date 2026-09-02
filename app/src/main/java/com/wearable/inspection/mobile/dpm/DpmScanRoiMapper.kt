package com.wearable.inspection.mobile.dpm

import android.graphics.Rect

/**
 * 扫码框 ROI 映射器 — 将屏幕坐标系的扫描框映射到 Bitmap 坐标系。
 *
 * 坐标系说明：
 * - 屏幕坐标系：PreviewView 的 (0,0)~(viewW,viewH)，含 letterbox
 * - Bitmap 坐标系：旋转后的 upright Bitmap (0,0)~(bitmapW,bitmapH)
 *
 * 映射步骤：
 * 1. 从屏幕 Rect 中减去 contentRect 的偏移（去掉 letterbox）
 * 2. 按 contentRect 到 Bitmap 的缩放比映射到 Bitmap 坐标
 * 3. clamp 到 Bitmap 边界
 * 4. 面积为零 → 返回 null（框与图像无交集）
 *
 * 不修改任何状态，纯函数。
 */
object DpmScanRoiMapper {

    /**
     * 将屏幕扫描框映射到 Bitmap 坐标系的 ROI。
     *
     * @param screenRect 屏幕坐标系的扫描框（相对于 PreviewView 左上角）
     * @param contentRect PreviewView 中图像实际显示区域（去掉 letterbox）
     * @param bitmapWidth 旋转后的 Bitmap 宽度
     * @param bitmapHeight 旋转后的 Bitmap 高度
     * @return Bitmap 坐标系的 ROI，如果框与图像无交集则返回 null
     */
    fun mapToBitmap(
        screenRect: Rect,
        contentRect: Rect,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): Rect? {
        // 检查输入有效性
        if (screenRect.isEmpty || contentRect.isEmpty) return null
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return null

        // 1. 屏幕 Rect 与 contentRect 求交集（去掉 letterbox 区域）
        val intersected = Rect()
        val hasIntersection = intersected.setIntersect(screenRect, contentRect)
        if (!hasIntersection || intersected.isEmpty) return null

        // 2. 减去 contentRect 偏移，得到图像坐标系
        val imageLeft = intersected.left - contentRect.left
        val imageTop = intersected.top - contentRect.top
        val imageRight = intersected.right - contentRect.left
        val imageBottom = intersected.bottom - contentRect.top

        // 3. 按缩放比映射到 Bitmap 坐标
        val scaleX = bitmapWidth.toFloat() / contentRect.width()
        val scaleY = bitmapHeight.toFloat() / contentRect.height()

        val bitmapLeft = (imageLeft * scaleX).toInt().coerceIn(0, bitmapWidth)
        val bitmapTop = (imageTop * scaleY).toInt().coerceIn(0, bitmapHeight)
        val bitmapRight = (imageRight * scaleX).toInt().coerceIn(0, bitmapWidth)
        val bitmapBottom = (imageBottom * scaleY).toInt().coerceIn(0, bitmapHeight)

        // 4. 检查有效性
        if (bitmapLeft >= bitmapRight || bitmapTop >= bitmapBottom) return null

        return Rect(bitmapLeft, bitmapTop, bitmapRight, bitmapBottom)
    }
}
