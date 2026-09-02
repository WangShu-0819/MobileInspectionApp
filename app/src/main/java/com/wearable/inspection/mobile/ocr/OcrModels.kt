package com.wearable.inspection.mobile.ocr

import android.graphics.Rect

/**
 * 文本行几何框（归一化 0..1；ROI 内坐标，ML Kit boundingBox 归一化而来）。
 * 纯 Kotlin 数据类，JVM 单测直接可用。
 */
data class OcrLineBox(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** 与 [other] 的 x 轴重叠比例（相对本行宽度；负 = 完全错开） */
    fun xOverlapWith(other: OcrLineBox): Float {
        val overlap = minOf(right, other.right) - maxOf(left, other.left)
        return if (width <= 0f) 0f else overlap / width
    }

    /** 与 [other] 的 y-center 间距（绝对 px 归一化） */
    fun yGapTo(other: OcrLineBox): Float = kotlin.math.abs(centerY - other.centerY)
}

/**
 * 钢印物理极性：描述**输入图像上钢印的物理形态**（凹字阴影 vs 凸字反光），
 * 与预处理路径一一对应（[OcrPreProcessor.buildCandidates] 按极性各生成一组候选）。
 *
 * - [POSITIVE]：凹字暗阴影 —— 金属基面反光偏亮，字痕凹槽形成暗阴影（亮底暗字）。
 * - [INVERTED]：凸字反光亮点 —— 凿点/凸起在金属基面上形成反光亮点（暗底亮字），
 *   需灰度反转后再增强，把亮点归一化成暗字。
 */
enum class OcrPolarity { POSITIVE, INVERTED }

/**
 * 单条预处理候选输出。名称稳定且可用于日志（如 pos-clahe / inv-adaptive）；
 * [bitmap] 为**亮底暗字归一化**候选（暗字亮底，OCR 引擎最友好的极性，规避引擎
 * 对反色文本的弱支持）；[contentBox] 为候选内容包围盒（在原图坐标系，剔除过滤
 * 后内容像素的最小外接矩形；空内容 = null，由过滤层丢弃）。bitmap 由
 * [OcrPreProcessor] 唯一构造，调用方识别完成后负责 recycle。
 */
data class OcrCandidate(
    val name: String,
    val polarity: OcrPolarity,
    val bitmap: android.graphics.Bitmap,
    val contentBox: Rect?,
)

/**
 * 纯坐标矩形（无 android 依赖，JVM 单测直接可用）。
 * 语义：left/top/right/bottom 同 android.graphics.Rect（right/bottom 为开区间外边界）。
 */
data class RoiBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun isEmpty(): Boolean = width <= 0f || height <= 0f

    /** 中心对齐外扩 [fraction]（相对当前宽高），clamp 到 [w]x[h] 图像内；非法输入返回原框 */
    fun expandCentered(fraction: Float, w: Int, h: Int): RoiBox {
        if (isEmpty() || fraction < 0f) return this
        val padX = width * fraction
        val padY = height * fraction
        val l = (left - padX).coerceIn(0f, w.toFloat() - 1f)
        val t = (top - padY).coerceIn(0f, h.toFloat() - 1f)
        val r = (right + padX).coerceIn(l + 1f, w.toFloat())
        val b = (bottom + padY).coerceIn(t + 1f, h.toFloat())
        return RoiBox(l, t, r, b)
    }

    /** 整图裁剪（clamp 到图内），返回 [Rect]（android 侧用） */
    fun toAndroidRect(w: Int, h: Int): Rect = Rect(
        left.toInt().coerceIn(0, w - 1),
        top.toInt().coerceIn(0, h - 1),
        right.toInt().coerceIn(1, w),
        bottom.toInt().coerceIn(1, h),
    )
}
