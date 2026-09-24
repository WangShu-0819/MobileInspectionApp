package com.wearable.inspection.mobile.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.wearable.inspection.mobile.registration.ProjectedPoint
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

    /**
     * 加载图片并应用 EXIF 旋转，返回 upright 方向的 Bitmap。
     *
     * 解码后的 Bitmap 像素方向与 NanoDet imageBox 坐标空间一致：
     * - Orientation=NORMAL(1)：Bitmap 尺寸 = 原始宽高
     * - Orientation=ROTATE_90(6)/ROTATE_270(8)：Bitmap 宽高互换
     *
     * @param photoPath 图片文件路径
     * @param maxTargetSize 降采样目标最大边长；<=0 回退 2048
     * @return upright Bitmap，失败返回 null
     */
    fun loadUprightBitmap(photoPath: String, maxTargetSize: Int = 2048): Bitmap? {
        val file = File(photoPath)
        if (!file.exists() || file.length() == 0L) return null

        val geometry = getImageGeometry(photoPath) ?: return null
        val safeMaxTarget = if (maxTargetSize <= 0) 2048 else maxTargetSize
        val inSampleSize = calculateSampleSize(geometry.rawWidth, geometry.rawHeight, safeMaxTarget)

        val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        val bitmap = BitmapFactory.decodeFile(photoPath, opts) ?: return null
        return orientBitmap(bitmap, geometry.exifOrientation)
    }

    private fun calculateSampleSize(width: Int, height: Int, maxTarget: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        val maxDim = maxOf(width, height)
        while (maxDim / sample > maxTarget) {
            sample *= 2
        }
        return sample
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

    /**
     * Boundary tolerance for projected corner containment check (in normalized [0,1] units).
     * Allows floating-point noise from Homography arithmetic (~1e-5 relative) without
     * falsely rejecting corners that are effectively on the scene boundary.
     */
    private const val PROJECTION_BOUNDARY_TOLERANCE = 1e-4f

    /**
     * Compute the projected normalized rect from four projected scene-space corners.
     *
     * Pure function: validates containment → AABB → safety margin → min-size check.
     * Returns null when any corner falls outside the scene boundary (triggers fallback).
     *
     * @param projectedCorners Four projected corner points in scene pixel space.
     * @param sceneWidth Scene image width in pixels.
     * @param sceneHeight Scene image height in pixels.
     * @param marginRatio Safety margin ratio to expand the AABB (0 = no expansion).
     * @param minSize Minimum normalized width/height to accept (reject smaller).
     * @return The projected NormalizedRect, or null if corners are outside scene or ROI too small.
     */
    fun computeProjectedRect(
        projectedCorners: List<ProjectedPoint>,
        sceneWidth: Int,
        sceneHeight: Int,
        marginRatio: Float,
        minSize: Float,
    ): NormalizedRect? {
        // All four projected corners must be within scene boundary (with floating-point tolerance).
        // If any corner is outside, the target is not fully captured → fail to trigger fallback.
        if (projectedCorners.any { corner ->
                corner.x < -PROJECTION_BOUNDARY_TOLERANCE * sceneWidth ||
                    corner.y < -PROJECTION_BOUNDARY_TOLERANCE * sceneHeight ||
                    corner.x > sceneWidth * (1.0 + PROJECTION_BOUNDARY_TOLERANCE) ||
                    corner.y > sceneHeight * (1.0 + PROJECTION_BOUNDARY_TOLERANCE)
            }
        ) return null

        val left = projectedCorners.minOf { it.x } / sceneWidth
        val top = projectedCorners.minOf { it.y } / sceneHeight
        val right = projectedCorners.maxOf { it.x } / sceneWidth
        val bottom = projectedCorners.maxOf { it.y } / sceneHeight

        // Clamp to [0,1] (absorbs boundary tolerance noise)
        val tight = NormalizedRect(
            left.coerceIn(0.0, 1.0).toFloat(),
            top.coerceIn(0.0, 1.0).toFloat(),
            right.coerceIn(0.0, 1.0).toFloat(),
            bottom.coerceIn(0.0, 1.0).toFloat(),
        )

        // Expand by safety margin so threads/small parts near edges are not clipped
        val expanded = expandNormalizedRect(tight, marginRatio)

        return expanded.takeIf {
            it.right - it.left >= minSize && it.bottom - it.top >= minSize
        }
    }

    /**
     * Expand a normalized rect by a safety margin ratio on each side, clamped to [0,1].
     *
     * The margin is proportional to the rect's own width/height, so small ROIs get
     * proportionally the same padding as large ones.  The expansion is symmetric:
     * each edge moves outward by `marginRatio * dimension / 2`.
     *
     * @param rect The source normalized rect (0–1).
     * @param marginRatio Fraction of width/height to add as total padding (e.g. 0.10 = 10%).
     * @return A new rect expanded on all four sides, clamped to [0,1].
     */
    fun expandNormalizedRect(rect: NormalizedRect, marginRatio: Float): NormalizedRect {
        require(marginRatio >= 0f) { "marginRatio must be non-negative" }
        val dw = (rect.right - rect.left) * marginRatio / 2f
        val dh = (rect.bottom - rect.top) * marginRatio / 2f
        return NormalizedRect(
            left = (rect.left - dw).coerceIn(0f, 1f),
            top = (rect.top - dh).coerceIn(0f, 1f),
            right = (rect.right + dw).coerceIn(0f, 1f),
            bottom = (rect.bottom + dh).coerceIn(0f, 1f),
        )
    }
}
