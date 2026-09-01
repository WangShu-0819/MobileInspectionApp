package com.wearable.inspection.mobile.dpm

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.datamatrix.DataMatrixReader
import com.google.zxing.datamatrix.decoder.Decoder
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt4
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Python 参考实现（DPM_Scanner_Source_20260820，识别 DPM 码很有效）的一模一样 Kotlin 移植。
 *
 * 来源文件：src/dpm_scanner/{grid,pipeline,localize,preprocess,decoder}.py
 * 移植原则：**逐函数翻译，函数名/参数/常量/阈值/核大小 1:1 保留，零"优化"改动**。
 * 唯一必要偏差：解码器 zxingcpp → Java ZXing（项目已有 com.google.zxing:core，无
 * zxing-cpp Android 原生库）。映射：
 * - 网格重建已得到逻辑模块矩阵，直接交给 ZXing [Decoder]，避免重新渲染后被
 *   [DataMatrixReader] 再做一次几何检测；
 * - 变体兜底（try_rotate/try_downscale/try_invert）→ 手动 4 向旋转 + 像素反色双试 +
 *   缩放 {1.0, 0.75, 0.5} 尝试
 *
 * 线程模型：无状态 object + 无共享可变状态，可安全地由后台协程（Dispatchers.Default）
 * 并发调用；DataMatrixReader 线程安全。所有 OpenCV Mat 在 try-finally 中成对释放
 * （防 JVM 堆外内存膨胀）。
 *
 * 实测基线（Python 源算法）：旧批次 91/113（80.5%，2867ms/帧）、新批次 79/115
 * （68.7%，4199ms/帧）；用户指定帧 input_400x533_1787219111940 → L0549630AE092212080057。
 */
object ImportedDpmScanner {

    // ================================================================ 对外接口

    /** pipeline.ScanResult 等价（bounds = (x, y, width, height)；dimension = 命中的网格
     *  重建尺寸，九宫格×变体直解兜底路径无尺寸概念时为 0） */
    data class ScanResult(
        val text: String,
        val regionName: String,
        val variantName: String,
        val bounds: IntArray,
        val durationMs: Float,
        val dimension: Int,
    )

    /**
     * pipeline.scan_image(image) 等价：灰度图（[gray]，w×h，CV_8UC1 亮度字节）完整扫描。
     * [mode] 控制重型网格重建尝试的 Data Matrix 尺寸（[DpmDimensionMode]，默认 AUTO =
     * 16/18/20 三尺寸配额+交错；固定模式只跑指定尺寸，配额放大到 24/12）。顺序与 Python
     * 完全一致：
     *   大图（长边 ≥2000px）→ rotated_grid(12 候选) → grid(24 候选)；
     *   小图 → grid(24 候选) → rotated_grid(12 候选)；
     *   都失败 → generate_regions 九宫格 × generate_variants 7 变体 → 直解
     *   （变体兜底尺寸无关，不受 [mode] 限制）。
     * 候选配额：AUTO 每尺寸 grid Top 8 / rotated Top 4，解码按尺寸名次交错
     *   （各尺寸第 1 名 → 第 2 名 → ……）；固定模式该尺寸 grid Top 24 / rotated Top 12。
     * [control]（可选，真机网格任务路径必传）：协作式截止/取消 —— 所有外层循环
     * （阶段切换、候选解码、box/dimension/left/refinement、region、variant/旋转/
     * 反色/缩放）主动调用 [DpmScanControl.shouldAbort]，deadline/取消到达即短路
     * 返回 null；单次 OpenCV native 调用不可打断，可能略微超出 deadline（毫秒级）。
     * null = 无预算约束（批量验证/单测路径）。
     */
    fun scanImage(
        gray: ByteArray,
        w: Int,
        h: Int,
        mode: DpmDimensionMode = DpmDimensionMode.AUTO,
        control: DpmScanControl? = null,
    ): ScanResult? {
        if (control.aborted()) return null
        val startedNanos = System.nanoTime()
        val largePhoto = max(w, h) >= 2000
        val dimensions = mode.dimensions()
        val gridPerDimension = gridQuotaPerDimension(dimensions.size)
        val rotatedPerDimension = rotatedQuotaPerDimension(dimensions.size)

        fun scanGridCandidates(candidates: List<GridCandidate>, regionPrefix: String): ScanResult? {
            for (candidate in candidates) {
                if (control.aborted()) return null
                val gridResult = decode_rectified_grid(gray, w, h, candidate.corners, candidate.dimension, control)
                if (gridResult != null) {
                    return ScanResult(
                        text = gridResult.text,
                        regionName = "${regionPrefix}_${candidate.dimension}",
                        variantName = "grid_std_r${gridResult.windowRadius}",
                        bounds = candidate.bounds,
                        durationMs = ((System.nanoTime() - startedNanos) / 1e6).toFloat(),
                        dimension = candidate.dimension,
                    )
                }
            }
            return null
        }

        if (largePhoto) {
            scanGridCandidates(findRotatedGridCandidates(gray, w, h, dimensions, rotatedPerDimension, control), "rotated_grid")
                ?.let { return it }
        }
        if (control.aborted()) return null
        scanGridCandidates(findGridCandidates(gray, w, h, dimensions, gridPerDimension, control), "grid")
            ?.let { return it }
        if (!largePhoto) {
            if (control.aborted()) return null
            scanGridCandidates(findRotatedGridCandidates(gray, w, h, dimensions, rotatedPerDimension, control), "rotated_grid")
                ?.let { return it }
        }

        val regions = generateRegions(gray, w, h)
        val orderedRegions = regions.filter { it.name != "full" } + regions.first()
        for (region in orderedRegions) {
            if (control.aborted()) return null
            for (variant in generateVariants(region.pixels, region.w, region.h, control)) {
                if (control.aborted()) return null
                val decoded = decodeBarcodes(variant.pixels, variant.w, variant.h, tryRotate = true, tryDownscale = true, tryInvert = true, control)
                if (decoded != null) {
                    return ScanResult(
                        text = decoded,
                        regionName = region.name,
                        variantName = variant.name,
                        bounds = intArrayOf(region.x, region.y, region.w, region.h),
                        durationMs = ((System.nanoTime() - startedNanos) / 1e6).toFloat(),
                        dimension = 0,
                    )
                }
            }
        }
        return null
    }

    /**
     * 变体直解兜底（decoder.decode_datamatrix 语义：try_rotate + try_downscale + try_invert）。
     * 供单测/上层直接验证预处理-解码链。[control] 可选（协作式截止）。
     */
    fun decodeVariants(gray: ByteArray, w: Int, h: Int, control: DpmScanControl? = null): String? =
        decodeBarcodes(gray, w, h, tryRotate = true, tryDownscale = true, tryInvert = true, control)

    /** 离散亮点候选的轴对齐点阵重建结果。 */
    data class DotGridResult(val text: String, val dimension: Int)

