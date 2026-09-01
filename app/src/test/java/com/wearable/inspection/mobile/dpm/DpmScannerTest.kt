package com.wearable.inspection.mobile.dpm

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.datamatrix.DataMatrixWriter
import com.google.zxing.datamatrix.encoder.SymbolShapeHint
import com.wearable.inspection.mobile.vision.OpenCvTestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.Collections
import java.util.HashSet
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ImportedDpmScanner]（Python 参考实现 DPM_Scanner_Source_20260820 的 Kotlin 移植）
 * 全链路验证（纯 JVM：OpenCV 桌面版 + Java ZXing）。
 *
 * 1. 合成点阵码场景：1:1 照抄源 tests/synthetic_dpm.py 的 make_dot_peen_scene
 *    （底 142 + 高斯噪声 N(0,2.0) seed 20260820 + 水平渐变 -12..14 + 圆点双圈模块
 *    （暗 82 / 亮 204，半径 3）+ GaussianBlur(3×3, 0.7)）→ 网格定位-解码全链路应解出。
 * 2. 真实帧回归：新批次用户指定帧（Python 源算法实测解出 L0549630AE092212080057）：
 *    - input_400x533_1787219111940.png（用户确认"很清晰"）
 *    - input_400x533_1787219112056.png（960 帧，Python grid_18 r5 解出）
 * 3. 16×16 / 18×18 / 20×20 精确尺寸合成：生成器用 FORCE_SQUARE 编码并断言实际
 *    矩阵就是目标尺寸；AUTO 模式三尺寸均可解出且 [ScanResult.dimension] 命中；
 *    固定模式只解自己的尺寸；错误固定尺寸不能伪装成功；反色/低对比度点阵可解。
 */
class DpmScannerTest {

    @Before
    fun setUp() = OpenCvTestSupport.loadNative()

    // ---------------------------------------------------------------- 合成场景

