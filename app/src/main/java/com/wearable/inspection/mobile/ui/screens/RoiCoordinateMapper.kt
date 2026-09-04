package com.wearable.inspection.mobile.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File

/**
 * ROI 坐标映射工具
 *
 * 将模板 ROI 的 normalizedRect (0-1) 映射到拍摄照片的实际像素坐标。
 * 假设现场照片已与模板 View 对齐（不需要 Homography）。
 *
 * 纯函数，可在 JVM 单测中直接测试。
 */
object RoiCoordinateMapper {

    /**
     * 从 JSON 字符串解析归一化矩形
     *
     * @return NormalizedRect 或 null（JSON 无效/越界时）
     */
    fun parseNormalizedRect(json: String): NormalizedRect? = runCatching {
        val obj = JSONObject(json)
        val rect = NormalizedRect(
            left = obj.getDouble("left").toFloat(),
            top = obj.getDouble("top").toFloat(),
            right = obj.getDouble("right").toFloat(),
            bottom = obj.getDouble("bottom").toFloat(),
        )
        val values = listOf(rect.left, rect.top, rect.right, rect.bottom)
        if (values.any { !it.isFinite() || it !in 0f..1f }) null
        else if (rect.left >= rect.right || rect.top >= rect.bottom) null
        else rect
    }.getOrNull()

    /**
     * 将 normalizedRect 映射到照片像素坐标
     *
     * @param normalizedRect 归一化矩形 (0-1)
     * @param imageWidth 照片宽度 (px)
     * @param imageHeight 照片高度 (px)
     * @return 像素矩形 (ContentRectBounds)，超出图片范围时被 clamp
     */
    fun mapToImagePixels(
        normalizedRect: NormalizedRect,
        imageWidth: Int,
        imageHeight: Int
    ): ContentRectBounds {
        val left = (normalizedRect.left * imageWidth).toInt().coerceIn(0, imageWidth)
        val top = (normalizedRect.top * imageHeight).toInt().coerceIn(0, imageHeight)
        val right = (normalizedRect.right * imageWidth).toInt().coerceIn(0, imageWidth)
        val bottom = (normalizedRect.bottom * imageHeight).toInt().coerceIn(0, imageHeight)
        return ContentRectBounds(left, top, right, bottom)
    }

    /**
     * 从照片文件裁剪 ROI 子图
     *
     * @param photoPath 照片文件路径
     * @param pixelRect 像素矩形 (ContentRectBounds)
     * @param inSampleSize 解码采样率 (1=原图, 2=半尺寸)
     * @return 裁剪后的 Bitmap，失败返回 null
     */
    fun cropRoiBitmap(
        photoPath: String,
        pixelRect: ContentRectBounds,
        inSampleSize: Int = 1
    ): Bitmap? {
        if (pixelRect.width <= 0 || pixelRect.height <= 0) return null
        val file = File(photoPath)
        if (!file.exists()) return null

        return try {
            // 先解码尺寸
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(photoPath, opts)
            val imgW = opts.outWidth
            val imgH = opts.outHeight
            if (imgW <= 0 || imgH <= 0) return null

            // Clamp rect to image bounds
            val safeRect = ContentRectBounds(
                left = pixelRect.left.coerceIn(0, imgW),
                top = pixelRect.top.coerceIn(0, imgH),
                right = pixelRect.right.coerceIn(0, imgW),
                bottom = pixelRect.bottom.coerceIn(0, imgH)
            )
            if (safeRect.width <= 0 || safeRect.height <= 0) return null

            // 解码完整图（带采样）
            val decodeOpts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize.coerceAtLeast(1)
            }
            val fullBitmap = BitmapFactory.decodeFile(photoPath, decodeOpts) ?: return null

            // 采样后的坐标需要缩放
            val scaleX = fullBitmap.width.toFloat() / imgW
            val scaleY = fullBitmap.height.toFloat() / imgH
            val scaledRect = ContentRectBounds(
                left = (safeRect.left * scaleX).toInt().coerceIn(0, fullBitmap.width),
                top = (safeRect.top * scaleY).toInt().coerceIn(0, fullBitmap.height),
                right = (safeRect.right * scaleX).toInt().coerceIn(0, fullBitmap.width),
                bottom = (safeRect.bottom * scaleY).toInt().coerceIn(0, fullBitmap.height)
            )
            if (scaledRect.width <= 0 || scaledRect.height <= 0) {
                fullBitmap.recycle()
                return null
            }

            val cropped = Bitmap.createBitmap(
                fullBitmap,
                scaledRect.left,
                scaledRect.top,
                scaledRect.width,
                scaledRect.height
            )
            if (cropped !== fullBitmap) {
                fullBitmap.recycle()
            }
            cropped
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 获取图片尺寸（不解码像素数据）
     *
     * @return Pair(width, height) 或 null
     */
    fun getImageDimensions(photoPath: String): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(photoPath, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) {
            opts.outWidth to opts.outHeight
        } else {
            null
        }
    }
}
