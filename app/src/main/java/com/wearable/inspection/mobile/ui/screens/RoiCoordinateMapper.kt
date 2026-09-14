package com.wearable.inspection.mobile.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

/**
 * ROI 坐标映射工具
 *
 * 将模板 ROI 的 normalizedRect (0-1) 映射到拍摄照片的实际像素坐标。
 * 假设现场照片已与模板 View 对齐（不需要 Homography）。
 *
 * 纯函数，可在 JVM 单测中直接测试。
 */
object RoiCoordinateMapper {

    data class PhotoGeometry(
        val rawWidth: Int,
        val rawHeight: Int,
        val exifOrientation: Int
    ) {
        val width: Int get() = if (exifOrientation in ORIENTATIONS_SWAP_WIDTH_HEIGHT) rawHeight else rawWidth
        val height: Int get() = if (exifOrientation in ORIENTATIONS_SWAP_WIDTH_HEIGHT) rawWidth else rawHeight
    }

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
        require(imageWidth > 0 && imageHeight > 0) { "Image dimensions must be positive" }
        require(listOf(normalizedRect.left, normalizedRect.top, normalizedRect.right, normalizedRect.bottom)
            .all { it.isFinite() && it in 0f..1f }) { "Normalized ROI must be within [0,1]" }
        require(normalizedRect.left < normalizedRect.right && normalizedRect.top < normalizedRect.bottom) {
            "Normalized ROI must have positive area"
        }
        // Rectangles use half-open pixel bounds: floor the leading edge and ceil the trailing edge
        // so a valid sub-pixel ROI never silently loses its last covered pixel.
        val left = floor(normalizedRect.left * imageWidth + PIXEL_ROUNDING_TOLERANCE).toInt().coerceIn(0, imageWidth)
        val top = floor(normalizedRect.top * imageHeight + PIXEL_ROUNDING_TOLERANCE).toInt().coerceIn(0, imageHeight)
        val right = ceil(normalizedRect.right * imageWidth - PIXEL_ROUNDING_TOLERANCE).toInt().coerceIn(0, imageWidth)
        val bottom = ceil(normalizedRect.bottom * imageHeight - PIXEL_ROUNDING_TOLERANCE).toInt().coerceIn(0, imageHeight)
        return ContentRectBounds(left, top, right, bottom)
    }

    /** ROI coordinates were authored on the raw template bitmap; convert them to upright-photo space. */
    fun mapTemplateRoiToPhotoPixels(
        normalizedRect: NormalizedRect,
        templateExifOrientation: Int,
        photoGeometry: PhotoGeometry
    ): ContentRectBounds {
        val uprightTemplateRect = transformNormalizedRect(normalizedRect, templateExifOrientation)
        return mapToImagePixels(uprightTemplateRect, photoGeometry.width, photoGeometry.height)
    }

    /** Apply EXIF orientation to a normalized raw-image rectangle without changing its coverage. */
    fun transformNormalizedRect(rect: NormalizedRect, orientation: Int): NormalizedRect {
        val (left, top, right, bottom) = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> listOf(1f - rect.right, rect.top, 1f - rect.left, rect.bottom)
            ExifInterface.ORIENTATION_ROTATE_180 -> listOf(1f - rect.right, 1f - rect.bottom, 1f - rect.left, 1f - rect.top)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> listOf(rect.left, 1f - rect.bottom, rect.right, 1f - rect.top)
            ExifInterface.ORIENTATION_TRANSPOSE -> listOf(rect.top, rect.left, rect.bottom, rect.right)
            ExifInterface.ORIENTATION_ROTATE_90 -> listOf(1f - rect.bottom, rect.left, 1f - rect.top, rect.right)
            ExifInterface.ORIENTATION_TRANSVERSE -> listOf(1f - rect.bottom, 1f - rect.right, 1f - rect.top, 1f - rect.left)
            ExifInterface.ORIENTATION_ROTATE_270 -> listOf(rect.top, 1f - rect.right, rect.bottom, 1f - rect.left)
            else -> listOf(rect.left, rect.top, rect.right, rect.bottom)
        }
        return NormalizedRect(left, top, right, bottom)
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

        var ownedBitmap: Bitmap? = null
        return try {
            // 先解码尺寸
            val geometry = getImageGeometry(photoPath) ?: return null

            // Clamp rect to image bounds
            val safeRect = ContentRectBounds(
                left = pixelRect.left.coerceIn(0, geometry.width),
                top = pixelRect.top.coerceIn(0, geometry.height),
                right = pixelRect.right.coerceIn(0, geometry.width),
                bottom = pixelRect.bottom.coerceIn(0, geometry.height)
            )
            if (safeRect.width <= 0 || safeRect.height <= 0) return null

            // 解码完整图（带采样）
            val decodeOpts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize.coerceAtLeast(1)
            }
            val rawBitmap = BitmapFactory.decodeFile(photoPath, decodeOpts) ?: return null
            ownedBitmap = rawBitmap
            val fullBitmap = orientBitmap(rawBitmap, geometry.exifOrientation)
            if (fullBitmap !== rawBitmap) {
                rawBitmap.recycle()
                ownedBitmap = fullBitmap
            }

            // 采样后的坐标需要缩放
            val scaleX = fullBitmap.width.toFloat() / geometry.width
            val scaleY = fullBitmap.height.toFloat() / geometry.height
            val scaledRect = ContentRectBounds(
                left = floor(safeRect.left * scaleX).toInt().coerceIn(0, fullBitmap.width),
                top = floor(safeRect.top * scaleY).toInt().coerceIn(0, fullBitmap.height),
                right = ceil(safeRect.right * scaleX).toInt().coerceIn(0, fullBitmap.width),
                bottom = ceil(safeRect.bottom * scaleY).toInt().coerceIn(0, fullBitmap.height)
            )
            if (scaledRect.width <= 0 || scaledRect.height <= 0) return null

            val cropped = Bitmap.createBitmap(
                fullBitmap,
                scaledRect.left,
                scaledRect.top,
                scaledRect.width,
                scaledRect.height
            )
            if (cropped === fullBitmap) ownedBitmap = null
            else {
                fullBitmap.recycle()
                ownedBitmap = null
            }
            cropped
        } catch (_: Exception) {
            null
        } finally {
            ownedBitmap?.recycle()
        }
    }

    /**
     * 获取图片尺寸（不解码像素数据）
     *
     * @return Pair(width, height) 或 null
     */
    fun getImageDimensions(photoPath: String): Pair<Int, Int>? {
        val geometry = getImageGeometry(photoPath) ?: return null
        return geometry.width to geometry.height
    }

    /** Read the saved raster dimensions and EXIF orientation; display dimensions are upright. */
    fun getImageGeometry(photoPath: String): PhotoGeometry? {
        val file = File(photoPath)
        if (!file.exists()) return null
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(photoPath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            .takeIf { it in ExifInterface.ORIENTATION_NORMAL..ExifInterface.ORIENTATION_ROTATE_270 }
            ?: ExifInterface.ORIENTATION_NORMAL
        return PhotoGeometry(opts.outWidth, opts.outHeight, orientation)
    }

    private fun orientBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        if (orientation == ExifInterface.ORIENTATION_NORMAL) return bitmap
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                    setRotate(180f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    setRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    setRotate(-90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private val ORIENTATIONS_SWAP_WIDTH_HEIGHT = setOf(
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSVERSE,
        ExifInterface.ORIENTATION_ROTATE_270
    )

    private const val PIXEL_ROUNDING_TOLERANCE = 0.01
}
