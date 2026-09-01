package com.wearable.inspection.mobile.dpm

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/** 预处理候选的 ZXing 二值化方式：二值候选（策略 0/1/2）走全局单阈值
 *  [org.opencv.core.Core]-无关的 GlobalHistogramBinarizer（候选已焊接/二值化，
 *  二次局部阈值会破坏结构）；灰度候选（策略 3）走 ZXing HybridBinarizer 局部自适应。 */
enum class DpmBinarizer { GLOBAL, HYBRID }

/**
 * 单条预处理候选输出。名称稳定且可用于日志（如 s0-dark-close）；
 * pixels 为 0-255 亮度字节（CV_8UC1 语义），长度恒等于 width*height。
 * 二值候选只含 0/255（策略 1 为自适应原样输出，极性由解码侧反色双试兜底）；
 * 灰度候选（策略 3）不做 OpenCV 硬二值化，由 ZXing 按 [binarizer] 自行二值化。
 * pixels 由 [DpmPreprocessor] 唯一构造，调用方只读，不共享可被后续修改的 Mat。
 */
data class DpmCandidate(
    val name: String,
    val pixels: ByteArray,
    val width: Int,
    val height: Int,
    val binarizer: DpmBinarizer,
)

/**
 * 工业 DPM 码预处理算子（纯 JVM：仅 OpenCV 核心 + imgproc，无 Android 依赖，可单测）。
 *
 * 工业 DPM 码（针打 / 激光蚀刻 / 反色码）形态差异大，单一二值化无法通吃。
 * 每个策略返回**一个或多个候选**（全部进入 ZXing，不在预处理阶段做候选取舍）：
 *
 * - [STRATEGY_DOT_PEEN]：自适应二值化 + 3×3 矩形闭运算，双极性各一条候选 ——
 *   针撞离散点 ≤2px 缝隙焊死成实心线/模块，让 ZXing Detector 能定位完整的 L 角
 *   （散点 L 角是断的）。形态学必须作用在目标标记为白色前景的状态下，因此暗/亮
 *   物理极性分开处理（invert(close(A)) != close(invert(A))，整图反色替代不了
 *   另一种极性的形态学）。原 5×5 矩形核在 400px ROI 上会连接相邻模块破坏时钟边，
 *   已缩小为 3×3；**核形状用矩形而非椭圆**：3×3 椭圆为十字核，dilation 无对角
 *   偏移，2px 网格点阵的 (odd,odd) 孔洞永远填不上，模块残留 1px 棋盘孔导致
 *   L 角断线（JVM 探针实测：3×3 椭圆 FAIL / 3×3 矩形与 5×5 椭圆可解）；
 * - [STRATEGY_INVERTED]：双极性自适应 —— 原图与反相图各跑一次 Gaussian
 *   adaptiveThreshold(THRESH_BINARY)，两个候选分别进 ZXing（解码侧自带反色双试），
 *   不再按白像素数量二选一丢弃候选；
 * - [STRATEGY_LASER_ETCHED]：Otsu 双极性 + 3×3 椭圆膨胀 —— 浅色/细激光蚀刻码
 *   先抽码再膨胀加粗，弱标记变实心；暗/亮标记各一条形态学候选；
 * - [STRATEGY_ENHANCED_GRAY]：CLAHE（ClipLimit=4.0, 8×8）增强灰度 + 原始灰度
 *   对照 —— 两条 HYBRID 灰度候选交给 ZXing HybridBinarizer 自适应二值化
 *   （不预做硬阈值）。
 *
 * 由 [DpmAnalyzer] 用帧计数逐帧轮转四种策略（[strategiesForFrame]，生产
 * [DEBUG_ALL_DPM_STRATEGIES]=false 时恒单策略；诊断开关开启时依次 0..3）。
 *
 * 输出语义：二值候选输出**暗码亮底**（码=0、底=255，策略 1 的自适应原样输出除外，
 * 由解码侧反色双试覆盖）；策略 3 输出增强灰度（非二值）。DpmAnalyzer.decodePixels
 * 仍对每个候选保留反色双试作为兜底。
 */
object DpmPreprocessor {

    const val STRATEGY_DOT_PEEN = 0
    const val STRATEGY_INVERTED = 1
    const val STRATEGY_LASER_ETCHED = 2
    const val STRATEGY_ENHANCED_GRAY = 3
    const val STRATEGY_COUNT = 4

