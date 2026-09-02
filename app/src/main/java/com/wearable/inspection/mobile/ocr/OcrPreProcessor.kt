package com.wearable.inspection.mobile.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 钢印 OCR 图像增强与多极性采样（分层验证的「预处理」层）。
 *
 * 金属钢印（凹字暗阴影 / 凸字反光亮点）对比度低、纹理杂（拉丝/反光/氧化斑），
 * 直接丢给通用 OCR 引擎识别率差。对每个输入按两种物理极性各生成**四条**候选
 * （归一化为亮底暗字，规避引擎对反色文本的弱支持）：
 *
 * - 正极性（凹字暗阴影）：原样灰度；
 * - 反极性（凸字反光亮点）：灰度反转(Bitwise NOT) —— 亮点变暗字，再走同一增强链。
 *
 * 每极性四条引擎候选（先按内容包围盒 **ROI 裁剪 + 放大** 到长边 ≥640 —— 钢印
 * 字符在整帧仅 ~30px 高，直接送引擎难识别；放大后字符高度 ~150px 是识别率关键）：
 * - `*-clahe`：CLAHE 动态对比度均衡灰度（**双级增强第一级**：clipLimit 按灰度 σ
 *   自适应 **3.5~4.5**，8x8 —— 低对比凹槽阴影 σ 小 → 更强局部均衡。不硬二值化，
 *   交给 ML Kit 内部自适应，对干净灰度识别率最高）；
 * - `*-gamma`：gamma 校正（压亮提暗，低对比金属件互补路径，作用在原始灰度）；
 * - `*-unsharp`：**串行双级增强第二级** —— 反锐化掩模作用在 CLAHE 增强后图像
 *   （Dst = Clahe + α·(Clahe − GaussianBlur(Clahe, (3,3), σ=1.0))，α=0.7），高频
 *   边缘残差放大，修正 W/N、3/5 临界混淆；
 * - `*-adaptive`：二值兜底（正极性凹字阴影 → 自适应局部阈值 GAUSSIAN；**反极性
 *   凸字反光亮点 → 像素反转后的矩阵做 Otsu 全局阈值** —— 反光区与基面呈全局
 *   亮度差，Otsu 一次分离优于局部窗口）。
 * 结构化管线由 [SteelStampOcrAnalyzer.analyzeStructured] 消费：全部候选过引擎后
 * 按几何行聚类 + 字符级加权融合（候选间互为证据，坏候选不会拖垮整体）。
 *
 * **候选过滤（吸收 DPM 复盘经验：候选取舍前置，坏候选不进引擎）**：
 * - 白像素比例 > [fillRatioLimitFor]（极性差异化容差：正极性 95% / 反极性 98%）：
 *   归一化候选接近纯白、几乎没有暗色笔画 → 自动丢弃。凸字冲压钢印笔画细，反极性
 *   Otsu 后白像素天然可达 95%+，正极性同阈值会整极性误杀 → 反极性放宽到 98%；
 *   纯空白件暗像素为 0 → 走 EMPTY 过滤，不受此阈值影响；
 * - 内容纵横比 > [MAX_CONTENT_ASPECT]（12:1）：单方向拉长的划痕/细线噪点 → 自动丢弃；
 * - 内容为空（无有效像素）→ 丢弃。
 * 过滤以各极性的自适应二值 Mat 为代理（内容区域在两候选间几何一致），
 * 通过则整极性两条候选全部保留，失败则整极性丢弃（不再把坏候选送进引擎烧预算）。
 *
 * 内存约定：全部临时 Mat 严格在 finally 中 release；输入 Bitmap 只读、不修改、
 * 不释放，所有权始终归调用方。候选 Bitmap 由调用方识别后 recycle。
 */
object OcrPreProcessor {

    /** 日志 Tag（分层日志格式：`[OCR] Stage=PreProcess ...`） */
    private const val TAG = "STEEL_OCR"

    /** 清晰度评估（Blur Score）：Bitmap → RGBA Mat → 拉普拉斯方差（越大越清晰）。 */
    fun computeBlurScore(bitmap: Bitmap): Double {
        val mat = Mat()
        try {
            Utils.bitmapToMat(bitmap, mat)
            return BlurDetector.computeBlurScore(mat)
        } finally {
            mat.release()
        }
    }

