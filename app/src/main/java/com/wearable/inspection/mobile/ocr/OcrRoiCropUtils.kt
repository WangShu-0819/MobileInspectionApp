package com.wearable.inspection.mobile.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.util.Log
import java.io.File

/**
 * 钢印 OCR 视图 → 原图坐标映射与 ROI 解码（分层验证「定位」层）。
 *
 * **为什么必须原图 ROI 优先**：整图先降采样到 1600px 再裁剪，字符笔画在降采样中
 * 被插值抹平；正确流程是先在**原始 JPEG** 上按 UI 引导框裁出高分辨率 ROI，再送
 * 预处理与识别（字符高度控制在 30~60px），大图只做 BitmapRegionDecoder 区域解码，
 * 不产生 12MP 整图 Bitmap。
 *
 * 坐标系链：`UI 框(View px) → 显示 JPEG(Display px) → 存储 JPEG(Stored px)`。
 * 预览层为 CameraX PreviewView `FIT_CENTER`（等比缩放居中、余量 letterbox），
 * 存储 JPEG 的**显示方向** = EXIF 旋转后的方向；映射后经逆旋转落在存储像素坐标，
 * BitmapRegionDecoder 按存储坐标区域解码，再按 EXIF 旋转到显示方向送引擎。
 */
object OcrRoiCropUtils {

    private const val TAG = "STEEL_OCR"

