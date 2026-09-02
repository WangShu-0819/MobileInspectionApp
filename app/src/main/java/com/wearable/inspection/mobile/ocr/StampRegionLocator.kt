package com.wearable.inspection.mobile.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 钢印文本区自动定位（定位层兜底，替代「居中假设」）。
 *
 * 场景：无 UI 引导框时（基准回放 / Leion 源），ROI 若按画面中心裁剪，钢印实际
 * 位置偏离中心会直接截断文本行 —— 真机回放 0/6 的根因。本定位器对**降采样整图**
 * 跑一次 ML Kit，把检出的文本行按几何聚类成「钢印块」包围盒，映射回原图
 * 全分辨率坐标，供 [OcrRoiCropUtils.decodeRoiRegion] 区域解码。
 *
 * 原则：
 * - **定位 ≠ 识别**：这里只看「哪里有密集文本行」，不做字符级决策；识别仍在
 *   全分辨率 ROI 上由 [SteelStampOcrAnalyzer] 完成（不违反「禁止整图缩到 1600px
 *   再识别」——定位用的降采样图只出包围盒，OCR 输入始终是原图高清 ROI）；
 * - **失败可回退**：找不到可靠文本块返回 null，调用方回退原引导框/居中框；
 * - 文本行过滤只做弱约束（≥2 个字母数字字符），不按内容猜钢印 —— 无证据不做决定。
 */