    /**
     * 同帧诊断开关（默认关）：true 时对同一帧依次运行 strategy 0～3，用于公平比较
     * 不同预处理策略；false 时保持 frameCount % 4 的生产轮转行为。不影响 Release 默认性能。
     */
    const val DEBUG_ALL_DPM_STRATEGIES = false

    /**
     * 帧计数 → 本帧预处理策略列表：生产（[debugAll]=false）恒单元素
     * （frameId % [STRATEGY_COUNT]）；诊断开启时依次 0..3 全部策略。
     */
    fun strategiesForFrame(frameId: Long, debugAll: Boolean = DEBUG_ALL_DPM_STRATEGIES): IntArray =
        if (debugAll) IntArray(STRATEGY_COUNT) { it }
        else intArrayOf((frameId % STRATEGY_COUNT).toInt())

    /**
     * Debug 开关：非 null 时**任意策略**的输入灰度原图 + 全部候选 PNG 可写入该目录
     * （真机设 `context.externalCacheDir/dpm_dump`，`adb pull` 验证二值化效果）。
     * 由 DpmAnalyzer 构造时接线（BuildConfig.DEBUG 门控）；JVM 单测不设置 → 无写入。
     *
     * **默认不写**：即使目录已设置，还必须有 [dumpBudget] 配额（显式
     * [requestDebugDump] 授予）才会落盘 —— 防止 debug 包默认全量写盘
     * （历史上曾膨胀到 ~3.4GB）。
     */
    @Volatile
    var debugDumpDir: String? = null

    /**
     * 落盘配额（纯 JVM 单测见 [DpmDumpBudget]）：null = 从不写；
     * 非 null 时每次 [preprocess] 最多消耗一组配额（整帧 = input + 全部候选）。
     * 单进程累计写满 [DpmDumpBudget.DEFAULT_MAX_FRAME_SETS] 组后自动停止。
     */
    @Volatile
    var dumpBudget: DpmDumpBudget? = null

    /** 显式请求 [frameSets] 组落盘（未接线 [dumpBudget] 时为 no-op；累计截断到硬上限） */
    fun requestDebugDump(frameSets: Int = 1) {
        dumpBudget?.request(frameSets)
    }

    /**
     * 8bit 灰度像素（0-255）→ 该策略的候选列表（每个策略 2 条）。
     * 二值候选只含 0/255（暗码亮底；策略 1 为自适应原样输出）；
     * 策略 3 为 HYBRID 灰度候选（不做硬二值化）。
     * [frameId] 仅用于 dump 文件名（同一组帧的 input/候选共享同一 frameId，便于关联）。
     * @throws IllegalArgumentException gray.size != w * h
     */
    fun preprocess(gray: IntArray, w: Int, h: Int, strategy: Int, frameId: Long = 0L): List<DpmCandidate> {
        require(gray.size == w * h) { "gray.size=${gray.size} != w*h=$w*$h" }
        val src = Mat(h, w, CvType.CV_8UC1)
        val bytes = ByteArray(gray.size) { gray[it].toByte() }
        src.put(0, 0, bytes)
        try {
            val candidates = when (strategy) {
                STRATEGY_DOT_PEEN -> dotPeenCandidates(src, w, h)
                STRATEGY_INVERTED -> invertedCandidates(src, w, h)
                STRATEGY_LASER_ETCHED -> laserCandidates(src, w, h)
                STRATEGY_ENHANCED_GRAY -> enhancedGrayCandidates(src, w, h)
                else -> error("unknown strategy=$strategy")
            }
            // Debug 落盘（策略调优素材）：**任意策略**都写 输入灰度原图 + 全部候选 PNG，
            // 便于离线比较各策略预处理效果（绿框 ROI 即输入图）。策略调优抓手：
            // input_s* = 绿框原始灰度，pre_s*_* = 各策略候选，同一 frameId 同帧关联。
            // 仅当显式授予过配额且目录已接线才写；一组配额覆盖本帧全部文件。
            val dir = debugDumpDir
            val budget = dumpBudget
            if (dir != null && budget != null && budget.tryConsume()) {
                runCatching { Imgcodecs.imwrite("$dir/input_s${strategy}_${w}x${h}_f$frameId.png", src) }
                for (c in candidates) {
                    runCatching { writeCandidatePng(dir, "pre_s${strategy}_${c.name}_${w}x${h}_f$frameId.png", c) }
                }
            }
            return candidates
        } finally {
            src.release()
        }
    }

    // ---------- 各策略候选生成 ----------