    /**
     * 照抄源 tests/synthetic_dpm.py make_dot_peen_scene（canvas 400×533）。
     * [forceSquare]：编码时强制方形符号（用于精确尺寸场景）；[inverted]：亮点暗圈
     * 反色点阵（std 图与极性无关，应同样可解）；dotMain/dotAccent/background 供低对比度。
     */
    private fun makeDotPeenScene(
        payload: String = "SYNTH-DPM-001",
        canvasWidth: Int = 400,
        canvasHeight: Int = 533,
        originX: Int = 70,
        originY: Int = 230,
        modulePx: Int = 14,
        forceSquare: Boolean = false,
        inverted: Boolean = false,
        dotMain: Double? = null,
        dotAccent: Double? = null,
        background: Float? = null,
    ): ByteArray {
        val hints = mutableMapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to 0)
        if (forceSquare) hints[EncodeHintType.DATA_MATRIX_SHAPE] = SymbolShapeHint.FORCE_SQUARE
        val matrix = DataMatrixWriter().encode(payload, BarcodeFormat.DATA_MATRIX, 18, 18, hints)
        val bg = background ?: 142f
        val main = dotMain ?: if (inverted) 204.0 else 82.0
        val accent = dotAccent ?: if (inverted) 82.0 else 204.0
        val rng = Random(20260820)
        // 底 142 + 噪声 + 水平渐变（np.linspace(-12, 14, width)）
        val grad = FloatArray(canvasWidth) { -12f + it * 26f / (canvasWidth - 1) }
        val mat = Mat(canvasHeight, canvasWidth, CvType.CV_32FC1)
        val base = FloatArray(canvasWidth * canvasHeight)
        for (y in 0 until canvasHeight) {
            for (x in 0 until canvasWidth) {
                base[y * canvasWidth + x] = bg + (rng.nextGaussian() * 2.0).toFloat() + grad[x]
            }
        }
        mat.put(0, 0, base)
        try {
            // 圆点模块：暗/亮双圈（radius = max(2, module_px//4)）
            val radius = maxOf(2, modulePx / 4)
            for (my in 0 until matrix.height) {
                for (mx in 0 until matrix.width) {
                    if (!matrix.get(mx, my)) continue
                    val cx = originX + mx * modulePx + modulePx / 2
                    val cy = originY + my * modulePx + modulePx / 2
                    Imgproc.circle(mat, Point((cx - 2).toDouble(), (cy - 2).toDouble()), radius, Scalar(main), -1)
                    Imgproc.circle(mat, Point((cx + 2).toDouble(), (cy + 2).toDouble()), radius, Scalar(accent), -1)
                }
            }
            val blur = Mat()
            try {
                Imgproc.GaussianBlur(mat, blur, Size(3.0, 3.0), 0.7)
                val f = FloatArray(canvasWidth * canvasHeight)
                blur.get(0, 0, f)
                val out = ByteArray(canvasWidth * canvasHeight)
                for (i in f.indices) out[i] = f[i].toInt().coerceIn(0, 255).toByte()
                return out
            } finally {
                blur.release()
            }
        } finally {
            mat.release()
        }
    }

    @Test
    fun syntheticDotPeenSceneDecodes() {
        val bytes = makeDotPeenScene()
        val t0 = System.nanoTime()
        val result = ImportedDpmScanner.scanImage(bytes, 400, 533)
        val ms = (System.nanoTime() - t0) / 1e6
        println("synthetic: ${result?.regionName}/${result?.variantName} dim=${result?.dimension} in ${ms}ms")
        assertEquals("SYNTH-DPM-001", result?.text)
    }

    // ---------------------------------------------------------------- 真实帧回归

    /** 读 D 盘 dump 帧（IMREAD_GRAYSCALE）为亮度字节 */
    private fun readFrame(path: String): Pair<ByteArray, Pair<Int, Int>> {
        val mat = Imgcodecs.imread(path, Imgcodecs.IMREAD_GRAYSCALE)
        val w = mat.cols()
        val h = mat.rows()
        val bytes = ByteArray(w * h)
        mat.get(0, 0, bytes)
        mat.release()
        return bytes to (w to h)
    }

    @Test
    fun probeRootDumpCandidatesF23ToF37() {
        val dir = File("../dpm_dump")
        assumeTrue("root dpm_dump is missing, skipping", dir.isDirectory)
        val files = dir.listFiles().orEmpty()
            .filter { file ->
                val frame = Regex("_f(\\d+)\\.png$").find(file.name)
                    ?.groupValues?.get(1)?.toIntOrNull()
                file.name.startsWith("pre_") && frame != null && frame in 23..37
            }
            .sortedBy { it.name }
        for (file in files) {
            val (bytes, dims) = readFrame(file.absolutePath)
            val t0 = System.nanoTime()
            val result = ImportedDpmScanner.decodeDotGridCandidate(bytes, dims.first, dims.second)
            val ms = (System.nanoTime() - t0) / 1e6
            println("root-dump ${file.name}: ${result?.text} dim=${result?.dimension} in ${ms}ms")
        }
    }

    @Test
    fun capturedF26Decodes() {
        val file = File("../device_acceptance/dpm_preprocess_samples_20260824/f26_analysis/pre_s2_s2-bright-otsu-dilate_400x618_f26.png")
        assumeTrue("f26 regression frame is missing, skipping", file.exists())
        val (bytes, dims) = readFrame(file.absolutePath)
        val t0 = System.nanoTime()
        val result = ImportedDpmScanner.decodeDotGridCandidate(bytes, dims.first, dims.second)
        val ms = (System.nanoTime() - t0) / 1e6
        println("f26 dot grid: ${result?.text} dim=${result?.dimension} in ${ms}ms")
        assertEquals("M968942280224B169AH005023044710", result?.text)
        assertEquals(18, result?.dimension)
    }

    @Test
    fun userSpecifiedFrameDecodes() {
        val file = File("D:/dpm_dump_20260820/cache/input_400x533_1787219111940.png")
        assumeTrue("新批次用户指定帧不存在,跳过", file.exists())
        val (bytes, dims) = readFrame(file.absolutePath)
        val t0 = System.nanoTime()
        val result = ImportedDpmScanner.scanImage(bytes, dims.first, dims.second)
        val ms = (System.nanoTime() - t0) / 1e6
        println("1787219111940: ${result?.regionName}/${result?.variantName} dim=${result?.dimension} in ${ms}ms")
        assertEquals("L0549630AE092212080057", result?.text)
    }

    @Test
    fun frame960Decodes() {
        val file = File("D:/dpm_dump_20260820/cache/input_960x1280_1787219112056.png")
        assumeTrue("960 帧不存在,跳过", file.exists())
        val (bytes, dims) = readFrame(file.absolutePath)
        val t0 = System.nanoTime()
        val result = ImportedDpmScanner.scanImage(bytes, dims.first, dims.second)
        val ms = (System.nanoTime() - t0) / 1e6
        println("1787219112056(960x1280): ${result?.regionName}/${result?.variantName} dim=${result?.dimension} in ${ms}ms")
        assertEquals("L0549630AE092212080057", result?.text)
    }

    /**
     * 全量批次统计（显式 --tests 过滤运行，不随常规测试集跑）：D:/dpm_dump_20260820/cache
     * 全部 input_* 帧 —— 与 Python 参考实现 79/115 同一新批次。AUTO 模式 + 5000ms
     * 预算（生产 GRID_TASK_BUDGET_MS=2500 的宽松版，减少截止误杀；逐帧耗时可对照）。
     * 4 线程并发（ImportedDpmScanner 无状态线程安全）。汇总写
     * D:/dpm_dump_20260820/batch_scan_summary.txt（每帧 结果/路径/尺寸/耗时 + OK 总数/
     * 不同文本数/总耗时）。
     */
    @Test
    fun batchDecodeCache20260820() {
        val dir = File("D:/dpm_dump_20260820/cache")
        assumeTrue("缓存目录不存在,跳过", dir.isDirectory)
        val files = dir.listFiles().orEmpty()
            .filter { it.name.startsWith("input_") && it.name.endsWith(".png") }
            .sortedBy { it.name }
        assertTrue("无 input_*.png 帧", files.isNotEmpty())
        val total = files.size
        val ok = AtomicInteger(0)
        val texts = Collections.synchronizedSet(HashSet<String>())
        val lines = Collections.synchronizedList(ArrayList<String>(total))
        val pool = Executors.newFixedThreadPool(4)
        val startedNanos = System.nanoTime()
        try {
            val futures = files.map { file ->
                pool.submit {
                    val (bytes, dims) = readFrame(file.absolutePath)
                    val control = DpmScanControl(deadlineNanos = System.nanoTime() + 5_000L * 1_000_000L)
                    val t0 = System.nanoTime()
                    val result = ImportedDpmScanner.scanImage(bytes, dims.first, dims.second, DpmDimensionMode.AUTO, control)
                    val ms = (System.nanoTime() - t0) / 1e6
                    if (result != null) {
                        ok.incrementAndGet()
                        texts.add(result.text)
                        lines.add("${file.name}\tOK\t${result.regionName}/${result.variantName}\tdim=${result.dimension}\t${result.text}\t${"%.0f".format(ms)}ms")
                    } else {
                        lines.add("${file.name}\tFAIL\t${control.abortReason}\t${"%.0f".format(ms)}ms")
                    }
                }
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        val elapsedMs = (System.nanoTime() - startedNanos) / 1e6
        val summary = File("D:/dpm_dump_20260820/batch_scan_summary.txt")
        summary.writeText(
            lines.sorted().joinToString("\n") + "\n\n" +
                "=== $ok/$total OK (${"%.1f".format(ok.get() * 100.0 / total)}%), distinct texts=${texts.size}, " +
                "wall=${"%.0f".format(elapsedMs)}ms ==="
        )
        println("BATCH-SUMMARY: $ok/$total OK (${"%.1f".format(ok.get() * 100.0 / total)}%), " +
            "distinct=${texts.size}, wall=${"%.0f".format(elapsedMs)}ms -> ${summary.absolutePath}")
        lines.sorted().forEach { println("BATCH $it") }
    }

    @Test
    fun decodeVariantsDirect() {
        // 变体直解兜底链（regions×variants → 4 旋转/反色/缩放）：合成场景全链路已覆盖
        // grid 路径，此处直接验证 decodeVariants 入口可用（返回值 null 或 payload 均合法）。
        val bytes = makeDotPeenScene()
        val result = ImportedDpmScanner.decodeVariants(bytes, 400, 533)
        println("decodeVariants direct: $result")
    }

    // ---------------------------------------------------------------- 16/18/20 精确尺寸合成

    /** 找一个经 ZXing FORCE_SQUARE 编码后恰好为 dimension×dimension 的负载 */
    private fun payloadForDimension(dimension: Int): String {
        val hints = mapOf(EncodeHintType.DATA_MATRIX_SHAPE to SymbolShapeHint.FORCE_SQUARE)
        for (len in 1..60) {
            val payload = "A".repeat(len)
            val matrix = DataMatrixWriter().encode(payload, BarcodeFormat.DATA_MATRIX, 0, 0, hints)
            if (matrix.width == dimension && matrix.height == dimension) return payload
        }
        throw AssertionError("ZXing 找不到恰好 ${dimension}x$dimension 的方形负载")
    }

    /** 生成器自检：负载经 FORCE_SQUARE 编码的实际矩阵必须就是目标尺寸 */
    private fun assertSymbolIsExactly(payload: String, dimension: Int) {
        val matrix = DataMatrixWriter().encode(
            payload, BarcodeFormat.DATA_MATRIX, 0, 0,
            mapOf(EncodeHintType.DATA_MATRIX_SHAPE to SymbolShapeHint.FORCE_SQUARE),
        )
        assertEquals("负载 $payload 实际符号宽度不是 $dimension", dimension, matrix.width)
        assertEquals("负载 $payload 实际符号高度不是 $dimension", dimension, matrix.height)
    }

    @Test
    fun autoDecodesAllThreeDimensionsDotPeen() {
        for (d in intArrayOf(16, 18, 20)) {
            val payload = payloadForDimension(d)
            assertSymbolIsExactly(payload, d)
            val bytes = makeDotPeenScene(payload, forceSquare = true)
            val t0 = System.nanoTime()
            val result = ImportedDpmScanner.scanImage(bytes, 400, 533, DpmDimensionMode.AUTO)
            val ms = (System.nanoTime() - t0) / 1e6
            println("AUTO dot-peen ${d}x$d: ${result?.regionName}/${result?.variantName} dim=${result?.dimension} in ${ms}ms")
            assertEquals("AUTO ${d}x$d 未解出", payload, result?.text)
            assertEquals("AUTO ${d}x$d 命中尺寸错误", d, result?.dimension)
        }
    }

    @Test
    fun autoDecodesInvertedScenes() {
        for (d in intArrayOf(16, 18, 20)) {
            val payload = payloadForDimension(d)
            assertSymbolIsExactly(payload, d)
            // 反色点阵：亮主点/暗辅点（std 图与极性无关）
            val bytes = makeDotPeenScene(payload, forceSquare = true, inverted = true)
            val t0 = System.nanoTime()
            val result = ImportedDpmScanner.scanImage(bytes, 400, 533, DpmDimensionMode.AUTO)
            val ms = (System.nanoTime() - t0) / 1e6
            println("AUTO inverted ${d}x$d: ${result?.regionName}/${result?.variantName} dim=${result?.dimension} in ${ms}ms")
            assertEquals("反色 ${d}x$d 未解出", payload, result?.text)
            assertEquals("反色 ${d}x$d 命中尺寸错误", d, result?.dimension)
        }
    }

    @Test
    fun autoDecodesLowContrastScenes() {
        for (d in intArrayOf(16, 18, 20)) {
            val payload = payloadForDimension(d)
            assertSymbolIsExactly(payload, d)
            // 低对比度点阵：主点 126 / 辅点 158（底 142，幅值 ±16，正常场景 ±60）
            val bytes = makeDotPeenScene(payload, forceSquare = true, dotMain = 126.0, dotAccent = 158.0)
            val t0 = System.nanoTime()
            val result = ImportedDpmScanner.scanImage(bytes, 400, 533, DpmDimensionMode.AUTO)
            val ms = (System.nanoTime() - t0) / 1e6
            println("AUTO low-contrast ${d}x$d: ${result?.regionName}/${result?.variantName} dim=${result?.dimension} in ${ms}ms")
            assertEquals("低对比度 ${d}x$d 未解出", payload, result?.text)
            assertEquals("低对比度 ${d}x$d 命中尺寸错误", d, result?.dimension)
        }
    }

    @Test
    fun fixedModeDecodesItsOwnDimension() {
        val fixed = mapOf(
            16 to DpmDimensionMode.DIM_16,
            18 to DpmDimensionMode.DIM_18,
            20 to DpmDimensionMode.DIM_20,
        )
        for ((d, mode) in fixed) {
            val payload = payloadForDimension(d)
            assertSymbolIsExactly(payload, d)
            val bytes = makeDotPeenScene(payload, forceSquare = true)
            val t0 = System.nanoTime()
            val result = ImportedDpmScanner.scanImage(bytes, 400, 533, mode)
            val ms = (System.nanoTime() - t0) / 1e6
            println("FIXED ${mode.name} on ${d}x$d: ${result?.regionName}/${result?.variantName} dim=${result?.dimension} in ${ms}ms")
            assertEquals("固定 ${mode.name} 未解出", payload, result?.text)
            assertEquals("固定 ${mode.name} 命中尺寸错误", d, result?.dimension)
        }
    }

    @Test
    fun wrongFixedDimensionNeverFakesSuccess() {
        // 16×16 场景 + DIM_18：网格路径只允许 18；变体兜底与尺寸无关（dimension=0）。
        // 任何命中都不得报告 18。
        val payload = payloadForDimension(16)
        assertSymbolIsExactly(payload, 16)
        val bytes = makeDotPeenScene(payload, forceSquare = true)
        val result = ImportedDpmScanner.scanImage(bytes, 400, 533, DpmDimensionMode.DIM_18)
        println("DIM_18 on 16x16 scene: ${result?.text} dim=${result?.dimension}")
        assertTrue("错误固定尺寸伪装成 18 命中", result == null || result.dimension != 18)
    }

    // ---------------------------------------------------------------- 协作式截止/取消

    /** 无码噪声帧（960×1280，与真机全图输入同尺寸）：完整扫描最坏路径
     *  （grid→rotated→regions×variants）在无截止控制时单帧可达数分钟甚至
     *  27.8 分钟（真机 "grid task miss in 1669378ms"）。加控制后必须
     *  DEADLINE 短路返回。 */
    private fun makeBlankFrame(w: Int = 960, h: Int = 1280): ByteArray {
        val rng = Random(20260820)
        val grad = FloatArray(w) { -12f + it * 26f / (w - 1) }
        return ByteArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (142f + (rng.nextGaussian() * 2.0).toFloat() + grad[x]).toInt()
                .coerceIn(0, 255).toByte()
        }
    }

    /** deadline 已过：入口立即退出（远小于预算），原因 DEADLINE */
    @Test
    fun deadlineAlreadyPassedExitsImmediately() {
        val bytes = makeDotPeenScene()
        val control = DpmScanControl(deadlineNanos = System.nanoTime() - 1)
        val t0 = System.nanoTime()
        val result = ImportedDpmScanner.scanImage(bytes, 400, 533, DpmDimensionMode.AUTO, control)
        val ms = (System.nanoTime() - t0) / 1e6
        println("deadline-passed: result=$result in ${ms}ms")
        assertEquals(null, result)
        assertEquals(DpmAbortReason.DEADLINE, control.abortReason)
        assertTrue("deadline 已过应立即退出（实际 ${ms}ms）", ms < 100)
    }

    /** 已取消（isCancelled 恒 true）：入口立即退出，原因 CANCELLED */
    @Test
    fun cancelledExitsImmediately() {
        val bytes = makeDotPeenScene()
        val control = DpmScanControl(deadlineNanos = Long.MAX_VALUE, isCancelled = { true })
        val t0 = System.nanoTime()
        val result = ImportedDpmScanner.scanImage(bytes, 400, 533, DpmDimensionMode.AUTO, control)
        val ms = (System.nanoTime() - t0) / 1e6
        println("cancelled: result=$result in ${ms}ms")
        assertEquals(null, result)
        assertEquals(DpmAbortReason.CANCELLED, control.abortReason)
        assertTrue("已取消任务应立即退出（实际 ${ms}ms）", ms < 100)
    }

    /** 中途取消（先正常跑，扫描过程中 isCancelled 翻 true）：快速短路返回 CANCELLED。
     *  验证退出扫码模式后任务在下一个外层循环检查点停止，而不是继续跑完。 */
    @Test
    fun cancelMidScanExitsQuickly() {
        val bytes = makeBlankFrame()
        var cancelled = false
        val control = DpmScanControl(
            deadlineNanos = System.nanoTime() + 30_000L * 1_000_000L,
            isCancelled = { cancelled },
        )
        // 后台线程 300ms 后翻取消标志（模拟退出扫码模式）
        val flipper = Thread {
            Thread.sleep(300)
            cancelled = true
        }
        flipper.isDaemon = true
        flipper.start()
        val t0 = System.nanoTime()
        val result = ImportedDpmScanner.scanImage(bytes, 960, 1280, DpmDimensionMode.AUTO, control)
        val ms = (System.nanoTime() - t0) / 1e6
        println("cancel-mid-scan: result=$result in ${ms}ms")
        assertEquals(null, result)
        assertEquals(DpmAbortReason.CANCELLED, control.abortReason)
        // 取消后应在毫秒级检查点短路（远小于 30s 预算与无控制时的分钟级耗时）
        assertTrue("中途取消应在数秒内停止（实际 ${ms}ms）", ms < 10_000)
    }

    /** 空白 960×1280 帧 + 2500ms 预算（与 DpmAnalyzer.GRID_TASK_BUDGET_MS 一致）：
     *  DEADLINE 截断，总耗时受控在预算附近 —— 真机 "grid task miss in 1669378ms"
     *  类无限长任务不再出现。 */
    @Test
    fun blankFrameFinishesWithinBudget() {
        val bytes = makeBlankFrame()
        val control = DpmScanControl(
            deadlineNanos = System.nanoTime() + 2500L * 1_000_000L,
        )
        val t0 = System.nanoTime()
        val result = ImportedDpmScanner.scanImage(bytes, 960, 1280, DpmDimensionMode.AUTO, control)
        val ms = (System.nanoTime() - t0) / 1e6
        println("blank-960x1280: result=$result in ${ms}ms reason=${control.abortReason}")
        assertEquals(null, result)
        assertEquals("空白帧应耗尽预算被 DEADLINE 截断", DpmAbortReason.DEADLINE, control.abortReason)
        // 预算 2500ms + 单次不可打断的 OpenCV/ZXing 调用余量
        assertTrue("空白帧必须在预算附近截断（实际 ${ms}ms）", ms < 3500)
    }

    /** 宽裕预算（30s）：协作检查不破坏正常解码路径 —— 合成点阵码照常解出 */
    @Test
    fun generousDeadlineDoesNotBreakDecode() {
        val bytes = makeDotPeenScene()
        val control = DpmScanControl(
            deadlineNanos = System.nanoTime() + 30_000L * 1_000_000L,
        )
        val result = ImportedDpmScanner.scanImage(bytes, 400, 533, DpmDimensionMode.AUTO, control)
        assertEquals("宽裕预算下正常解码不受影响", "SYNTH-DPM-001", result?.text)
        assertEquals(DpmAbortReason.NONE, control.abortReason)
    }
}