    /**
     * 策略 2 亮点候选的轻量点阵重建：短闭运算只用于定位近似方形点阵包围框，随后仍从
     * 原始候选逐模块采样，并将逻辑矩阵直接交给 ZXing 解码。扫码绿框要求工件大致对正，
     * 因而这里只处理轴对齐点阵；重透视/旋转仍交给完整网格重建链路。
     */
    fun decodeDotGridCandidate(
        pixels: ByteArray,
        w: Int,
        h: Int,
        dimensions: IntArray = intArrayOf(16, 18, 20),
    ): DotGridResult? {
        if (w <= 0 || h <= 0 || pixels.size != w * h || dimensions.isEmpty()) return null

        data class Box(val x: Int, val y: Int, val width: Int, val height: Int)

        val src = Mat(h, w, CvType.CV_8UC1)
        val foreground = Mat()
        val closed = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val boxes = LinkedHashMap<String, Box>()
        try {
            src.put(0, 0, pixels)
            Imgproc.threshold(src, foreground, 127.0, 255.0, Imgproc.THRESH_BINARY_INV)
            for (kernelSize in intArrayOf(5, 7, 9, 11)) {
                val kernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_RECT,
                    Size(kernelSize.toDouble(), kernelSize.toDouble()),
                )
                try {
                    Imgproc.morphologyEx(foreground, closed, Imgproc.MORPH_CLOSE, kernel)
                } finally {
                    kernel.release()
                }
                val count = Imgproc.connectedComponentsWithStats(closed, labels, stats, centroids)
                for (label in 1 until count) {
                    val x = stats.get(label, Imgproc.CC_STAT_LEFT)[0].toInt()
                    val y = stats.get(label, Imgproc.CC_STAT_TOP)[0].toInt()
                    val width = stats.get(label, Imgproc.CC_STAT_WIDTH)[0].toInt()
                    val height = stats.get(label, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                    val area = stats.get(label, Imgproc.CC_STAT_AREA)[0]
                    val shortSide = minOf(width, height)
                    val longSide = maxOf(width, height)
                    if (shortSide < dimensions.min() * 3 || longSide > minOf(w, h) * 0.95) continue
                    if (longSide.toDouble() / shortSide > 1.4) continue
                    // 离散点阵在包围框内占比较低；大面积反光/运动拖影粘成实心块时直接拒绝，
                    // 防不可解坏帧触发大量模块采样与 Decoder 尝试。
                    if (area / (width * height) > 0.32) continue
                    boxes.putIfAbsent("$x,$y,$width,$height", Box(x, y, width, height))
                }
            }
        } finally {
            centroids.release()
            stats.release()
            labels.release()
            closed.release()
            foreground.release()
            src.release()
        }

        // 最多尝试 8 个大候选，避免复杂工件纹理在最坏情况下放大 Decoder 调用量。
        for (box in boxes.values.sortedByDescending { minOf(it.width, it.height) }.take(8)) {
            for (dimension in dimensions) {
                for (insetY in 1..4) {
                    val top = box.y + insetY
                    val bottom = box.y + box.height - 1 - insetY
                    if (bottom <= top) continue
                    for (insetX in 1..4) {
                        val left = box.x + insetX
                        val right = box.x + box.width - 1 - insetX
                        if (right <= left) continue
                        val counts = IntArray(dimension * dimension)
                        for (row in 0 until dimension) {
                            val cy = pyRound(top + row * (bottom - top).toDouble() / (dimension - 1))
                            for (col in 0 until dimension) {
                                val cx = pyRound(left + col * (right - left).toDouble() / (dimension - 1))
                                var black = 0
                                for (yy in maxOf(0, cy - 2)..minOf(h - 1, cy + 2)) {
                                    for (xx in maxOf(0, cx - 2)..minOf(w - 1, cx + 2)) {
                                        if ((pixels[yy * w + xx].toInt() and 0xFF) < 128) black++
                                    }
                                }
                                counts[row * dimension + col] = black
                            }
                        }
                        for (minimumBlack in intArrayOf(3, 5, 7)) {
                            val bits = BooleanArray(counts.size) { counts[it] >= minimumBlack }
                            decodePureBitsAnyOrientation(bits, dimension)?.let {
                                return DotGridResult(it, dimension)
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    // ================================================================ 数据结构

    /** grid.GridDecodeResult 等价 */
    private data class GridDecodeResult(
        val text: String,
        val dimension: Int,
        val windowRadius: Int,
        val threshold: Float,
    )

    /** grid.GridCandidate 等价；corners 为 4 角点 (x,y)×4：TL, TR, BL, BR */
    private data class GridCandidate(
        val corners: DoubleArray,
        val dimension: Int,
        val score: Double,
        val bounds: IntArray,
    )

    /** localize.ImageRegion 等价 */
    private data class ImageRegion(
        val name: String,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val pixels: ByteArray,
    )

    /** preprocess.ImageVariant 等价 */
    private data class ImageVariant(
        val name: String,
        val w: Int,
        val h: Int,
        val pixels: ByteArray,
    )

    // ================================================================ 解码（zxingcpp → Java ZXing）

    private val reader = DataMatrixReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.DATA_MATRIX),
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * zxingcpp.read_barcodes 映射。网格渲染图调用时全 false（is_pure 语义：原图直接解）；
     * 变体兜底调用时全 true（4 向旋转 + 反色双试 + 缩放 1.0/0.75/0.5）。
     */
    private fun decodeBarcodes(
        bytes: ByteArray,
        w: Int,
        h: Int,
        tryRotate: Boolean,
        tryDownscale: Boolean,
        tryInvert: Boolean,
        control: DpmScanControl? = null,
    ): String? {
        val images = ArrayList<ByteArray3>(4)
        images.add(ByteArray3(bytes, w, h))
        if (tryRotate) {
            for (rot in 1 until 4) {
                if (control.aborted()) return null
                val r = rotateGray(bytes, w, h, rot)
                val (rw, rh) = if (rot == 2) w to h else h to w
                images.add(ByteArray3(r, rw, rh))
            }
        }
        for (image in images) {
            if (control.aborted()) return null
            val polarities = if (tryInvert) {
                listOf(image, ByteArray3(invertBytes(image.data), image.w, image.h))
            } else {
                listOf(image)
            }
            for (polarity in polarities) {
                if (control.aborted()) return null
                val scales = if (tryDownscale) doubleArrayOf(1.0, 0.75, 0.5) else doubleArrayOf(1.0)
                for (scale in scales) {
                    if (control.aborted()) return null
                    if (scale == 1.0) {
                        decodeSingle(polarity.data, polarity.w, polarity.h)?.let { return it }
                    } else {
                        val dw = max(1, pyRound(polarity.w * scale))
                        val dh = max(1, pyRound(polarity.h * scale))
                        decodeSingle(nearestResize(polarity.data, polarity.w, polarity.h, dw, dh), dw, dh)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    /** 单次 DataMatrix 解码：GlobalHistogramBinarizer（等价源 FixedThreshold 场景） */
    private fun decodeSingle(bytes: ByteArray, w: Int, h: Int): String? = runCatching {
        reader.decode(
            BinaryBitmap(
                GlobalHistogramBinarizer(
                    PlanarYUVLuminanceSource(bytes, w, h, 0, 0, w, h, false)
                )
            ),
            hints,
        )
    }.getOrNull()?.text?.trim()?.takeIf { it.isNotEmpty() }

    private class ByteArray3(val data: ByteArray, val w: Int, val h: Int)

    /** 像素反色（255 - v）：try_invert 双试 */
    private fun invertBytes(src: ByteArray): ByteArray {
        val out = ByteArray(src.size)
        for (i in src.indices) out[i] = (255 - (src[i].toInt() and 0xFF)).toByte()
        return out
    }

    /** 最近邻缩放：try_downscale {0.75, 0.5} 用 */
    private fun nearestResize(src: ByteArray, w: Int, h: Int, dw: Int, dh: Int): ByteArray {
        val out = ByteArray(dw * dh)
        for (y in 0 until dh) {
            val sy = min(h - 1, y * h / dh)
            var off = y * dw
            for (x in 0 until dw) out[off++] = src[sy * w + min(w - 1, x * w / dw)]
        }
        return out
    }

    // ================================================================ grid.py

    /**
     * grid._module_centers：4 角点双线性插值 → dimension×dimension 模块中心点
     * （行序 [row][col]，每个点 (x, y)）。Python 用 float32；这里用 Double 计算
     * （最终都经 rint 取整，数值差异 ≤1 ulp，不影响行为）。
     */
    private fun moduleCenters(corners: DoubleArray, dimension: Int): DoubleArray {
        val points = DoubleArray(dimension * dimension * 2)
        val denominator = dimension - 1
        var idx = 0
        for (row in 0 until dimension) {
            val vertical = row.toDouble() / denominator
            for (col in 0 until dimension) {
                val horizontal = col.toDouble() / denominator
                val wx0 = 1 - horizontal
                val wy0 = 1 - vertical
                points[idx++] = wx0 * wy0 * corners[0] + horizontal * wy0 * corners[2] +
                    wx0 * vertical * corners[4] + horizontal * vertical * corners[6]
                points[idx++] = wx0 * wy0 * corners[1] + horizontal * wy0 * corners[3] +
                    wx0 * vertical * corners[5] + horizontal * vertical * corners[7]
            }
        }
        return points
    }

    /**
     * grid._local_standard_deviations：每个模块中心 (2r+1)² 补丁总体标准差
     * （np.std ddof=0）。任一点越界返回 null。
     */
    private fun localStandardDeviations(
        image: ByteArray,
        w: Int,
        h: Int,
        points: DoubleArray,
        dimension: Int,
        radius: Int,
    ): FloatArray? {
        val scores = FloatArray(dimension * dimension)
        for (row in 0 until dimension) {
            for (col in 0 until dimension) {
                val cx = rint(points[(row * dimension + col) * 2])
                val cy = rint(points[(row * dimension + col) * 2 + 1])
                if (cx - radius < 0 || cy - radius < 0 || cx + radius >= w || cy + radius >= h) return null
                var sum = 0.0
                var sumSq = 0.0
                var n = 0
                for (yy in cy - radius..cy + radius) {
                    var off = yy * w + (cx - radius)
                    for (xx in cx - radius..cx + radius) {
                        val v = (image[off++].toInt() and 0xFF).toDouble()
                        sum += v
                        sumSq += v * v
                        n++
                    }
                }
                val mean = sum / n
                scores[row * dimension + col] = sqrt(max(sumSq / n - mean * mean, 0.0)).toFloat()
            }
        }
        return scores
    }

    /** grid._threshold_candidates：unique 值相邻中点（1 个 unique 值时返回自身） */
    private fun thresholdCandidates(scores: FloatArray): FloatArray {
        val sorted = scores.copyOf()
        sorted.sort()
        val unique = ArrayList<Float>(sorted.size)
        for (v in sorted) if (unique.isEmpty() || v != unique[unique.size - 1]) unique.add(v)
        if (unique.size == 1) return floatArrayOf(unique[0])
        val out = FloatArray(unique.size - 1)
        for (i in 0 until unique.size - 1) out[i] = (unique[i] + unique[i + 1]) / 2f
        return out
    }

    /** 已完成定位和模块判定的纯逻辑矩阵直接解码，不再重复执行几何检测。 */
    private fun decodePureBits(bits: BooleanArray, dimension: Int): String? = runCatching {
        val matrix = BitMatrix(dimension)
        for (row in 0 until dimension) {
            for (col in 0 until dimension) {
                if (bits[row * dimension + col]) matrix.set(col, row)
            }
        }
        Decoder().decode(matrix).text
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** 相机/裁剪方向未知时尝试四向旋转及镜像；ECC 校验防止随机点阵伪命中。 */
    private fun decodePureBitsAnyOrientation(bits: BooleanArray, dimension: Int): String? {
        for (mirrored in booleanArrayOf(false, true)) {
            for (rotation in 0 until 4) {
                val transformed = BooleanArray(bits.size)
                for (row in 0 until dimension) {
                    for (col in 0 until dimension) {
                        if (!bits[row * dimension + col]) continue
                        val (rotatedRow, rotatedCol) = when (rotation) {
                            0 -> row to col
                            1 -> col to dimension - 1 - row
                            2 -> dimension - 1 - row to dimension - 1 - col
                            else -> dimension - 1 - col to row
                        }
                        val targetCol = if (mirrored) dimension - 1 - rotatedCol else rotatedCol
                        transformed[rotatedRow * dimension + targetCol] = true
                    }
                }
                decodePureBits(transformed, dimension)?.let { return it }
            }
        }
        return null
    }

    /** grid._enforce_finder_pattern：强制 Data Matrix 探测图形 */
    private fun enforceFinderPattern(bits: BooleanArray, dimension: Int) {
        for (j in 0 until dimension) {
            bits[j * dimension] = true                 // 列 0 全真（左实心臂）
            bits[(dimension - 1) * dimension + j] = true // 行 -1 全真（底实心臂）
        }
        for (i in 0 until dimension) {
            bits[i] = (i % 2 == 0)                     // 行 0 偶列真（顶交替臂）
            bits[i * dimension + (dimension - 1)] = (i % 2 == 1) // 列 -1 奇行真（右交替臂）
        }
    }

    /**
     * grid.decode_rectified_grid：18×18 模块中心采样 → pitch 中位数 → 半径
     * {round(pitch*0.12..0.45)} 去重 → 局部 std → 相邻阈值 → bits → 强制
     * finder pattern → 逻辑矩阵直解。遍历顺序与 Python 完全一致（半径升序、
     * 阈值升序，解出即短路返回）。
     */
    private fun decode_rectified_grid(
        image: ByteArray,
        w: Int,
        h: Int,
        moduleCenterCorners: DoubleArray,
        dimension: Int,
        control: DpmScanControl? = null,
    ): GridDecodeResult? {
        val points = moduleCenters(moduleCenterCorners, dimension)
        // 水平 pitch：points[.,1:] - points[.,:-1]（dim×(dim-1)）
        // 垂直 pitch：points[1:.,:] - points[:-1,.]（(dim-1)×dim）
        val pitches = DoubleArray(dimension * (dimension - 1) * 2)
        var p = 0
        for (row in 0 until dimension) {
            for (col in 0 until dimension - 1) {
                val i0 = (row * dimension + col) * 2
                val i1 = i0 + 2
                pitches[p++] = hypot(points[i1] - points[i0], points[i1 + 1] - points[i0 + 1])
            }
        }
        for (row in 0 until dimension - 1) {
            for (col in 0 until dimension) {
                val i0 = (row * dimension + col) * 2
                val i1 = i0 + dimension * 2
                pitches[p++] = hypot(points[i1] - points[i0], points[i1 + 1] - points[i0 + 1])
            }
        }
        val modulePitch = median(pitches)
        val radii = sortedSetOf<Int>()
        for (ratio in doubleArrayOf(0.12, 0.18, 0.25, 0.32, 0.38, 0.45)) {
            radii.add(max(1, pyRound(modulePitch * ratio)))
        }
        for (radius in radii) {
            if (control.aborted()) return null
            val scores = localStandardDeviations(image, w, h, points, dimension, radius) ?: continue
            for (threshold in thresholdCandidates(scores)) {
                if (control.aborted()) return null
                val bits = BooleanArray(dimension * dimension) { scores[it] > threshold }
                enforceFinderPattern(bits, dimension)
                val decoded = decodePureBits(bits, dimension)
                if (decoded != null) return GridDecodeResult(decoded, dimension, radius, threshold)
            }
        }
        return null
    }

    /** grid._standard_deviation_map：7×7 boxFilter 均值/均方根 → 局部标准差图 */
    private fun standardDeviationMap(image: ByteArray, w: Int, h: Int, kernelSize: Int = 7): FloatArray {
        val imageFloat = Mat(h, w, CvType.CV_32FC1)
        val vals = FloatArray(w * h) { (image[it].toInt() and 0xFF).toFloat() }
        imageFloat.put(0, 0, vals)
        val mean = Mat()
        val squared = Mat()
        val sq = Mat()
        try {
            Imgproc.boxFilter(imageFloat, mean, CvType.CV_32FC1, Size(kernelSize.toDouble(), kernelSize.toDouble()))
            Core.multiply(imageFloat, imageFloat, sq)
            Imgproc.boxFilter(sq, squared, CvType.CV_32FC1, Size(kernelSize.toDouble(), kernelSize.toDouble()))
            val meanArr = FloatArray(w * h)
            val sqArr = FloatArray(w * h)
            mean.get(0, 0, meanArr)
            squared.get(0, 0, sqArr)
            val out = FloatArray(w * h)
            for (i in out.indices) {
                out[i] = sqrt(max(sqArr[i] - meanArr[i] * meanArr[i], 0f)).toFloat()
            }
            return out
        } finally {
            sq.release()
            mean.release()
            squared.release()
            imageFloat.release()
        }
    }

    /** grid._intersection_over_union */
    private fun intersectionOverUnion(first: IntArray, second: IntArray): Double {
        val left = max(first[0], second[0])
        val top = max(first[1], second[1])
        val right = min(first[0] + first[2], second[0] + second[2])
        val bottom = min(first[1] + first[3], second[1] + second[3])
        val intersection = max(0, right - left) * max(0, bottom - top)
        val union = first[2] * first[3] + second[2] * second[3] - intersection
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    /**
     * grid._candidate_boxes：阈值 {20,25,30,35,40} ∪ {p80,p85,p90} → CLOSE(5×5 RECT)
     * → OPEN(3×3 RECT) → findContours → 过滤（边≥20%短边/≤90%、宽高比 0.60-1.50、
     * fill≥0.18）→ score=w*h*(0.5+fill) → IoU>0.55 去重 top3。
     */
    private fun candidateBoxes(standardDeviation: FloatArray, w: Int, h: Int, control: DpmScanControl? = null): List<Pair<IntArray, Double>> {
        val shortestSide = min(h, w)
        val thresholds = sortedSetOf<Float>()
        thresholds.addAll(listOf(20f, 25f, 30f, 35f, 40f))
        for (percentile in listOf(80, 85, 90)) {
            thresholds.add(percentileFloat(standardDeviation, percentile.toDouble()))
        }
        val found = ArrayList<Pair<IntArray, Double>>()
        val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        try {
            for (threshold in thresholds) {
                if (control.aborted()) break
                val mask = Mat(h, w, CvType.CV_8UC1)
                val maskBytes = ByteArray(w * h) { if (standardDeviation[it] > threshold) 255.toByte() else 0 }
                mask.put(0, 0, maskBytes)
                val merged = Mat()
                val opened = Mat()
                val hierarchy = Mat()
                val contours = ArrayList<MatOfPoint>()
                try {
                    Imgproc.morphologyEx(mask, merged, Imgproc.MORPH_CLOSE, closeKernel)
                    Imgproc.morphologyEx(merged, opened, Imgproc.MORPH_OPEN, openKernel)
                    Imgproc.findContours(opened, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                    for (contour in contours) {
                        val r = Imgproc.boundingRect(contour)
                        if (min(r.width, r.height) < shortestSide * 0.20) continue
                        if (max(r.width, r.height) > shortestSide * 0.90) continue
                        val aspectRatio = r.width.toDouble() / r.height
                        if (aspectRatio < 0.60 || aspectRatio > 1.50) continue
                        val fillRatio = Imgproc.contourArea(contour) / (r.width * r.height)
                        if (fillRatio < 0.18) continue
                        val score = r.width * r.height * (0.5 + fillRatio)
                        found.add(intArrayOf(r.x, r.y, r.width, r.height) to score)
                    }
                } finally {
                    hierarchy.release()
                    contours.forEach { it.release() }
                    opened.release()
                    merged.release()
                    mask.release()
                }
            }
        } finally {
            openKernel.release()
            closeKernel.release()
        }
        val selected = ArrayList<Pair<IntArray, Double>>()
        for ((bounds, score) in found.sortedByDescending { it.second }) {
            if (selected.any { intersectionOverUnion(bounds, it.first) > 0.55 }) continue
            selected.add(bounds to score)
            if (selected.size >= 3) break
        }
        return selected
    }

    /** grid._order_rotated_box：4 点按 y 分上下两半、各自按 x 排序 → TL, TR, BL, BR */
    private fun orderRotatedBox(box: DoubleArray): DoubleArray {
        val byY = IntArray(4) { it }.sortedBy { box[it * 2 + 1] }
        val top = byY.take(2).sortedBy { box[it * 2] }
        val bottom = byY.drop(2).sortedBy { box[it * 2] }
        val out = DoubleArray(8)
        for (k in 0 until 4) {
            val src = if (k < 2) top[k] else bottom[k - 2]
            out[k * 2] = box[src * 2]
            out[k * 2 + 1] = box[src * 2 + 1]
        }
        return out
    }

    /** grid._rotated_finder_score：L 实边（列 0 + 行 -1）强度 + 顶/右交替对比 */
    private fun rotatedFinderScore(
        standardDeviation: FloatArray,
        w: Int,
        h: Int,
        corners: DoubleArray,
        dimension: Int,
    ): Double {
        val points = moduleCenters(corners, dimension)
        val n = dimension * dimension
        val xs = IntArray(n)
        val ys = IntArray(n)
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (i in 0 until n) {
            val x = rint(points[i * 2])
            val y = rint(points[i * 2 + 1])
            xs[i] = x
            ys[i] = y
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (minX < 0 || maxX >= w || minY < 0 || maxY >= h) return Double.NEGATIVE_INFINITY
        val values = DoubleArray(n)
        for (i in 0 until n) values[i] = standardDeviation[ys[i] * w + xs[i]].toDouble()
        // top_contrast = values[0, ::2].mean - values[0, 1::2].mean
        var evenSum = 0.0
        var oddSum = 0.0
        var evenN = 0
        var oddN = 0
        for (c in 0 until dimension) {
            if (c % 2 == 0) { evenSum += values[c]; evenN++ } else { oddSum += values[c]; oddN++ }
        }
        val topContrast = evenSum / evenN - oddSum / oddN
        // right_contrast = values[1::2, -1].mean - values[::2, -1].mean
        evenSum = 0.0; oddSum = 0.0; evenN = 0; oddN = 0
        for (r in 0 until dimension) {
            val v = values[r * dimension + dimension - 1]
            if (r % 2 == 1) { oddSum += v; oddN++ } else { evenSum += v; evenN++ }
        }
        val rightContrast = oddSum / oddN - evenSum / evenN
        // solid_strength = values[:, 0].mean + values[-1, :].mean
        var solid = 0.0
        for (r in 0 until dimension) solid += values[r * dimension]
        for (c in 0 until dimension) solid += values[(dimension - 1) * dimension + c]
        return solid / dimension + topContrast + rightContrast
    }

    /**
     * grid._rotated_rectangle_candidates：阈值 {15..40} ∪ {p80,p85,p90} → CLOSE/OPEN →
     * findContours → minAreaRect → boxPoints → 过滤 → 每框 × shrink(-0.02..0.08@0.01)
     * × 3 几何(shrunk/upward/stronger_right) → finder score。注意 Python 的 limit 参数
     * 在函数体内未被使用（返回全部候选），此处同样不截断。
     */
    private fun rotatedRectangleCandidates(
        standardDeviation: FloatArray,
        w: Int,
        h: Int,
        dimension: Int,
        control: DpmScanControl? = null,
    ): List<Triple<DoubleArray, IntArray, Double>> {
        val shortestSide = min(h, w)
        val thresholds = sortedSetOf<Float>()
        thresholds.addAll(listOf(15f, 20f, 25f, 30f, 35f, 40f))
        for (percentile in listOf(80, 85, 90)) {
            thresholds.add(percentileFloat(standardDeviation, percentile.toDouble()))
        }
        val found = ArrayList<Triple<DoubleArray, IntArray, Double>>()
        val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        try {
            for (threshold in thresholds) {
                if (control.aborted()) break
                val mask = Mat(h, w, CvType.CV_8UC1)
                val maskBytes = ByteArray(w * h) { if (standardDeviation[it] > threshold) 255.toByte() else 0 }
                mask.put(0, 0, maskBytes)
                val merged = Mat()
                val opened = Mat()
                val hierarchy = Mat()
                val contours = ArrayList<MatOfPoint>()
                try {
                    Imgproc.morphologyEx(mask, merged, Imgproc.MORPH_CLOSE, closeKernel)
                    Imgproc.morphologyEx(merged, opened, Imgproc.MORPH_OPEN, openKernel)
                    Imgproc.findContours(opened, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                    for (contour in contours) {
                        val contour2f = MatOfPoint2f(*contour.toArray())
                        val rect = try {
                            Imgproc.minAreaRect(contour2f)
                        } finally {
                            contour2f.release()
                        }
                        val rectWidth = rect.size.width
                        val rectHeight = rect.size.height
                        if (min(rectWidth, rectHeight) < shortestSide * 0.20) continue
                        if (max(rectWidth, rectHeight) > shortestSide * 0.90) continue
                        val aspectRatio = rectWidth / rectHeight
                        if (aspectRatio < 0.60 || aspectRatio > 1.50) continue
                        val area = rectWidth * rectHeight
                        val fillRatio = if (area != 0.0) Imgproc.contourArea(contour) / area else 0.0
                        if (fillRatio < 0.18) continue
                        val boxMat = MatOfPoint2f()
                        try {
                            Imgproc.boxPoints(rect, boxMat)
                            val boxPts = boxMat.toArray()
                            val box = DoubleArray(8) { k -> if (k % 2 == 0) boxPts[k / 2].x else boxPts[k / 2].y }
                            val bounds = floatBounds(box)
                            val score = area * (0.5 + fillRatio)
                            found.add(Triple(box, bounds, score))
                        } finally {
                            boxMat.release()
                        }
                    }
                } finally {
                    hierarchy.release()
                    contours.forEach { it.release() }
                    opened.release()
                    merged.release()
                    mask.release()
                }
            }
        } finally {
            openKernel.release()
            closeKernel.release()
        }
        val selected = found.sortedByDescending { it.third }
        val candidates = ArrayList<Triple<DoubleArray, IntArray, Double>>()
        for ((box, bounds, boxScore) in selected) {
            if (control.aborted()) break
            val ordered = orderRotatedBox(box)
            val centerX = (ordered[0] + ordered[2] + ordered[4] + ordered[6]) / 4
            val centerY = (ordered[1] + ordered[3] + ordered[5] + ordered[7]) / 4
            for (shrink in arange(-0.02, 0.081, 0.01)) {
                if (control.aborted()) break
                val factor = 1 - shrink
                val shrunk = DoubleArray(8) { k ->
                    if (k % 2 == 0) centerX + (ordered[k] - centerX) * factor
                    else centerY + (ordered[k] - centerY) * factor
                }
                val lastModule = dimension - 1
                val topModuleX = (shrunk[2] - shrunk[0]) / lastModule
                val topModuleY = (shrunk[3] - shrunk[1]) / lastModule
                val bottomModuleX = (shrunk[6] - shrunk[4]) / lastModule
                val bottomModuleY = (shrunk[7] - shrunk[5]) / lastModule
                val leftModuleX = (shrunk[4] - shrunk[0]) / lastModule
                val leftModuleY = (shrunk[5] - shrunk[1]) / lastModule
                val rightModuleX = (shrunk[6] - shrunk[2]) / lastModule
                val rightModuleY = (shrunk[7] - shrunk[3]) / lastModule
                val upward = shrunk.copyOf()
                upward[0] += 0.20 * topModuleX - 0.35 * leftModuleX
                upward[1] += 0.20 * topModuleY - 0.35 * leftModuleY
                upward[4] += 0.20 * bottomModuleX - 0.35 * leftModuleX
                upward[5] += 0.20 * bottomModuleY - 0.35 * leftModuleY
                upward[2] -= 0.35 * rightModuleX
                upward[3] -= 0.35 * rightModuleY
                upward[6] -= 0.35 * rightModuleX
                upward[7] -= 0.35 * rightModuleY
                val strongerRight = shrunk.copyOf()
                strongerRight[0] -= 0.35 * leftModuleX
                strongerRight[1] -= 0.35 * leftModuleY
                strongerRight[4] -= 0.35 * leftModuleX
                strongerRight[5] -= 0.35 * leftModuleY
                strongerRight[2] -= 0.85 * rightModuleX
                strongerRight[3] -= 0.85 * rightModuleY
                strongerRight[6] -= 0.85 * rightModuleX
                strongerRight[7] -= 0.85 * rightModuleY
                for (geometry in arrayOf(shrunk, upward, strongerRight)) {
                    val finderScore = rotatedFinderScore(standardDeviation, w, h, geometry, dimension)
                    candidates.add(Triple(geometry, bounds, 1000.0 + finderScore + ln(1 + boxScore)))
                }
            }
        }
        return candidates.sortedByDescending { it.third }
    }

    /**
     * grid._border_score：顶边 = 偶列(黑)均值 - 奇列(白)均值 + 0.5*p20(黑)；
     * 底边 = 列中心均值 - 列间中点均值 + 0.5*p20(中心)。
     */
    private fun borderScore(
        standardDeviation: FloatArray,
        w: Int,
        h: Int,
        dimension: Int,
        xLeft: Double,
        yLeft: Double,
        pitch: Double,
        yDelta: Double,
        isTop: Boolean,
    ): Double {
        val xPositions = IntArray(dimension) { c -> rint(xLeft + c * pitch) }
        val yPositions = IntArray(dimension) { c -> rint(yLeft + c * yDelta / (dimension - 1)) }
        var yMin = Int.MAX_VALUE
        var yMax = Int.MIN_VALUE
        for (y in yPositions) {
            if (y < yMin) yMin = y
            if (y > yMax) yMax = y
        }
        if (xPositions[0] < 0 || xPositions[dimension - 1] >= w || yMin < 0 || yMax >= h) {
            return Double.NEGATIVE_INFINITY
        }
        val centerValues = DoubleArray(dimension) { c -> standardDeviation[yPositions[c] * w + xPositions[c]].toDouble() }
        if (isTop) {
            val black = DoubleArray((dimension + 1) / 2)
            val white = DoubleArray(dimension / 2)
            var bi = 0
            var wi = 0
            for (c in 0 until dimension) {
                if (c % 2 == 0) black[bi++] = centerValues[c] else white[wi++] = centerValues[c]
            }
            return mean(black) - mean(white) + 0.5 * percentile(black, 20.0)
        }
        val midpointX = IntArray(dimension - 1) { c -> rint(xLeft + (c + 0.5) * pitch) }
        val midpointY = IntArray(dimension - 1) { c -> rint(yLeft + (c + 0.5) * yDelta / (dimension - 1)) }
        val midpointValues = DoubleArray(dimension - 1) { c -> standardDeviation[midpointY[c] * w + midpointX[c]].toDouble() }
        return mean(centerValues) - mean(midpointValues) + 0.5 * percentile(centerValues, 20.0)
    }

    /** grid._left_border_score：左列中心均值 - 行间中点均值 + 0.5*p20(中心) */
    private fun leftBorderScore(
        standardDeviation: FloatArray,
        w: Int,
        h: Int,
        dimension: Int,
        xTop: Double,
        yTop: Double,
        pitch: Double,
        xDelta: Double,
    ): Double {
        val xPositions = IntArray(dimension) { r -> rint(xTop + r * xDelta / (dimension - 1)) }
        val yPositions = IntArray(dimension) { r -> rint(yTop + r * pitch) }
        var xMin = Int.MAX_VALUE
        var xMax = Int.MIN_VALUE
        for (x in xPositions) {
            if (x < xMin) xMin = x
            if (x > xMax) xMax = x
        }
        if (xMin < 0 || xMax >= w || yPositions[0] < 0 || yPositions[dimension - 1] >= h) {
            return Double.NEGATIVE_INFINITY
        }
        val centerValues = DoubleArray(dimension) { r -> standardDeviation[yPositions[r] * w + xPositions[r]].toDouble() }
        val midpointX = IntArray(dimension - 1) { r -> rint(xTop + (r + 0.5) * xDelta / (dimension - 1)) }
        val midpointY = IntArray(dimension - 1) { r -> rint(yTop + (r + 0.5) * pitch) }
        val midpointValues = DoubleArray(dimension - 1) { r -> standardDeviation[midpointY[r] * w + midpointX[r]].toDouble() }
        return mean(centerValues) - mean(midpointValues) + 0.5 * percentile(centerValues, 20.0)
    }

    /**
     * grid._fit_left_border：4 维粗搜（x_top/x_delta∈±22%宽/4、y_top、pitch∈[h/(18+5), h/17]@1.0）
     * + 细化（±2、±1、±0.4/0.1）。返回 [score, x_top, y_top, pitch, x_delta]。
     */
    private fun fitLeftBorder(
        standardDeviation: FloatArray,
        w: Int,
        h: Int,
        bounds: IntArray,
        dimension: Int,
        control: DpmScanControl? = null,
    ): DoubleArray? {
        val x = bounds[0]
        val y = bounds[1]
        val width = bounds[2]
        val height = bounds[3]
        val xStep = max(2, pyRound(width / 60.0))
        val yStep = max(2, pyRound(height / 60.0))
        val pitchMin = height.toDouble() / (dimension + 5)
        val pitchMax = height.toDouble() / (dimension - 1)
        val xStart = max(0, x - pyRound(width * 0.03))
        val xStop = x + max(2, pyRound(width * 0.12)) + 1
        val yStart = max(0, y - pyRound(height * 0.03))
        val yStop = y + max(2, pyRound(height * 0.12)) + 1
        // 斜视手机视角可能使左边缘倾斜多个模块间距
        val deltaLimit = max(2, pyRound(width * 0.22))

        var best: DoubleArray? = null
        var xTop = xStart
        while (xTop < xStop) {
            if (control.aborted()) return best
            var xDelta = -deltaLimit
            while (xDelta <= deltaLimit) {
                if (control.aborted()) return best
                var yTop = yStart
                while (yTop < yStop) {
                    if (control.aborted()) return best
                    for (pitch in arange(pitchMin, pitchMax + 0.001, 1.0)) {
                        if (control.aborted()) return best
                        val score = leftBorderScore(
                            standardDeviation, w, h, dimension,
                            xTop.toDouble(), yTop.toDouble(), pitch, xDelta.toDouble(),
                        )
                        if (best == null || score > best[0]) {
                            best = doubleArrayOf(score, xTop.toDouble(), yTop.toDouble(), pitch, xDelta.toDouble())
                        }
                    }
                    yTop += yStep
                }
                xDelta += 4
            }
            xTop += xStep
        }

        if (best == null) return null

        var refined = best!!
        var rx = best[1] - 2
        while (rx <= best[1] + 2.01) {
            if (control.aborted()) return refined
            var rd = best[4] - 1
            while (rd <= best[4] + 1.01) {
                if (control.aborted()) return refined
                var ry = best[2] - 2
                while (ry <= best[2] + 2.01) {
                    if (control.aborted()) return refined
                    var rp = best[3] - 0.4
                    while (rp <= best[3] + 0.401) {
                        if (control.aborted()) return refined
                        val score = leftBorderScore(
                            standardDeviation, w, h, dimension,
                            rx, ry, rp, rd,
                        )
                        if (score > refined[0]) {
                            refined = doubleArrayOf(score, rx, ry, rp, rd)
                        }
                        rp += 0.1
                    }
                    ry += 1.0
                }
                rd += 1.0
            }
            rx += 1.0
        }
        return refined
    }

    /**
     * grid._fit_hough_left_borders：box 内 std>20 掩码 → HoughLinesP(π/720, 20,
     * minLen=0.35h, maxGap=0.06h) → 竖线过滤 → 沿直线 4 维搜索 + 细化 → 去重 top[limit]。
     */
    private fun fitHoughLeftBorders(
        standardDeviation: FloatArray,
        w: Int,
        h: Int,
        bounds: IntArray,
        dimension: Int,
        limit: Int = 4,
        control: DpmScanControl? = null,
    ): List<DoubleArray> {
        val x = bounds[0]
        val y = bounds[1]
        val width = bounds[2]
        val height = bounds[3]
        if (control.aborted()) return emptyList()
        val mask = Mat(h, w, CvType.CV_8UC1)
        val maskBytes = ByteArray(w * h)
        for (yy in y until y + height) {
            var off = yy * w + x
            for (xx in x until x + width) {
                if (standardDeviation[yy * w + xx] > 20f) maskBytes[off] = 255.toByte()
                off++
            }
        }
        mask.put(0, 0, maskBytes)
        val lines = MatOfInt4()
        try {
            Imgproc.HoughLinesP(
                mask, lines,
                1.0, Math.PI / 720.0,
                20,
                max(20, pyRound(height * 0.35)).toDouble(),
                max(5, pyRound(height * 0.06)).toDouble(),
            )
        } finally {
            mask.release()
        }
        if (lines.rows() == 0) {
            lines.release()
            return emptyList()
        }
        val raw = lines.toArray()
        lines.release()

        val lineCandidates = ArrayList<Pair<Double, IntArray>>()
        var i = 0
        while (i + 3 < raw.size) {
            val x1 = raw[i]; val y1 = raw[i + 1]; val x2 = raw[i + 2]; val y2 = raw[i + 3]
            i += 4
            val deltaX = x2 - x1
            val deltaY = y2 - y1
            val verticalLength = abs(deltaY)
            if (verticalLength < height * 0.50) continue
            if (abs(deltaX) > verticalLength * 0.35) continue
            if ((x1 + x2) / 2 > x + width * 0.35) continue
            lineCandidates.add(verticalLength.toDouble() to intArrayOf(x1, y1, x2, y2))
        }

        val fitted = ArrayList<DoubleArray>()
        for ((verticalLength, line) in lineCandidates.sortedByDescending { it.first }.take(16)) {
            if (control.aborted()) break
            var x1 = line[0]; var y1 = line[1]; var x2 = line[2]; var y2 = line[3]
            if (y2 < y1) {
                val tx = x1; val ty = y1
                x1 = x2; y1 = y2
                x2 = tx; y2 = ty
            }
            val slope = (x2 - x1).toDouble() / (y2 - y1)
            var best: DoubleArray? = null
            for (yTop in arange(max(0.0, y - height * 0.03), y + height * 0.12 + 0.01, 2.0)) {
                if (control.aborted()) break
                val xOnLine = x1 + slope * (yTop - y1)
                for (pitch in arange(height.toDouble() / (dimension + 5), height.toDouble() / (dimension - 1) + 0.001, 0.4)) {
                    if (control.aborted()) break
                    val xDelta = slope * pitch * (dimension - 1)
                    for (xOffset in intArrayOf(-2, 0, 2)) {
                        val xTop = xOnLine + xOffset
                        val score = leftBorderScore(
                            standardDeviation, w, h, dimension,
                            xTop, yTop, pitch, xDelta,
                        )
                        if (best == null || score > best[0]) {
                            best = doubleArrayOf(score, xTop, yTop, pitch, xDelta)
                        }
                    }
                }
                if (control.aborted()) break
            }
            if (best != null) {
                var refined = best!!
                var ry = best[2] - 1
                while (ry <= best[2] + 1.01) {
                    if (control.aborted()) break
                    var rp = best[3] - 0.2
                    while (rp <= best[3] + 0.201) {
                        if (control.aborted()) break
                        val scaledDelta = best[4] * rp / best[3]
                        var rx = best[1] - 1
                        while (rx <= best[1] + 1.01) {
                            val score = leftBorderScore(
                                standardDeviation, w, h, dimension,
                                rx, ry, rp, scaledDelta,
                            )
                            if (score > refined[0]) {
                                refined = doubleArrayOf(score, rx, ry, rp, scaledDelta)
                            }
                            rx += 1.0
                        }
                        rp += 0.1
                    }
                    ry += 1.0
                }
                fitted.add(doubleArrayOf(refined[0], refined[1], refined[2], refined[3], refined[4], verticalLength))
            }
        }

        val selected = ArrayList<DoubleArray>()
        for (entry in fitted.sortedWith(compareByDescending<DoubleArray> { it[0] }.thenByDescending { it[5] })) {
            val candidate = doubleArrayOf(entry[0], entry[1], entry[2], entry[3], entry[4])
            if (selected.any {
                    abs(it[4] - candidate[4]) < 3 && abs(it[1] - candidate[1]) < 3 && abs(it[2] - candidate[2]) < 3
                }) continue
            selected.add(candidate)
            if (selected.size >= limit) break
        }
        return selected
    }

    /**
     * grid._fit_border：顶/底边界拟合。anchor 非空时在锚点 ±anchor_window*pitch 窗口内
     * 逐像素搜索（步长 1）；否则按 box 相对区域粗搜。返回 [score, x_left, y_left, pitch, y_delta]。
     */
    private fun fitBorder(
        standardDeviation: FloatArray,
        w: Int,
        h: Int,
        bounds: IntArray,
        dimension: Int,
        isTop: Boolean,
        anchor: DoubleArray? = null,
        anchorWindowModules: Double = 0.45,
        coordinateRefineRadius: Int = 2,
        control: DpmScanControl? = null,
    ): DoubleArray? {
        val x = bounds[0]
        val y = bounds[1]
        val width = bounds[2]
        val height = bounds[3]
        val xStep = max(2, pyRound(width / 60.0))
        val yStep = max(2, pyRound(height / 60.0))
        val pitchMin = width.toDouble() / (dimension + 5)
        val pitchMax = width.toDouble() / (dimension - 1)
        val xValues: IntProgression
        val yValues: IntProgression
        if (anchor != null) {
            val approximatePitch = (pitchMin + pitchMax) / 2
            val anchorX = anchor[0]
            val anchorY = anchor[1]
            xValues = intProgression(
                max(0, pyRound(anchorX - approximatePitch * anchorWindowModules)),
                pyRound(anchorX + approximatePitch * anchorWindowModules) + 1,
                1,
            )
            yValues = intProgression(
                max(0, pyRound(anchorY - approximatePitch * anchorWindowModules)),
                pyRound(anchorY + approximatePitch * anchorWindowModules) + 1,
                1,
            )
        } else {
            val xStart = x
            val xStop = x + max(2, pyRound(width * 0.16)) + 1
            if (isTop) {
                val yStart = max(0, y - pyRound(height * 0.03))
                val yStop = y + max(2, pyRound(height * 0.16)) + 1
                xValues = intProgression(xStart, xStop, xStep)
                yValues = intProgression(yStart, yStop, yStep)
            } else {
                val yStart = y + pyRound(height * 0.75)
                val yStop = y + height
                xValues = intProgression(xStart, xStop, xStep)
                yValues = intProgression(yStart, yStop, yStep)
            }
        }
        val deltaLimit = max(2, pyRound(height * 0.05))

        var best: DoubleArray? = null
        for (yLeft in yValues) {
            if (control.aborted()) return best
            var yDelta = -deltaLimit
            while (yDelta <= deltaLimit) {
                if (control.aborted()) return best
                for (xLeft in xValues) {
                    if (control.aborted()) return best
                    for (pitch in arange(pitchMin, pitchMax + 0.001, 1.0)) {
                        if (control.aborted()) return best
                        val score = borderScore(
                            standardDeviation, w, h, dimension,
                            xLeft.toDouble(), yLeft.toDouble(), pitch, yDelta.toDouble(), isTop,
                        )
                        if (best == null || score > best[0]) {
                            best = doubleArrayOf(score, xLeft.toDouble(), yLeft.toDouble(), pitch, yDelta.toDouble())
                        }
                    }
                }
                yDelta += 4
            }
        }

        if (best == null) return null

        var refined = best!!
        var ry = best[2] - coordinateRefineRadius
        while (ry <= best[2] + coordinateRefineRadius + 0.01) {
            if (control.aborted()) return refined
            var rd = best[4] - 1
            while (rd <= best[4] + 1.01) {
                if (control.aborted()) return refined
                var rx = best[1] - coordinateRefineRadius
                while (rx <= best[1] + coordinateRefineRadius + 0.01) {
                    if (control.aborted()) return refined
                    var rp = best[3] - 0.4
                    while (rp <= best[3] + 0.401) {
                        if (control.aborted()) return refined
                        val score = borderScore(
                            standardDeviation, w, h, dimension,
                            rx, ry, rp, rd, isTop,
                        )
                        if (score > refined[0]) {
                            refined = doubleArrayOf(score, rx, ry, rp, rd)
                        }
                        rp += 0.1
                    }
                    rx += 1.0
                }
                rd += 1.0
            }
            ry += 1.0
        }
        return refined
    }

    /**
     * grid._fit_grid_candidate_from_left：由左边界推导顶/底锚点 → _fit_border(顶/底)
     * → 组合 4 角点。left_score≥35 时锚点窗口 0.05 模块（高置信锁定），否则 0.45。
     */
    private fun fitGridCandidateFromLeft(
        standardDeviation: FloatArray,
        w: Int,
        h: Int,
        bounds: IntArray,
        dimension: Int,
        left: DoubleArray,
        control: DpmScanControl? = null,
    ): Pair<DoubleArray, Double>? {
        if (control.aborted()) return null
        val leftScore = left[0]
        val leftX = left[1]
        val leftY = left[2]
        val leftPitch = left[3]
        val leftDelta = left[4]
        val lastModule = dimension - 1
        val topAnchor = doubleArrayOf(leftX, leftY)
        val bottomAnchor = doubleArrayOf(leftX + leftDelta, leftY + lastModule * leftPitch)
        val anchorWindow = if (leftScore >= 35) 0.05 else 0.45
        val coordinateRefineRadius = if (leftScore >= 35) 0 else 2
        val top = fitBorder(
            standardDeviation, w, h, bounds, dimension, isTop = true,
            anchor = topAnchor, anchorWindowModules = anchorWindow,
            coordinateRefineRadius = coordinateRefineRadius, control = control,
        ) ?: return null
        if (control.aborted()) return null
        val bottom = fitBorder(
            standardDeviation, w, h, bounds, dimension, isTop = false,
            anchor = bottomAnchor, anchorWindowModules = anchorWindow,
            coordinateRefineRadius = coordinateRefineRadius, control = control,
        ) ?: return null
        val corners = doubleArrayOf(
            top[1], top[2],
            top[1] + lastModule * top[3], top[2] + top[4],
            bottom[1], bottom[2],
            bottom[1] + lastModule * bottom[3], bottom[2] + bottom[4],
        )
        return corners to (leftScore + top[0] + bottom[0])
    }

    /** grid._high_resolution_corner_refinement（仅 scale<0.5 时追加） */
    private fun highResolutionCornerRefinement(corners: DoubleArray, dimension: Int): DoubleArray {
        val lastModule = dimension - 1
        val topModuleX = (corners[2] - corners[0]) / lastModule
        val topModuleY = (corners[3] - corners[1]) / lastModule
        val leftModuleX = (corners[4] - corners[0]) / lastModule
        val leftModuleY = (corners[5] - corners[1]) / lastModule
        val rightModuleX = (corners[6] - corners[2]) / lastModule
        val rightModuleY = (corners[7] - corners[3]) / lastModule
        val refined = corners.copyOf()
        refined[0] += 0.08 * topModuleX + 0.11 * leftModuleX
        refined[1] += 0.08 * topModuleY + 0.11 * leftModuleY
        refined[2] += -0.28 * topModuleX + 0.10 * rightModuleX
        refined[3] += -0.28 * topModuleY + 0.10 * rightModuleY
        refined[4] += -0.22 * topModuleX - 0.35 * leftModuleX
        refined[5] += -0.22 * topModuleY - 0.35 * leftModuleY
        refined[6] += -0.25 * topModuleX - 0.35 * rightModuleX
        refined[7] += -0.25 * topModuleY - 0.35 * rightModuleY
        return refined
    }

    /** grid._axis_corner_refinement */
    private fun axisCornerRefinement(corners: DoubleArray, dimension: Int): DoubleArray {
        val lastModule = dimension - 1
        val topModuleX = (corners[2] - corners[0]) / lastModule
        val topModuleY = (corners[3] - corners[1]) / lastModule
        val bottomModuleX = (corners[6] - corners[4]) / lastModule
        val bottomModuleY = (corners[7] - corners[5]) / lastModule
        val leftModuleX = (corners[4] - corners[0]) / lastModule
        val leftModuleY = (corners[5] - corners[1]) / lastModule
        val rightModuleX = (corners[6] - corners[2]) / lastModule
        val rightModuleY = (corners[7] - corners[3]) / lastModule
        val refined = corners.copyOf()
        refined[0] += 0.80 * topModuleX - 0.75 * leftModuleX
        refined[1] += 0.80 * topModuleY - 0.75 * leftModuleY
        refined[2] += -0.10 * topModuleX - 0.35 * rightModuleX
        refined[3] += -0.10 * topModuleY - 0.35 * rightModuleY
        refined[4] += 0.45 * bottomModuleX
        refined[5] += 0.45 * bottomModuleY
        refined[6] += 0.15 * bottomModuleX - 0.35 * rightModuleX
        refined[7] += 0.15 * bottomModuleY - 0.35 * rightModuleY
        return refined
    }

    /** grid._axis_vertical_refinement */
    private fun axisVerticalRefinement(corners: DoubleArray, dimension: Int): DoubleArray {
        val lastModule = dimension - 1
        val leftModuleX = (corners[4] - corners[0]) / lastModule
        val leftModuleY = (corners[5] - corners[1]) / lastModule
        val rightModuleX = (corners[6] - corners[2]) / lastModule
        val rightModuleY = (corners[7] - corners[3]) / lastModule
        val refined = corners.copyOf()
        refined[0] -= 0.50 * leftModuleX
        refined[1] -= 0.50 * leftModuleY
        refined[2] -= 0.50 * rightModuleX
        refined[3] -= 0.50 * rightModuleY
        refined[4] -= 0.35 * leftModuleX
        refined[5] -= 0.35 * leftModuleY
        refined[6] -= 0.35 * rightModuleX
        refined[7] -= 0.35 * rightModuleY
        return refined
    }

    /** grid._additional_axis_refinements（5 个变体） */
    private fun additionalAxisRefinements(corners: DoubleArray, dimension: Int): List<DoubleArray> {
        val lastModule = dimension - 1
        val topX = (corners[2] - corners[0]) / lastModule
        val topY = (corners[3] - corners[1]) / lastModule
        val bottomX = (corners[6] - corners[4]) / lastModule
        val bottomY = (corners[7] - corners[5]) / lastModule
        val leftX = (corners[4] - corners[0]) / lastModule
        val leftY = (corners[5] - corners[1]) / lastModule
        val rightX = (corners[6] - corners[2]) / lastModule
        val rightY = (corners[7] - corners[3]) / lastModule
        val variants = ArrayList<DoubleArray>(5)

        val first = corners.copyOf()
        first[0] += 0.10 * topX - 0.40 * leftX
        first[1] += 0.10 * topY - 0.40 * leftY
        first[2] += 0.30 * topX - 0.40 * rightX
        first[3] += 0.30 * topY - 0.40 * rightY
        first[4] += 0.50 * bottomX
        first[5] += 0.50 * bottomY
        first[6] += 0.30 * bottomX - 0.50 * rightX
        first[7] += 0.30 * bottomY - 0.50 * rightY
        variants.add(first)

        val second = corners.copyOf()
        second[0] += -0.30 * topX - 0.65 * leftX
        second[1] += -0.30 * topY - 0.65 * leftY
        second[2] += -0.60 * topX - 0.25 * rightX
        second[3] += -0.60 * topY - 0.25 * rightY
        second[4] -= 0.90 * bottomX
        second[5] -= 0.90 * bottomY
        second[6] += -0.70 * bottomX - 0.45 * rightX
        second[7] += -0.70 * bottomY - 0.45 * rightY
        variants.add(second)

        val third = corners.copyOf()
        third[0] += -0.50 * topX - 0.60 * leftX
        third[1] += -0.50 * topY - 0.60 * leftY
        third[2] += -0.40 * topX + 0.15 * rightX
        third[3] += -0.40 * topY + 0.15 * rightY
        third[4] += -2.70 * bottomX - 0.50 * leftX
        third[5] += -2.70 * bottomY - 0.50 * leftY
        third[6] += -2.70 * bottomX + 0.65 * rightX
        third[7] += -2.70 * bottomY + 0.65 * rightY
        variants.add(third)

        val fourth = corners.copyOf()
        fourth[0] += -0.30 * topX - 0.50 * leftX
        fourth[1] += -0.30 * topY - 0.50 * leftY
        fourth[2] += -0.50 * topX - 0.65 * rightX
        fourth[3] += -0.50 * topY - 0.65 * rightY
        fourth[4] += 0.45 * bottomX + 2.10 * leftX
        fourth[5] += 0.45 * bottomY + 2.10 * leftY
        fourth[6] += 0.45 * bottomX + 2.40 * rightX
        fourth[7] += 0.45 * bottomY + 2.40 * rightY
        variants.add(fourth)

        val fifth = corners.copyOf()
        fifth[0] += 0.30 * leftX
        fifth[1] += 0.30 * leftY
        fifth[2] -= 0.45 * rightX
        fifth[3] -= 0.45 * rightY
        fifth[4] += 0.55 * bottomX - 0.55 * leftX
        fifth[5] += 0.55 * bottomY - 0.55 * leftY
        fifth[6] += 1.75 * bottomX + 0.15 * rightX
        fifth[7] += 1.75 * bottomY + 0.15 * rightY
        variants.add(fifth)

        return variants
    }

    /**
     * grid.find_grid_candidates：scale=min(1, 800/max边) INTER_AREA → std 图（所有尺寸
     * 复用同一张，不重复计算）→ _candidate_boxes(≤3) → 每 box × 每 dimension：
     * _fit_left_border [+ box0: _fit_hough_left_borders(3)] → _fit_grid_candidate_from_left
     * → 变体（phase_shift{0,-1,+1} + axis + axis_vertical + 5 additional + scale<0.5 时
     * high_resolution）→ 按分数降序后**按 dimension 分组保留配额**（每尺寸 [perDimension]
     * 条，防单一尺寸占满候选）→ 尺寸名次交错返回（[interleave]：各尺寸第 1 名 → 第 2 名
     * → ……，AUTO 下 18×18 不独占时间预算）。AUTO 每尺寸 8 条（共 24）、固定模式该尺寸
     * 24 条，总量恒 ≤24。
     */
    private fun findGridCandidates(
        image: ByteArray,
        w: Int,
        h: Int,
        dimensions: IntArray = intArrayOf(18),
        perDimension: Int = 24,
        control: DpmScanControl? = null,
    ): List<GridCandidate> {
        val scale = min(1.0, 800.0 / max(w, h))
        val (working, ww, wh) = if (scale < 1.0) resizeArea(image, w, h, scale) else Triple(image, w, h)
        val std = standardDeviationMap(working, ww, wh)
        val candidates = ArrayList<GridCandidate>()
        val boxes = candidateBoxes(std, ww, wh, control)
        for (boxIndex in boxes.indices) {
            if (control.aborted()) break
            val (bounds, boxScore) = boxes[boxIndex]
            for (dimension in dimensions) {
                if (control.aborted()) break
                val leftCandidates = ArrayList<DoubleArray>()
                fitLeftBorder(std, ww, wh, bounds, dimension, control)?.let { leftCandidates.add(it) }
                if (boxIndex == 0) {
                    leftCandidates.addAll(fitHoughLeftBorders(std, ww, wh, bounds, dimension, limit = 3, control = control))
                }
                for (left in leftCandidates) {
                    if (control.aborted()) break
                    val fitted = fitGridCandidateFromLeft(std, ww, wh, bounds, dimension, left, control) ?: continue
                    val workingCorners = fitted.first
                    val gridScore = fitted.second
                    val originalBounds = intArrayOf(
                        pyRound(bounds[0] / scale),
                        pyRound(bounds[1] / scale),
                        pyRound(bounds[2] / scale),
                        pyRound(bounds[3] / scale),
                    )
                    val topModuleX = (workingCorners[2] - workingCorners[0]) / (dimension - 1)
                    val topModuleY = (workingCorners[3] - workingCorners[1]) / (dimension - 1)
                    for (phaseShift in intArrayOf(0, -1, 1)) {
                        if (control.aborted()) break
                        val shifted = workingCorners.copyOf()
                        shifted[0] += phaseShift * topModuleX
                        shifted[1] += phaseShift * topModuleY
                        shifted[2] += phaseShift * topModuleX
                        shifted[3] += phaseShift * topModuleY
                        candidates.add(
                            GridCandidate(
                                scaleUpCorners(shifted, scale), dimension,
                                gridScore + ln(1 + boxScore) - abs(phaseShift) * 0.01,
                                originalBounds,
                            )
                        )
                    }
                    candidates.add(
                        GridCandidate(
                            scaleUpCorners(axisCornerRefinement(workingCorners, dimension), scale), dimension,
                            gridScore + ln(1 + boxScore) - 0.005,
                            originalBounds,
                        )
                    )
                    candidates.add(
                        GridCandidate(
                            scaleUpCorners(axisVerticalRefinement(workingCorners, dimension), scale), dimension,
                            gridScore + ln(1 + boxScore) - 0.006,
                            originalBounds,
                        )
                    )
                    for ((refinementIndex, refinement) in additionalAxisRefinements(workingCorners, dimension).withIndex()) {
                        candidates.add(
                            GridCandidate(
                                scaleUpCorners(refinement, scale), dimension,
                                gridScore + ln(1 + boxScore) - 0.020 - refinementIndex * 0.001,
                                originalBounds,
                            )
                        )
                    }
                    if (scale < 0.5) {
                        candidates.add(
                            GridCandidate(
                                scaleUpCorners(highResolutionCornerRefinement(workingCorners, dimension), scale), dimension,
                                gridScore + ln(1 + boxScore) - 0.005,
                                originalBounds,
                            )
                        )
                    }
                }
            }
        }
        return interleave(
            capPerDimension(candidates.sortedByDescending { it.score }, { it.dimension }, perDimension)
        )
    }

    /**
     * grid.find_rotated_grid_candidates：std 图 → _rotated_rectangle_candidates →
     * 缩放回原图坐标 → 按 dimension 分组保留配额（每尺寸 [perDimension] 条）→ 尺寸名次
     * 交错返回。AUTO 每尺寸 4 条（共 12）、固定模式该尺寸 12 条，总量恒 ≤12。
     */
    private fun findRotatedGridCandidates(
        image: ByteArray,
        w: Int,
        h: Int,
        dimensions: IntArray = intArrayOf(18),
        perDimension: Int = 12,
        control: DpmScanControl? = null,
    ): List<GridCandidate> {
        val scale = min(1.0, 800.0 / max(w, h))
        val (working, ww, wh) = if (scale < 1.0) resizeArea(image, w, h, scale) else Triple(image, w, h)
        val std = standardDeviationMap(working, ww, wh)
        val candidates = ArrayList<GridCandidate>()
        for (dimension in dimensions) {
            if (control.aborted()) break
            for ((workingCorners, bounds, score) in rotatedRectangleCandidates(std, ww, wh, dimension, control)) {
                candidates.add(
                    GridCandidate(
                        scaleUpCorners(workingCorners, scale), dimension, score,
                        intArrayOf(
                            pyRound(bounds[0] / scale),
                            pyRound(bounds[1] / scale),
                            pyRound(bounds[2] / scale),
                            pyRound(bounds[3] / scale),
                        ),
                    )
                )
            }
        }
        return interleave(
            capPerDimension(candidates.sortedByDescending { it.score }, { it.dimension }, perDimension)
        )
    }

    /** 工作图坐标 → 原图坐标（÷scale，与 Python corners/scale 一致） */
    private fun scaleUpCorners(corners: DoubleArray, scale: Double): DoubleArray {
        if (scale == 1.0) return corners.copyOf()
        val out = DoubleArray(corners.size)
        for (i in corners.indices) out[i] = corners[i] / scale
        return out
    }

    /** INTER_AREA 缩放（fx=fy=scale）：与 cv2.resize(None, fx, fy, INTER_AREA) 一致 */
    private fun resizeArea(src: ByteArray, w: Int, h: Int, scale: Double): Triple<ByteArray, Int, Int> {
        val srcMat = Mat(h, w, CvType.CV_8UC1)
        srcMat.put(0, 0, src)
        val dst = Mat()
        try {
            Imgproc.resize(srcMat, dst, Size(), scale, scale, Imgproc.INTER_AREA)
        } finally {
            srcMat.release()
        }
        val dw = dst.cols()
        val dh = dst.rows()
        val out = ByteArray(dw * dh)
        dst.get(0, 0, out)
        dst.release()
        return Triple(out, dw, dh)
    }

    // ================================================================ localize.py

    /** localize._positions：{0, 中点, 末端} 去重排序 */
    private fun positions(length: Int, size: Int): IntArray {
        val a = 0
        val b = max(0, (length - size) / 2)
        val c = max(0, length - size)
        val set = sortedSetOf(a, b, c)
        return set.toIntArray()
    }

    /** localize.generate_regions：全图 + {0.90, 0.70, 0.55}×{左,中,右} 方块 */
    private fun generateRegions(image: ByteArray, w: Int, h: Int): List<ImageRegion> {
        val regions = ArrayList<ImageRegion>()
        regions.add(ImageRegion("full", 0, 0, w, h, image))
        val shortestSide = min(h, w)
        val seen = HashSet<String>()
        seen.add("0,0,$w,$h")
        for (ratio in doubleArrayOf(0.90, 0.70, 0.55)) {
            val size = min(shortestSide, max(64, pyRound(shortestSide * ratio)))
            for (y in positions(h, size)) {
                for (x in positions(w, size)) {
                    if (!seen.add("$x,$y,$size")) continue
                    val sub = ByteArray(size * size)
                    for (yy in 0 until size) {
                        System.arraycopy(image, (y + yy) * w + x, sub, yy * size, size)
                    }
                    regions.add(ImageRegion("tile_%.2f_%d_%d".format(ratio, x, y), x, y, size, size, sub))
                }
            }
        }
        return regions
    }

    // ================================================================ preprocess.py

    /** preprocess._resize_square：h<640 → CUBIC，否则 AREA（Python 判断 image.shape[0]=行数=高度） */
    private fun resizeSquare(image: ByteArray, w: Int, h: Int, targetSize: Int = 640): ByteArray {
        val interpolation = if (h < targetSize) Imgproc.INTER_CUBIC else Imgproc.INTER_AREA
        val srcMat = Mat(h, w, CvType.CV_8UC1)
        srcMat.put(0, 0, image)
        val dst = Mat()
        try {
            Imgproc.resize(srcMat, dst, Size(targetSize.toDouble(), targetSize.toDouble()), 0.0, 0.0, interpolation)
        } finally {
            srcMat.release()
        }
        val out = ByteArray(targetSize * targetSize)
        dst.get(0, 0, out)
        dst.release()
        return out
    }

    /** preprocess._with_quiet_zone：24px 白边 */
    private fun withQuietZone(image: ByteArray, w: Int, h: Int, border: Int = 24): ByteArray {
        val nw = w + 2 * border
        val nh = h + 2 * border
        val out = ByteArray(nw * nh)
        Arrays.fill(out, 255.toByte())
        for (y in 0 until h) {
            System.arraycopy(image, y * w, out, (y + border) * nw + border, w)
        }
        return out
    }

    /** CLAHE(clipLimit=3.0, 8×8) */
    private fun applyClahe(image: ByteArray, w: Int, h: Int): ByteArray {
        val src = Mat(h, w, CvType.CV_8UC1)
        src.put(0, 0, image)
        val dst = Mat()
        try {
            Imgproc.createCLAHE(3.0, Size(8.0, 8.0)).apply(src, dst)
        } finally {
            src.release()
        }
        val out = ByteArray(w * h)
        dst.get(0, 0, out)
        dst.release()
        return out
    }

    /** MORPH_GRADIENT(kernel) + GaussianBlur(3×3, 0) */
    private fun morphologyGradientBlur(image: ByteArray, w: Int, h: Int, kernel: Mat): ByteArray {
        val src = Mat(h, w, CvType.CV_8UC1)
        src.put(0, 0, image)
        val grad = Mat()
        val dst = Mat()
        try {
            Imgproc.morphologyEx(src, grad, Imgproc.MORPH_GRADIENT, kernel)
            Imgproc.GaussianBlur(grad, dst, Size(3.0, 3.0), 0.0)
        } finally {
            grad.release()
            src.release()
        }
        val out = ByteArray(w * h)
        dst.get(0, 0, out)
        dst.release()
        return out
    }

    /** Otsu 反色二值化：THRESH_BINARY_INV | THRESH_OTSU */
    private fun otsuBinaryInv(image: ByteArray, w: Int, h: Int): ByteArray {
        val src = Mat(h, w, CvType.CV_8UC1)
        src.put(0, 0, image)
        val dst = Mat()
        try {
            Imgproc.threshold(src, dst, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
        } finally {
            src.release()
        }
        val out = ByteArray(w * h)
        dst.get(0, 0, out)
        dst.release()
        return out
    }

    /** adaptiveThreshold(GAUSSIAN_C, 41, C=4) */
    private fun adaptiveThresholdGaussian(image: ByteArray, w: Int, h: Int): ByteArray {
        val src = Mat(h, w, CvType.CV_8UC1)
        src.put(0, 0, image)
        val dst = Mat()
        try {
            Imgproc.adaptiveThreshold(src, dst, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 41, 4.0)
        } finally {
            src.release()
        }
        val out = ByteArray(w * h)
        dst.get(0, 0, out)
        dst.release()
        return out
    }

    /**
     * preprocess.generate_variants：resize 640×640 → CLAHE → 7 变体：
     * clahe+24 白边 / MORPH_GRADIENT k={5,9,13}→Blur→255-lr / 同 Otsu 反色 / adaptive。
     */
    private fun generateVariants(image: ByteArray, w: Int, h: Int, control: DpmScanControl? = null): List<ImageVariant> {
        val resized = resizeSquare(image, w, h)
        val clahe = applyClahe(resized, 640, 640)
        val variants = ArrayList<ImageVariant>(7)
        variants.add(ImageVariant("clahe", 688, 688, withQuietZone(clahe, 640, 640)))

        for (kernelSize in intArrayOf(5, 9, 13)) {
            if (control.aborted()) break
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(kernelSize.toDouble(), kernelSize.toDouble()))
            try {
                val localRange = morphologyGradientBlur(clahe, 640, 640, kernel)
                variants.add(ImageVariant("range_$kernelSize", 688, 688, withQuietZone(invertBytes(localRange), 640, 640)))
                variants.add(ImageVariant("range_otsu_$kernelSize", 688, 688, withQuietZone(otsuBinaryInv(localRange, 640, 640), 640, 640)))
            } finally {
                kernel.release()
            }
        }

        if (!control.aborted()) {
            variants.add(ImageVariant("adaptive", 688, 688, withQuietZone(adaptiveThresholdGaussian(clahe, 640, 640), 640, 640)))
        }
        return variants
    }

    // ================================================================ 工具

    /** Python round() 语义（银行家舍入 round-half-to-even） */
    private fun pyRound(v: Double): Int = rint(v).toInt()

    /** numpy np.rint 语义（round-half-to-even），坐标取整 */
    private fun rint(v: Double): Int = Math.rint(v).toInt()

    /** numpy np.arange(start, stop, step) 语义：start + k*step < stop */
    private fun arange(start: Double, stop: Double, step: Double): DoubleArray {
        if (step <= 0.0 || stop <= start) return DoubleArray(0)
        val n = ceil((stop - start) / step).toInt()
        return DoubleArray(n) { start + it * step }
    }

    /** Python range(start, stop, step) 语义（Int，步长 >0）；start >= stop 时为空 */
    private fun intProgression(start: Int, stop: Int, step: Int): IntProgression =
        if (start < stop && step > 0) (start until stop).step(step) else (0 until 0).step(1)

    /** 均值 */
    private fun mean(a: DoubleArray): Double {
        var s = 0.0
        for (v in a) s += v
        return s / a.size
    }

    /** 中位数（numpy np.median 语义） */
    private fun median(a: DoubleArray): Double {
        val s = a.copyOf()
        s.sort()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** 线性插值百分位（numpy np.percentile 默认 linear 语义） */
    private fun percentile(a: DoubleArray, p: Double): Double {
        val s = a.copyOf()
        s.sort()
        val idx = p / 100.0 * (s.size - 1)
        val lo = idx.toInt()
        val frac = idx - lo
        return if (lo + 1 < s.size) s[lo] * (1 - frac) + s[lo + 1] * frac else s[lo]
    }

    /** 百分位（Float 数组输入，numpy percentile 语义） */
    private fun percentileFloat(a: FloatArray, p: Double): Float {
        val s = a.copyOf()
        s.sort()
        val idx = p / 100.0 * (s.size - 1)
        val lo = idx.toInt()
        val frac = idx - lo
        return if (lo + 1 < s.size) (s[lo] * (1 - frac) + s[lo + 1] * frac).toFloat() else s[lo]
    }

    /** 浮点 box 的整数边界（cv2.boundingRect 对 float32 点集语义：floor(min) 到 ceil(max)） */
    private fun floatBounds(p: DoubleArray): IntArray {
        val minX = floor(minOf(p[0], p[2], p[4], p[6])).toInt()
        val maxX = ceil(maxOf(p[0], p[2], p[4], p[6])).toInt()
        val minY = floor(minOf(p[1], p[3], p[5], p[7])).toInt()
        val maxY = ceil(maxOf(p[1], p[3], p[5], p[7])).toInt()
        return intArrayOf(minX, minY, maxX - minX, maxY - minY)
    }

    /** numpy rot90 语义图像旋转（rot=1 逆时针 90°）：rot 1/3 时宽高互换 */
    private fun rotateGray(src: ByteArray, w: Int, h: Int, rot: Int): ByteArray {
        return when (rot) {
            1 -> {
                val out = ByteArray(w * h)
                for (y in 0 until w) {
                    for (x in 0 until h) {
                        out[y * h + x] = src[x * w + (w - 1 - y)]
                    }
                }
                out
            }
            2 -> {
                val out = ByteArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        out[y * w + x] = src[(h - 1 - y) * w + (w - 1 - x)]
                    }
                }
                out
            }
            else -> {
                val out = ByteArray(w * h)
                for (y in 0 until w) {
                    for (x in 0 until h) {
                        out[y * h + x] = src[(h - 1 - x) * w + y]
                    }
                }
                out
            }
        }
    }
}