    /**
     * 策略 0 针撞点阵：物理极性分开做形态学（形态学必须作用在白色前景上）。
     * - s0-dark-close：BINARY_INV 抽暗点（暗码亮底）→ 3×3 矩形闭运算焊散点 → 反相归一化黑码白底；
     * - s0-bright-close：BINARY 抽亮点（亮码暗底，深色金属基面激光打白点）→ 闭运算 → 反相归一化。
     * 核形状说明：3×3 椭圆为十字核无对角偏移，填不上点阵 (odd,odd) 孔（模块残棋盘孔、
     * L 角断线）；3×3 矩形含对角偏移且半径仍为 1（不跨模块焊接）。
     */
    private fun dotPeenCandidates(src: Mat, w: Int, h: Int): List<DpmCandidate> {
        val dark = adaptiveThreshold(src, w, h, Imgproc.THRESH_BINARY_INV)
        val bright = adaptiveThreshold(src, w, h, Imgproc.THRESH_BINARY)
        try {
            closeInPlace(dark, 3, 3, Imgproc.MORPH_RECT)
            closeInPlace(bright, 3, 3, Imgproc.MORPH_RECT)
            return listOf(
                binaryCandidate("s0-dark-close", dark, w, h, invert = true),
                binaryCandidate("s0-bright-close", bright, w, h, invert = true),
            )
        } finally {
            dark.release()
            bright.release()
        }
    }

    /**
     * 策略 1 双极性自适应：原图与反相图各跑一次 BINARY 自适应，两个候选分别进
     * ZXing（解码侧自带反色双试兜底），**不再按白像素数量二选一丢弃候选**。
     * - s1-normal-adaptive：原图自适应（暗码亮底物理码输出即暗码亮底）；
     * - s1-inverted-gray-adaptive：灰度反相后自适应（亮码暗底物理码输出即暗码亮底）。
     */
    private fun invertedCandidates(src: Mat, w: Int, h: Int): List<DpmCandidate> {
        val normal = adaptiveThreshold(src, w, h, Imgproc.THRESH_BINARY)
        val notSrc = Mat()
        try {
            Core.bitwise_not(src, notSrc)
            val invertedGray = adaptiveThreshold(notSrc, w, h, Imgproc.THRESH_BINARY)
            try {
                return listOf(
                    binaryCandidate("s1-normal-adaptive", normal, w, h, invert = false),
                    binaryCandidate("s1-inverted-gray-adaptive", invertedGray, w, h, invert = false),
                )
            } finally {
                invertedGray.release()
            }
        } finally {
            normal.release()
            notSrc.release()
        }
    }

    /**
     * 策略 2 激光蚀刻：Otsu 双极性各一条形态学候选（同样必须白色前景做膨胀）。
     * - s2-dark-otsu-dilate：Otsu BINARY_INV 抽暗标记 → 3×3 椭圆膨胀加粗 → 反相归一化黑码白底；
     * - s2-bright-otsu-dilate：Otsu BINARY 抽亮标记 → 膨胀 → 反相归一化。
     */
    private fun laserCandidates(src: Mat, w: Int, h: Int): List<DpmCandidate> {
        val dark = otsu(src, Imgproc.THRESH_BINARY_INV)
        val bright = otsu(src, Imgproc.THRESH_BINARY)
        try {
            dilateInPlace(dark, 3, 3, Imgproc.MORPH_ELLIPSE)
            dilateInPlace(bright, 3, 3, Imgproc.MORPH_ELLIPSE)
            return listOf(
                binaryCandidate("s2-dark-otsu-dilate", dark, w, h, invert = true),
                binaryCandidate("s2-bright-otsu-dilate", bright, w, h, invert = true),
            )
        } finally {
            dark.release()
            bright.release()
        }
    }

    /**
     * 策略 3 增强灰度：不做 OpenCV 硬二值化，交给 ZXing HybridBinarizer 局部自适应。
     * - s3-clahe-hybrid：CLAHE(clipLimit=4.0, 8×8) 增强灰度；
     * - s3-gray-hybrid：原始灰度对照。
     */
    private fun enhancedGrayCandidates(src: Mat, w: Int, h: Int): List<DpmCandidate> {
        val clahe = Mat()
        try {
            Imgproc.createCLAHE(CLAHE_CLIP_LIMIT, Size(CLAHE_TILE, CLAHE_TILE)).apply(src, clahe)
            return listOf(
                grayCandidate("s3-clahe-hybrid", clahe, w, h),
                grayCandidate("s3-gray-hybrid", src, w, h),
            )
        } finally {
            clahe.release()
        }
    }

    // ---------- OpenCV 基础算子 ----------