class StampRegionLocator(
    // 可为 null：JVM 单测只测 clusterToRegion 纯几何，不触发 ML Kit 初始化
    //（TextRecognition.getClient 在无 Android Context 时抛 MlKitContext 未初始化）
    private val recognizer: TextRecognizer? = null,
) {

    /**
     * 在降采样位图上定位钢印文本块。
     *
     * @param downsampled 整图降采样位图（BitmapFactory 解码，无 EXIF 旋转 —— 存储方向）
     * @param origW/origH 原图（存储方向）像素尺寸 —— 聚类结果为归一化 (0..1) 坐标，
     *    乘**原图尺寸**（不是降采样比例）才得到原图坐标
     * @param timeoutMs 单次 ML Kit 调用限时（定位失败返回 null，不阻塞管线）
     * @return 原图（存储方向）像素坐标的钢印块包围盒（已外扩）；找不到返回 null
     */
    fun locate(
        downsampled: Bitmap,
        origW: Int,
        origH: Int,
        timeoutMs: Long = LOCATE_TIMEOUT_MS,
    ): RoiBox? {
        if (downsampled.width <= 0 || downsampled.height <= 0 || origW <= 0 || origH <= 0) return null
        val w = downsampled.width
        val h = downsampled.height
        val t0 = System.nanoTime()
        // 延迟创建识别器（仅真机 locate() 时初始化；JVM 单测不触达此处）
        val rec = recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        Log.i(TAG, "[OCR] Stage=Locate input=${downsampled.width}x${downsampled.height} orig=${origW}x${origH}")
        val text: Text? = try {
            Tasks.await(
                rec.process(InputImage.fromBitmap(downsampled, 0)),
                timeoutMs, TimeUnit.MILLISECONDS,
            )
        } catch (t: Throwable) {
            Log.i(TAG, "[OCR] Stage=Locate FAIL ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (text == null) {
            Log.i(TAG, "[OCR] Stage=Locate EMPTY/超时（回退原框）")
            return null
        }
        Log.i(TAG, "[OCR] Stage=Locate blocks=${text.textBlocks.size}")
        // 文本行 → 弱过滤（≥2 字母数字字符，高度 ≥ 2.5% 图高防碎片）
        val lines = mutableListOf<OcrLineBox>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val raw = line.text?.trim().orEmpty()
                if (raw.length < 2) continue
                val box = line.boundingBox ?: continue
                if (box.height() < h * MIN_LINE_HEIGHT_FRACTION) continue
                val alnum = raw.count { it.isLetterOrDigit() }
                if (alnum < 2) continue
                lines += OcrLineBox(
                    text = raw,
                    left = box.left.toFloat() / w,
                    top = box.top.toFloat() / h,
                    right = box.right.toFloat() / w,
                    bottom = box.bottom.toFloat() / h,
                )
            }
        }
        if (lines.isEmpty()) {
            Log.i(TAG, "[OCR] Stage=Locate 无可用文本行（回退原框） blocks=${text.textBlocks.size} lines=${text.textBlocks.sumOf { it.lines.size }}")
            return null
        }
        // 诊断：逐行几何（归一化 0..1，定位图坐标）
        lines.forEachIndexed { i, l ->
            Log.i(
                TAG,
                "[OCR] Stage=Locate line[$i] text=${l.text} " +
                    "l=${"%.2f".format(l.left)} t=${"%.2f".format(l.top)} r=${"%.2f".format(l.right)} b=${"%.2f".format(l.bottom)} " +
                    "h=${"%.2f".format(l.height)}",
            )
        }
        val region = clusterToRegion(lines, w, h)
        if (region == null) {
            Log.i(TAG, "[OCR] Stage=Locate 无可靠文本块（回退原框）")
            return null
        }
        // 映射回原图坐标（存储方向）：归一化 × 原图尺寸，外扩 + clamp
        val scaled = RoiBox(
            region.left * origW,
            region.top * origH,
            region.right * origW,
            region.bottom * origH,
        ).expandCentered(LOCATE_MARGIN_FRACTION, origW, origH)
        val ms = (System.nanoTime() - t0) / 1_000_000L
        Log.i(
            TAG,
            "[OCR] Stage=Locate lines=${lines.size} cluster=${region.width.toInt()}x${region.height.toInt()} " +
                "-> roi=${scaled.width.toInt()}x${scaled.height.toInt()} t=${ms}ms",
        )
        return scaled
    }

    /**
     * 文本行 → 钢印块包围盒（纯逻辑，JVM 单测覆盖）。
     *
     * 聚类准则（对齐规格「Y-center/baseline/X-overlap/line-spacing」）：
     * - **链内**：按 y-center 自顶向下贪心成链：下一行与链参考行 y 间距 <= 较大行高
     *   x1.5（钢印行距通常 1.2~1.5 倍行高）且 x 重叠 >= 30% → 同一链；
     * - **链间联合（跨 TextBlock）**：相邻链若垂直空隙 <= 较大行高 x4 且
     *   x 重叠（相对较宽链）>= 阈值，判为同一钢印整体，Bounding Box 强制 Union。
     * - **置信判据**：块内行数 >=2 或 块内字符总数 >=8 才接受；
     * - **选块**：多块并存取「总字符数最多，并列取面积最大」。
     */
    fun clusterToRegion(lines: List<OcrLineBox>, imgW: Int, imgH: Int): RoiBox? {
        if (lines.isEmpty() || imgW <= 0 || imgH <= 0) return null
        val sorted = lines.sortedBy { it.centerY }
        // ---- 阶段 1：链内贪心（行距 <=1.5 行高 + x 重叠 >=30%） ----
        val chains = mutableListOf<MutableList<OcrLineBox>>()
        for (line in sorted) {
            var target: MutableList<OcrLineBox>? = null
            for (chain in chains) {
                val ref = chain.maxBy { it.centerY }
                val gapOk = line.yGapTo(ref) <= maxOf(ref.height, line.height) * 1.5f
                val xOk = line.xOverlapWith(ref) >= 0.30f
                if (gapOk && xOk) { target = chain; break }
            }
            if (target != null) target.add(line) else chains.add(mutableListOf(line))
        }
        // ---- 阶段 2：链间联合（跨 TextBlock 几何合并） ----
        val unions = mutableListOf<MutableList<OcrLineBox>>()
        for (chain in chains.sortedBy { it.minOf { l -> l.centerY } }) {
            var target: MutableList<OcrLineBox>? = null
            for (u in unions) {
                val ref = u.maxBy { it.centerY }
                val lineH = maxOf(ref.height, chain.minOf { it.height })
                val gap = chain.minOf { l -> l.top } - ref.bottom
                val overlapW = minOf(chain.maxOf { it.right }, u.maxOf { it.right }) -
                    maxOf(chain.minOf { it.left }, u.minOf { it.left })
                val wider = maxOf(
                    chain.maxOf { it.right } - chain.minOf { it.left },
                    u.maxOf { it.right } - u.minOf { it.left },
                )
                val gapFactor = if (lineH > 0f) gap / lineH else CHAIN_UNION_GAP_FACTOR
                val xThreshold = if (gapFactor <= CHAIN_UNION_TIGHT_GAP) {
                    MIN_UNION_X_OVERLAP +
                        (CHAIN_UNION_X_OVERLAP - MIN_UNION_X_OVERLAP) * (gapFactor / CHAIN_UNION_TIGHT_GAP)
                } else {
                    CHAIN_UNION_X_OVERLAP
                }
                val xOk = wider > 0f && overlapW / wider >= xThreshold
                if (gap <= lineH * CHAIN_UNION_GAP_FACTOR && xOk) { target = u; break }
            }
            if (target != null) target.addAll(chain) else unions.add(chain.toMutableList())
        }
        // ---- 置信判据 + 总字符数优先选块 ----
        val plausible = unions
            .filter { it.size >= 2 || it.sumOf { l -> l.text.count { c -> c.isLetterOrDigit() } } >= 8 }
        if (plausible.isEmpty()) return null
        val best = plausible.maxWithOrNull(
            compareBy<MutableList<OcrLineBox>> { it.sumOf { l -> l.text.count { c -> c.isLetterOrDigit() } } }
                .thenBy { chain ->
                    val l = chain.minOf { it.left }; val r = chain.maxOf { it.right }
                    val t = chain.minOf { it.top }; val b = chain.maxOf { it.bottom }
                    (r - l) * (b - t)
                },
        ) ?: return null
        return RoiBox(
            best.minOf { it.left },
            best.minOf { it.top },
            best.maxOf { it.right },
            best.maxOf { it.bottom },
        )
    }

    companion object {
        private const val TAG = "STEEL_OCR"

        /** 定位单次调用限时 */
        const val LOCATE_TIMEOUT_MS = 4500L

        /** 文本行最小高度（图高比例）：低于该值视为噪点碎片 */
        const val MIN_LINE_HEIGHT_FRACTION = 0.025f

        /** 定位块外扩比例（防框边字符截断） */
        const val LOCATE_MARGIN_FRACTION = 0.08f

        /** 链间联合：垂直空隙 <= 较大行高 x 该因子 */
        const val CHAIN_UNION_GAP_FACTOR = 4.0f

        /** 链间联合：x 重叠（相对较宽链）>= 该比例才判为同一钢印整体 */
        const val CHAIN_UNION_X_OVERLAP = 0.40f

        /** 链间联合（gap 联动）：紧贴行 x 重叠最低接受比例 */
        const val MIN_UNION_X_OVERLAP = 0.15f

        /** 链间联合（gap 联动）：gap <= 该倍数行高时阈值线性放宽 */
        const val CHAIN_UNION_TIGHT_GAP = 1.5f
    }
}

/**
 * 降采样整图（存储方向）供定位：长边 <= [maxEdge] 的 2 的幂采样。
 */
fun decodeLongEdgeForLocate(file: File, maxEdge: Int = 1200): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(file.absolutePath, opts)
}

/** 定位框 → BitmapRegionDecoder 区域（clamp 到原图） */
fun roiToRect(roi: RoiBox, imgW: Int, imgH: Int): Rect =
    Rect(
        roi.left.toInt().coerceIn(0, imgW - 1),
        roi.top.toInt().coerceIn(0, imgH - 1),
        roi.right.toInt().coerceIn(1, imgW),
        roi.bottom.toInt().coerceIn(1, imgH),
    )