    /**
     * EXIF Orientation 值 → 顺时针旋转角度（EXIF 6=90°CW、3=180°、8=270°CW，
     * 其余 1/2/4/5/7 为镜像翻转：OCR 场景按不旋转处理并告警）。
     */
    fun rotationDegrees(exifOrientation: Int): Int = when (exifOrientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    /** 存储尺寸 → 显示尺寸（EXIF 旋转后的宽高） */
    fun displaySizeOf(jpegW: Int, jpegH: Int, exifOrientation: Int): Pair<Int, Int> =
        if (rotationDegrees(exifOrientation) % 180 == 90) jpegH to jpegW else jpegW to jpegH

    /**
     * 居中引导框（纯函数）：宽度占 [viewW] 的 [widthFraction]，高宽比 [aspect]（h/w），
     * 垂直居中（不按扫描习惯上移——钢印通常位于零件中部）。
     */
    fun centeredScanBox(viewW: Int, viewH: Int, widthFraction: Float, aspect: Float): RoiBox {
        val boxW = viewW * widthFraction
        val boxH = boxW * aspect
        val left = (viewW - boxW) / 2f
        val top = (viewH - boxH) / 2f
        return RoiBox(left, top, left + boxW, top + boxH)
    }

    /**
     * View 坐标 UI 框 → 存储 JPEG 像素坐标（FIT_CENTER 逆映射 + EXIF 逆旋转 + 外扩）。
     *
     * @param view  UI 框在预览视图上的像素坐标
     * @param viewW/viewH 预览视图尺寸（px）
     * @param jpegW/jpegH 存储 JPEG 原始尺寸（BitmapFactory bounds，非显示尺寸）
     * @param exifOrientation JPEG 的 EXIF 方向值
     * @param marginFraction 外扩比例（防框边字符被截断，建议 0.05~0.10）
     * @return 存储像素坐标系中的 ROI（已 clamp 到图内、已外扩）
     */
    fun mapViewRectToJpeg(
        view: RoiBox,
        viewW: Int,
        viewH: Int,
        jpegW: Int,
        jpegH: Int,
        exifOrientation: Int,
        marginFraction: Float,
    ): RoiBox {
        if (view.isEmpty() || viewW <= 0 || viewH <= 0 || jpegW <= 0 || jpegH <= 0) return view
        val (dispW, dispH) = displaySizeOf(jpegW, jpegH, exifOrientation)
        // FIT_CENTER：等比缩放（min 轴）后居中，letterbox 偏移（注意 Int/Int 会截断为 0）
        val scale = minOf(viewW.toFloat() / dispW, viewH.toFloat() / dispH)
        val offX = (viewW - dispW * scale) / 2f
        val offY = (viewH - dispH * scale) / 2f
        // View → 显示 JPEG
        val dLeft = (view.left - offX) / scale
        val dTop = (view.top - offY) / scale
        val dRight = (view.right - offX) / scale
        val dBottom = (view.bottom - offY) / scale
        // 显示 JPEG → 存储 JPEG（逆旋转；右上/左下角各映射一次，兼容全部旋转方向）
        val tl = toStored(dLeft, dTop, jpegW, jpegH, exifOrientation)
        val br = toStored(dRight - 1f, dBottom - 1f, jpegW, jpegH, exifOrientation)
        val rect = RoiBox(
            minOf(tl.first, br.first),
            minOf(tl.second, br.second),
            maxOf(tl.first, br.first) + 1f,
            maxOf(tl.second, br.second) + 1f,
        )
        return rect.expandCentered(marginFraction, jpegW, jpegH)
    }

    /**
     * 显示像素点 → 存储像素点（EXIF 顺时针旋转的逆映射；W/H 为存储尺寸）。
     * 公式：CW90 显示 = 存储逆时针交换（x↔y），逐角展开避免反三角函数误差。
     */
    private fun toStored(px: Float, py: Float, jpegW: Int, jpegH: Int, exifOrientation: Int): Pair<Float, Float> =
        when (rotationDegrees(exifOrientation)) {
            90 -> Pair(py, jpegH - 1f - px)
            180 -> Pair(jpegW - 1f - px, jpegH - 1f - py)
            270 -> Pair(jpegW - 1f - py, px)
            else -> Pair(px, py)
        }

    /** 读取 JPEG EXIF 方向（缺失/不可读返回 [ExifInterface.ORIENTATION_NORMAL]） */
    fun readExifOrientation(file: File): Int = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /**
     * 原图 ROI 解码（BitmapRegionDecoder 区域解码，不产生整图内存）：
     * 按存储坐标 [roi] 以采样率解码 → 按 EXIF 旋转到显示方向。
     *
     * 目标：解码后 ROI 长边 ≈ [targetLongEdge]。真机样本表明 340px 长边会把细钢印
     * 笔画降采样成 1~2px；1000px 又会明显放大塑料表面纹理。680px 让该样本进入
     * sample=4，在笔画保真、纹理干扰和耗时之间更稳定。
     *
     * 内存约定：返回 Bitmap 所有权归调用方，识别完成后必须 recycle。
     */
    fun decodeRoiRegion(
        file: File,
        roi: RoiBox,
        @Suppress("UNUSED_PARAMETER") targetLongEdge: Int = TARGET_ROI_EDGE,
    ): Bitmap? {
        val orientation = readExifOrientation(file)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val jpegW = bounds.outWidth
        val jpegH = bounds.outHeight
        if (jpegW <= 0 || jpegH <= 0) return null
        return runCatching {
            val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false)
            try {
                val region = roi.toAndroidRect(jpegW, jpegH)
                val regionW = region.width()
                val regionH = region.height()
                if (regionW <= 0 || regionH <= 0) return null
                // 采样按「字符高」约束（规格 30~60px）而非长边：定位框/引导框高而宽时，
                // 按长边采样会解码出 ~92px 超大字符（ML Kit 检测识别效果差）——
                // 真机回放：合并后 ROI 2364x751 按长边 sample=2 → charH 92px 识别乱码。
                val sample = sampleSizeForCharHeight(regionH, MAX_LINES, TARGET_CHAR_HEIGHT)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val cropped = decoder.decodeRegion(region, opts) ?: return null
                // 区域解码 → 显示方向（0° 时直接返回，省一次拷贝）
                val degrees = rotationDegrees(orientation)
                val rotated = if (degrees == 0) cropped else {
                    val m = Matrix().apply { postRotate(degrees.toFloat()) }
                    Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, m, true)
                        .also { cropped.recycle() }
                }
                Log.i(
                    TAG,
                    "[OCR] Stage=RoiDecode region=${regionW}x${regionH}@sample=$sample " +
                        "roi=${rotated.width}x${rotated.height} exif=${orientation}°=$degrees " +
                        "charH~${estimateCharHeight(rotated.height)}px " +
                        "bounds=${jpegW}x${jpegH}",
                )
                rotated
            } finally {
                decoder.recycle()
            }
        }.getOrElse { e ->
            Log.w(TAG, "[OCR] Stage=RoiDecode failed: ${e.message}")
            null
        }
    }

    /**
     * 采样率：长边降至 ≤ [targetLongEdge] 的 2 的幂（只降不升；区域本身较小时
     * sample=1 保持原分辨率——ROI 已是高分辨率裁剪，不引入插值损耗）。
     */
    fun sampleSizeFor(regionW: Int, regionH: Int, targetLongEdge: Int): Int {
        var sample = 1
        val longest = maxOf(regionW, regionH)
        while (longest / (sample * 2) >= targetLongEdge) sample *= 2
        return sample
    }

    /**
     * 采样率（字符高约束，ROI 解码用）：解码后 ROI 高度 ≤ [targetCharHeight] × [maxLines]
     * × 1.35（即 [estimateCharHeight] 估算的字符高 ≤ [targetCharHeight]，
     * 落在钢印识别规格 30~60px 内）。
     */
    fun sampleSizeForCharHeight(roiHeight: Int, maxLines: Int, targetCharHeight: Int): Int {
        if (roiHeight <= 0 || maxLines <= 0 || targetCharHeight <= 0) return 1
        // 目标解码高 = 行数 × 目标字符高 × 行高系数 1.35 × 容差 1.10
        val targetDecodedH = (targetCharHeight * maxLines * LINE_HEIGHT_FACTOR * SAMPLING_TOLERANCE).toInt()
        var sample = 1
        while (roiHeight / sample > targetDecodedH) sample *= 2
        return sample
    }

    /** 估算解码后 ROI 的字符高度（钢印区字符高 ≈ ROI 高 / (行数上限 3 × 行高系数)） */
    fun estimateCharHeight(roiHeight: Int): Int = (roiHeight / (MAX_LINES * LINE_HEIGHT_FACTOR)).toInt()

    /** 目标 ROI 长边（历史兼容：按字符高约束采样后不再直接使用） */
    const val TARGET_ROI_EDGE = 680

    /** 目标字符高上限（钢印识别规格 30~60px，取上限保证笔画充足） */
    const val TARGET_CHAR_HEIGHT = 60

    /** 行高系数：钢印行高 ≈ 字符高 × 1.35（字符高占行高约 74%） */
    const val LINE_HEIGHT_FACTOR = 1.35f

    /** 采样容差：解码高 ≤ 目标 × 该系数即停留在当前 2 的幂档 */
    const val SAMPLING_TOLERANCE = 1.10f

    /** 钢印行数上限（引导框容纳 1~3 行；行数由算法检测，不强制补齐） */
    const val MAX_LINES = 3

    /** 引导框 UI 规范：宽占比 / 高宽比（1:2.5~1:3） */
    const val SCAN_BOX_WIDTH_FRACTION = 0.82f
    const val SCAN_BOX_ASPECT = 1f / 2.7f

    /** 框边外扩比例（防边缘字符被截断） */
    const val SCAN_BOX_MARGIN = 0.08f
}