    /** 自适应阈值（Gaussian，块=约 1/4 短边、强制奇数、上限 51，C=10）→ 新 Mat（调用方负责释放） */
    private fun adaptiveThreshold(src: Mat, w: Int, h: Int, threshType: Int): Mat {
        val dst = Mat()
        Imgproc.adaptiveThreshold(
            src, dst, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, threshType,
            blockSize(w, h), ADAPTIVE_C,
        )
        return dst
    }

    /** Otsu 阈值 → 新 Mat（调用方负责释放） */
    private fun otsu(src: Mat, threshType: Int): Mat {
        val dst = Mat()
        Imgproc.threshold(src, dst, 0.0, 255.0, threshType or Imgproc.THRESH_OTSU)
        return dst
    }

    /** 原地闭运算（先膨胀后腐蚀）：白色前景 ≤(k-2)px 缝隙焊接；kernel try-finally 释放 */
    private fun closeInPlace(mat: Mat, kx: Int, ky: Int, shape: Int) {
        val kernel = Imgproc.getStructuringElement(shape, Size(kx.toDouble(), ky.toDouble()))
        try {
            Imgproc.morphologyEx(mat, mat, Imgproc.MORPH_CLOSE, kernel)
        } finally {
            kernel.release()
        }
    }

    /** 原地膨胀：加粗白色前景标记；kernel try-finally 释放 */
    private fun dilateInPlace(mat: Mat, kx: Int, ky: Int, shape: Int) {
        val kernel = Imgproc.getStructuringElement(shape, Size(kx.toDouble(), ky.toDouble()))
        try {
            Imgproc.morphologyEx(mat, mat, Imgproc.MORPH_DILATE, kernel)
        } finally {
            kernel.release()
        }
    }

    // ---------- 候选封装 ----------

    /**
     * 二值候选（GLOBAL）：Mat → 0/255 字节，像素值归一化（≥128=白）；
     * [invert]=true 时反相（形态学作用在白色前景后的"反相归一化为黑码白底"）。
     */
    private fun binaryCandidate(name: String, mat: Mat, w: Int, h: Int, invert: Boolean): DpmCandidate {
        val data = ByteArray(w * h)
        mat.get(0, 0, data)
        for (i in data.indices) {
            val white = ((data[i].toInt() and 0xFF) >= 128) xor invert
            data[i] = if (white) 255.toByte() else 0
        }
        return DpmCandidate(name, data, w, h, DpmBinarizer.GLOBAL)
    }

    /** 灰度候选（HYBRID）：Mat → 原始灰度字节，不做硬二值化（由 ZXing HybridBinarizer 自行二值化） */
    private fun grayCandidate(name: String, mat: Mat, w: Int, h: Int): DpmCandidate {
        val data = ByteArray(w * h)
        mat.get(0, 0, data)
        return DpmCandidate(name, data, w, h, DpmBinarizer.HYBRID)
    }

    /** 候选字节 → PNG 落盘（仅 Debug 开关开启时使用；Mat try-finally 释放） */
    private fun writeCandidatePng(dir: String, path: String, c: DpmCandidate) {
        val m = Mat(c.height, c.width, CvType.CV_8UC1)
        try {
            m.put(0, 0, c.pixels)
            Imgcodecs.imwrite("$dir/$path", m)
        } finally {
            m.release()
        }
    }

    /** 自适应阈值邻域：约 1/4 短边，强制奇数，上限 [ADAPTIVE_BLOCK_SIZE] */
    private fun blockSize(w: Int, h: Int): Int {
        val raw = (minOf(w, h) / 4).coerceIn(3, ADAPTIVE_BLOCK_SIZE)
        return raw or 1
    }

    private const val ADAPTIVE_BLOCK_SIZE = 51
    /**
     * 自适应阈值常数偏移（C）：阈值 = 局部均值 - C。实测（点阵码合成图 8px 模块）
     * C=15 会让局部均值 < 暗码值+C 的区域整体翻白 —— 大块实心暗区（DataMatrix
     * L 角）中心被侵蚀成洞，解码失败；C=10 翻白区收缩到可忽略，解码正常。
     */
    private const val ADAPTIVE_C = 10.0
    /** CLAHE 对比度限制（越大增强越强；4.0 为强力增强，低对比金属码适用） */
    private const val CLAHE_CLIP_LIMIT = 4.0
    /** CLAHE 分块尺寸（8x8 块，块内局部直方图均衡） */
    private const val CLAHE_TILE = 8.0
}