    /**
     * 多极性采样：按 [OcrPolarity] 各生成 clahe 灰度 + adaptive 二值两条候选（亮底暗字归一化），
     * 并施加填充率/纵横比/空内容过滤。全部候选被过滤时返回空列表（调用方进入失败层级）。
     */
    fun buildCandidates(bitmap: Bitmap): List<OcrCandidate> {
        val w = bitmap.width
        val h = bitmap.height
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val gray = Mat()
        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val out = mutableListOf<OcrCandidate>()
            for (polarity in OcrPolarity.entries) {
                buildPolarityCandidates(gray, polarity)?.let { out.addAll(it) }
            }
            return out
        } finally {
            gray.release()
            rgba.release()
        }
    }

    /**
     * 单极性候选生成（结构化管线：8 候选，无 180° 重复 —— ROI 引导框已固定方向）。
     * 输入 [gray] 为共享灰度 Mat（只读，不释放）。返回 null = 本极性候选被过滤层丢弃。
     *
     * 每极性 4 条引擎候选（名称稳定：pos-clahe / pos-gamma / pos-unsharp / pos-adaptive）：
     * - `-clahe`：**双级增强第一级** CLAHE 灰度（clipLimit 自适应 3.5~4.5, 8x8；
     *   主候选，ML Kit 对干净灰度识别率最高）；
     * - `-gamma`：gamma 校正灰度（gamma=1.5 压亮提暗，低对比金属件互补路径）；
     * - `-unsharp`：**串行双级增强第二级** —— 反锐化掩模（(3,3) σ=1.0、α=0.7，
     *   作用在 CLAHE 增强后图像，笔画边界锐化）；
     * - `-adaptive`：二值兜底（正极性：自适应局部阈值；反极性：像素反转后 Otsu
     *   全局阈值 —— 凸字反光与基面呈全局亮度差）。
     * 全部按内容包围盒 ROI 裁剪 + 放大到长边 ≥ [MIN_OCR_ROI_EDGE]（字符高度撑到
     * 百像素级，是识别率关键），归一化为亮底暗字。
     */
    private fun buildPolarityCandidates(gray: Mat, polarity: OcrPolarity): List<OcrCandidate>? {
        val prefix = when (polarity) {
            OcrPolarity.POSITIVE -> "pos"
            OcrPolarity.INVERTED -> "inv"
        }
        val base = Mat()
        val clahe = Mat()
        val binary = Mat()
        val gammaMat = Mat()
        val unsharpMat = Mat()
        try {
            // 反极性先灰度反转：凸字反光亮点 → 暗字，与凹字走同一条增强链
            if (polarity == OcrPolarity.INVERTED) {
                Core.bitwise_not(gray, base)
            } else {
                gray.copyTo(base)
            }
            // CLAHE 动态对比度均衡（双级增强第一级）：clipLimit 按灰度全局标准差
            // 自适应 —— 低对比金属件（凹槽阴影淡化、σ 小）需要最强局部均衡，高对比
            // 件降到下限防金属纹理放大。连续线性公式（无分档/特例）：σ=10 → 4.5 上限；
            // σ=40 → 3.5 下限。
            val clipLimit = adaptiveClipLimit(grayStdDev(gray))
            Imgproc.createCLAHE(clipLimit, Size(CLAHE_TILE, CLAHE_TILE)).apply(base, clahe)
            // 二值化**极性差异化**：凹字阴影是局部光照变化（阴影随凹槽轮廓渐变）→
            // 自适应局部阈值；凸字反光亮点在像素反转后与金属基面呈**全局**亮度差
            // （整片反光区一起亮）→ Otsu 全局阈值一次分离，避免局部窗口把亮字蚀掉。
            if (polarity == OcrPolarity.POSITIVE) {
                Imgproc.adaptiveThreshold(
                    clahe, binary, 255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY,
                    blockSize(gray.width(), gray.height()), ADAPTIVE_C,
                )
            } else {
                Imgproc.threshold(base, binary, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            }

            // ---- 过滤层（以二值 Mat 为代理；内容区域在灰度候选间几何一致） ----
            val metrics = binaryMetrics(binary)
            val box = metrics.contentBox
            val fillRatio = metrics.whiteRatio
            if (box == null) {
                Log.d(TAG, "[OCR] Stage=PreProcess polar=$polarity drop=EMPTY (无有效内容)")
                return null
            }
            val fillRatioLimit = fillRatioLimitFor(polarity)
            if (fillRatio > fillRatioLimit) {
                Log.d(TAG, "[OCR] Stage=PreProcess polar=$polarity drop=FILL_RATIO fill=${fillRatio * 100}% > ${(fillRatioLimit * 100).toInt()}% (接近纯白、无有效暗色笔画)")
                return null
            }
            val aspect = maxOf(box.width().toFloat() / box.height(), box.height().toFloat() / box.width())
            if (aspect > MAX_CONTENT_ASPECT) {
                Log.d(TAG, "[OCR] Stage=PreProcess polar=$polarity drop=ASPECT aspect=${"%.1f".format(aspect)} > $MAX_CONTENT_ASPECT (划痕/细线噪点)")
                return null
            }

            // gamma / unsharp 互补灰度（同一内容区域）：
            // - gamma 作用于原始灰度（互补路径，压亮提暗加深阴影笔画）；
            // - unsharp 作用于 **CLAHE 增强后** 图像 —— 串行双级增强第二级，反遮罩
            //   锐化放大凹槽压痕边缘的微小梯度，修正 W/N、3/5 临界混淆
            //   （Dst = Clahe + 0.7·(Clahe − GaussianBlur(Clahe, (3,3), σ=1.0))）
            gammaLut(base, gammaMat, GAMMA_CORRECTION)
            unsharpMask(clahe, unsharpMat, UNSHARP_AMOUNT)

            // ---- 通过过滤：ROI 裁剪放大，生成 4 条候选 ----
            val bmps = listOf(
                "$prefix-clahe" to cropToBitmap(clahe, box),
                "$prefix-gamma" to cropToBitmap(gammaMat, box),
                "$prefix-unsharp" to cropToBitmap(unsharpMat, box),
                "$prefix-adaptive" to cropToBitmap(binary, box),
            )
            Log.i(TAG, "[OCR] Stage=PreProcess polar=$polarity σ=${"%.1f".format(grayStdDev(gray))} clip=$clipLimit fill=${fillRatio * 100}% box=${box.width()}x${box.height()} roi=${bmps.first().second.width}x${bmps.first().second.height} aspect=${"%.1f".format(aspect)}")
            return bmps.map { (name, bmp) -> OcrCandidate(name, polarity, bmp, box) }
        } finally {
            unsharpMat.release()
            gammaMat.release()
            binary.release()
            clahe.release()
            base.release()
        }
    }

    /** 灰度全局标准差（CV_8U 单通道；低对比金属件 σ 小，凹槽阴影淡化） */
    private fun grayStdDev(gray: Mat): Double {
        val mean = MatOfDouble()
        val std = MatOfDouble()
        return try {
            Core.meanStdDev(gray, mean, std)
            std.toArray()[0]
        } finally {
            mean.release()
            std.release()
        }
    }

    /**
     * 自适应 CLAHE 对比度限制（纯函数，JVM 可测）：与灰度全局标准差成反比的连续线性
     * 公式 —— σ=10（极低对比凹槽阴影）→ 上限 4.5；σ=40（对比正常）→ 下限 3.5；
     * σ 更小 → 钳制 4.5、更大 → 钳制 3.5。无分档、无针对样本的特例分支。
     */
    fun adaptiveClipLimit(stdDev: Double): Double =
        (CLAHE_CLIP_MAX - (stdDev - 10.0) * CLIP_SCALE)
            .coerceIn(CLAHE_CLIP_MIN, CLAHE_CLIP_MAX)

    /**
     * 填充率过滤上限（纯函数，JVM 可测；极性差异化容差）：
     * 正极性凹字阴影笔画占比较粗 → 95%；反极性凸字冲压笔画细、反光面积小，
     * Otsu 二值后白像素占比天然偏高（实测 95.2%）→ 放宽至 98%，防整极性误杀。
     */
    fun fillRatioLimitFor(polarity: OcrPolarity): Float =
        if (polarity == OcrPolarity.INVERTED) MAX_FILL_RATIO_INVERTED else MAX_FILL_RATIO

    /** gamma 校正 LUT（gray → LUT(gray)；gamma > 1 压亮提暗，> 1 亮部细节压缩） */
    private fun gammaLut(src: Mat, dst: Mat, gamma: Double) {
        val lut = Mat(1, 256, CvType.CV_8UC1)
        try {
            val data = ByteArray(256)
            for (i in 0..255) {
                data[i] = (255.0 * Math.pow(i / 255.0, gamma)).toInt().toByte()
            }
            lut.put(0, 0, data)
            Core.LUT(src, lut, dst)
        } finally {
            lut.release()
        }
    }

    /**
     * 反锐化掩模（串行双级增强末级）：dst = src + α·(src − GaussianBlur(src, (3,3),
     * σ=1.0))。作用在 CLAHE 增强后图像上 —— 高频残差（凹槽压痕边缘的微小梯度）被
     * α 放大，笔画轮廓比单级 CLAHE 更锐利；σ=1.0 的 3x3 核只保留近邻像素差，
     * 不侵蚀笔画内部（与 DPM 复盘「2.0 clipLimit 太温和」结论呼应，本管线默认
     * 3.5~4.5 强均衡 + 0.7 高频放大）。
     */
    private fun unsharpMask(src: Mat, dst: Mat, amount: Double) {
        val blur = Mat()
        val diff = Mat()
        try {
            Imgproc.GaussianBlur(src, blur, Size(UNSHARP_KERNEL, UNSHARP_KERNEL), UNSHARP_SIGMA)
            Core.subtract(src, blur, diff)
            Core.addWeighted(src, 1.0, diff, amount, 0.0, dst)
        } finally {
            diff.release()
            blur.release()
        }
    }

    /**
     * 内容包围盒裁剪（外扩 15% 边距，clamp 图内）→ 长边不足 [MIN_OCR_ROI_EDGE]
     * 时等比放大（INTER_LINEAR）。返回新 Mat（放大时独立内存；未放大时 submat 共享
     * 父数据 —— 调用方 matToBitmap 即拷贝，父 Mat release 前完成即可）。
     */
    private fun cropRoiMat(src: Mat, box: Rect): Mat {
        val marginX = (box.width() * 0.15f).toInt().coerceAtLeast(8)
        val marginY = (box.height() * 0.15f).toInt().coerceAtLeast(8)
        val left = (box.left - marginX).coerceIn(0, src.width() - 1)
        val top = (box.top - marginY).coerceIn(0, src.height() - 1)
        val right = (box.right + marginX).coerceIn(left + 1, src.width())
        val bottom = (box.bottom + marginY).coerceIn(top + 1, src.height())
        val crop = src.submat(top, bottom, left, right)
        val cw = crop.width()
        val ch = crop.height()
        val longest = maxOf(cw, ch)
        if (longest >= MIN_OCR_ROI_EDGE) return crop
        val scale = MIN_OCR_ROI_EDGE.toDouble() / longest
        val dst = Mat()
        try {
            Imgproc.resize(
                crop, dst,
                Size(
                    (cw * scale).toInt().coerceAtLeast(1).toDouble(),
                    (ch * scale).toInt().coerceAtLeast(1).toDouble(),
                ),
                0.0, 0.0, Imgproc.INTER_LINEAR,
            )
        } finally {
            // 放大路径：crop 仅为共享父数据的中间 submat 视图，resize 完成即显式释放
            // header，避免逐帧累积 Native 内存（未放大路径的 crop 由调用方 cropToBitmap 释放）
            crop.release()
        }
        return dst
    }

    /** 裁剪 Mat 转 Bitmap，并及时释放 submat/resize 产生的 Mat header/native 内存。 */
    private fun cropToBitmap(src: Mat, box: Rect): Bitmap {
        val cropped = cropRoiMat(src, box)
        return try {
            matToBitmap(cropped)
        } finally {
            cropped.release()
        }
    }

    /** 二值 Mat → 内容统计（白像素比例 + 暗色文字最小外接矩形；无暗色笔画时 box = null） */
    private data class BinaryMetrics(val whiteRatio: Float, val contentBox: Rect?)

    private fun binaryMetrics(binary: Mat): BinaryMetrics {
        val w = binary.width()
        val h = binary.height()
        val data = ByteArray(w * h)
        binary.get(0, 0, data)
        var white = 0
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            var rowStart = y * w
            for (x in 0 until w) {
                val value = data[rowStart + x].toInt() and 0xFF
                if (value >= 128) {
                    white++
                } else {
                    // 所有候选在进入这里前都已归一化为亮底暗字。旧实现对白色背景求
                    // 包围盒，结果几乎恒为整幅 ROI，所谓"内容裁剪"实际上从未发生。
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        val box = if (maxX < minX) null else Rect(minX, minY, maxX + 1, maxY + 1)
        return BinaryMetrics(white.toFloat() / (w * h), box)
    }

    /** 灰度 Mat → ARGB_8888 Bitmap（候选输入；按 Mat 实际尺寸，调用方识别后 recycle） */
    private fun matToBitmap(mat: Mat): Bitmap {
        val bmp = Bitmap.createBitmap(mat.width(), mat.height(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bmp)
        return bmp
    }

    /** 自适应阈值邻域：约 1/4 短边，强制奇数，上限 [ADAPTIVE_BLOCK_SIZE]（与 DpmPreprocessor 同源参数） */
    private fun blockSize(w: Int, h: Int): Int {
        val raw = (minOf(w, h) / 4).coerceIn(3, ADAPTIVE_BLOCK_SIZE)
        return raw or 1
    }

    /** 自适应阈值常数偏移（阈值 = 局部均值 - C；DPM 复盘实测 C=10 不侵蚀实心笔画内部） */
    private const val ADAPTIVE_C = 10.0
    private const val ADAPTIVE_BLOCK_SIZE = 51
    /** CLAHE 对比度限制下限（σ≥40/对比正常件：3.5 强均衡，防金属纹理放大） */
    private const val CLAHE_CLIP_MIN = 3.5
    /** CLAHE 对比度限制上限（σ≤10/极低对比凹槽阴影：4.5 超强局部均衡） */
    private const val CLAHE_CLIP_MAX = 4.5
    /** 自适应基准：灰度标准差 ≤ 该值用上限（低对比凹槽压痕需要最强均衡） */
    private const val CONTRAST_REF = 40.0
    /** 每提升 1σ 降低的 clipLimit 幅度（σ=10 → 上限 4.5；σ=40 → 下限 3.5） */
    private const val CLIP_SCALE = 1.0 / (CONTRAST_REF - 10.0)
    /** CLAHE 分块尺寸（8x8 块内局部直方图均衡） */
    private const val CLAHE_TILE = 8.0
    /** gamma 校正指数（>1 压亮提暗，加深阴影笔画；与 CLAHE 互补的低对比路径） */
    private const val GAMMA_CORRECTION = 1.5
    /** 串行反锐化掩模强度 α（Dst = Clahe + α·(Clahe − Blur(Clahe))；规格 0.6~0.8） */
    private const val UNSHARP_AMOUNT = 0.7
    /** 反锐化高斯核尺寸（3x3，只放大近邻边缘残差，不侵蚀笔画内部） */
    private const val UNSHARP_KERNEL = 3.0
    /** 反锐化高斯 σ（1.0：核外扩展 3 像素内的细节锐化） */
    private const val UNSHARP_SIGMA = 1.0
    /** 白像素比例上限（正极性）：凹字阴影笔画占比较粗，95% 足够区分「接近纯白」；
     *  超过说明候选几乎没有暗色笔画 → 整极性丢弃。 */
    private const val MAX_FILL_RATIO = 0.95f
    /** 白像素比例上限（反极性）：凸字冲压笔画细、反光面积小，Otsu 二值后白像素
     *  占比天然偏高（实测 95.2%）—— 放宽到 98% 防整极性 4 条候选被误杀；真正的
     *  空白件暗像素为 0，仍会被 [contentBox]=null 的 EMPTY 过滤拦截，不会放空候选。 */
    private const val MAX_FILL_RATIO_INVERTED = 0.98f
    /** 内容包围盒纵横比上限：单方向拉长（划痕/细线）视为噪点 */
    private const val MAX_CONTENT_ASPECT = 12f

    /**
     * ROI 长边下限：钢印字符在整帧里占比小（高约 20-40px），直接送引擎难识别；
     * 按内容包围盒裁剪后若长边小于该值则等比放大，让字符高度撑到百像素级
     * （只放大不缩小：ROI 本身接近整帧时保持原分辨率，不损失细节）。
     */
    private const val MIN_OCR_ROI_EDGE = 640
}
