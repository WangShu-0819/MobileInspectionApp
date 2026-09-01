package com.wearable.inspection.mobile.dpm

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.datamatrix.DataMatrixReader
import com.google.zxing.datamatrix.DataMatrixWriter
import com.wearable.inspection.mobile.vision.OpenCvTestSupport
import com.wearable.inspection.mobile.dpm.ImportedDpmScanner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.Random

/**
 * [DpmPreprocessor] 多候选预处理验证（纯 JVM，OpenCV 桌面版原生库）。
 *
 * 对齐真机场景：工业 DPM 码（针打/激光）对比度低 + 离散散点。断言：
 * 1. 原始低对比散点图 ZXing 直接解码**失败**（L 角是断的，Detector 无法定位）——
 *    这是预处理存在的意义；
 * 2. 策略 0 双极性形态学候选（暗点/亮点各一条）把散点连接成实心模块后解码**成功**；
 * 3. 策略 1 双候选（原图/反相图自适应）不再按白像素数量二选一 —— 两条都返回、
 *    各自解码对应物理极性；
 * 4. 策略 2 双极性 Otsu 形态学候选；策略 3 双 HYBRID 灰度候选（CLAHE + 原图对照）；
 * 5. 二值候选只含 0/255、字节长度恒等于 width*height；
 * 6. 策略轮转 [DpmPreprocessor.strategiesForFrame]：生产（诊断关）frameCount % 4
 *    恒单策略；诊断开关开启时依次 0..3；
 * 7. **真实压痕凹点模型**（[renderDimpleScene]）：每模块单个圆形凹坑 + 方向性
 *    明暗双边缘 + 渐变底 + 噪声 + 模糊，亮底/暗底各一 —— 凹点形态的**主解码
 *    路径是 ImportedDpmScanner 重型网格重建**（快速预处理策略在真实凹点上的
 *    可解性如实打印，不做未经验证的成功断言）；
 * 8. Debug dump 配额（[DpmDumpBudget]）：默认 0 配额不写盘；显式 request 后按
 *    frameId 落一组；单进程硬上限 10 组。
 *
 * 模型说明（诚实声明）：[render] 的 [dotSpacing] 点网格是**结构代理** —— 逐像素
 * 孤立点 + 1px 缝隙，只建模"离散点断 L 角"问题（3×3 闭运算恰好焊上），**不等价
 * 于真实针撞压痕**（真机压痕是每模块一个圆形凹坑、带方向性明暗双边缘）。
 */
class DpmPreprocessorTest {

    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.DATA_MATRIX),
        DecodeHintType.TRY_HARDER to true,
    )

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        // DpmPreprocessor 为全局 object：测试间必须复位静态 dump 状态
        DpmPreprocessor.debugDumpDir = null
        DpmPreprocessor.dumpBudget = null
    }

    /** 灰度像素图（0-255，亮=大值） */
    private class GrayPixels(val data: IntArray, val width: Int) {
        val height: Int get() = data.size / width
    }

    /**
     * 原图直喂控制组：ARGB 灰度 → RGBLuminanceSource + HybridBinarizer（传统链路）。
     * 只用于「未预处理必失败」断言（对照），不代表生产解码路径。
     */
    private fun decodeRaw(pixels: IntArray, w: Int, h: Int): String? = runCatching {
        DataMatrixReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, pixels))),
            hints,
        ).text
    }.getOrNull()

    /**
     * 生产解码路径（镜像 DpmAnalyzer.decodePixels）：候选亮度字节 →
     * PlanarYUVLuminanceSource（零 RGB 往返）+ 候选声明 binarizer（GLOBAL 全局单阈值
     * / HYBRID 局部自适应）。[invert] = true 按字节反色兜底。
     */
    private fun decodeCandidate(c: DpmCandidate, invert: Boolean = false): String? = runCatching {
        val data = if (invert) {
            c.pixels.clone().also { t -> for (i in t.indices) t[i] = (255 - (t[i].toInt() and 0xFF)).toByte() }
        } else c.pixels
        val source = PlanarYUVLuminanceSource(data, c.width, c.height, 0, 0, c.width, c.height, false)
        val binarizer = if (c.binarizer == DpmBinarizer.HYBRID) HybridBinarizer(source) else GlobalHistogramBinarizer(source)
        DataMatrixReader().decode(BinaryBitmap(binarizer), hints).text
    }.getOrNull()

    /** 候选列表逐条双极性解码（镜像 DpmAnalyzer.decodeWithStrategy 的候选循环 + 反色双试） */
    private fun decodeCandidates(candidates: List<DpmCandidate>): String? =
        candidates.firstNotNullOfOrNull { c -> decodeCandidate(c) ?: decodeCandidate(c, invert = true) }

    /** 灰度 → ARGB（模拟低对比画面直接喂 ZXing） */
    private fun argbOf(g: GrayPixels): IntArray = IntArray(g.data.size) { i ->
        val v = g.data[i]
        0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
    }

    /**
     * 编码 DataMatrix 为灰度像素图（带 2 模块静区）。
     * [dotSpacing] = null → 实心模块；否则模块内按 [dotSpacing] 像素网格打点
     * （离散点阵**结构代理**：逐像素孤立点、点间 1px 缝隙，只建模"离散点断 L 角"，
     * 3×3 矩形闭运算恰好能把 1px 缝连同对角孔焊死 —— 椭圆十字核无对角偏移，
     * 填不上 (odd,odd) 孔。**不等价于真实针撞压痕**：真实凹点每模块一个圆坑，
     * 见 [renderDimpleScene]）。
     * [inverted] = true → 物理反色：亮底暗码换成暗底亮码（激光打白码的深色金属基面）。
     */
    private fun render(
        text: String,
        scale: Int = 8,
        dotSpacing: Int? = null,
        light: Int = 200,
        dark: Int = 80,
        inverted: Boolean = false,
    ): GrayPixels {
        val matrix = DataMatrixWriter().encode(text, BarcodeFormat.DATA_MATRIX, 0, 0)
        val quiet = 2
        val w = (matrix.width + quiet * 2) * scale
        val h = (matrix.height + quiet * 2) * scale
        val (bg, mark) = if (inverted) dark to light else light to dark
        val data = IntArray(w * h) { bg }
        for (my in 0 until matrix.height) {
            for (mx in 0 until matrix.width) {
                if (!matrix.get(mx, my)) continue
                val px0 = (mx + quiet) * scale
                val py0 = (my + quiet) * scale
                if (dotSpacing == null) {
                    for (y in py0 until py0 + scale) {
                        for (x in px0 until px0 + scale) data[y * w + x] = mark
                    }
                } else {
                    for (dy in 0 until scale step dotSpacing) {
                        for (dx in 0 until scale step dotSpacing) data[(py0 + dy) * w + (px0 + dx)] = mark
                    }
                }
            }
        }
        return GrayPixels(data, w)
    }

    @Before
    fun setUp() = OpenCvTestSupport.loadNative()

    // ---------------------------------------------------------------- 行为回归

    @Test
    fun `raw dot-peen fails but dot-peen strategy joins dots and decodes`() {
        val text = "DPM-88A92-001"
        val p = render(text, scale = 8, dotSpacing = 2)
        // 原始低对比散点图：ZXing 直接解失败（L 角是断的）
        assertNull("raw pixels should fail on sparse dot-peen", decodeRaw(argbOf(p), p.width, p.height))
        // 策略 0：双极性自适应 + 3x3 矩形闭运算焊散点（1px 缝含对角孔）→ 候选列表逐条解码
        val candidates = DpmPreprocessor.preprocess(p.data, p.width, p.height, DpmPreprocessor.STRATEGY_DOT_PEEN)
        assertEquals(text, decodeCandidates(candidates))
    }

    @Test
    fun `raw inverted code fails but inverted strategy decodes`() {
        val text = "DPM-88A92-001"
        val p = render(text, scale = 8, inverted = true)
        // 物理反色（白码黑底）：ZXing 直接解失败（Detector 找的 L 角是反的）
        assertNull("raw pixels should fail on inverted code", decodeRaw(argbOf(p), p.width, p.height))
        // 策略 1：双候选（原图/反相图自适应）分别进 ZXing，不按白像素数量二选一
        val candidates = DpmPreprocessor.preprocess(p.data, p.width, p.height, DpmPreprocessor.STRATEGY_INVERTED)
        assertEquals(2, candidates.size)
        assertEquals(text, decodeCandidates(candidates))
        // 反色物理码由反相图候选解出（原图候选对反色码失效 —— 证明两候选缺一不可）
        assertEquals(text, decodeCandidate(candidates.first { it.name == "s1-inverted-gray-adaptive" }))
    }

    @Test
    fun `all strategies decode normal solid code`() {
        val text = "DPM-88A92-001"
        val p = render(text, scale = 8, dotSpacing = null)
        for (strategy in 0 until DpmPreprocessor.STRATEGY_COUNT) {
            val candidates = DpmPreprocessor.preprocess(p.data, p.width, p.height, strategy)
            // 生产解码路径 + 反色双试（镜像 DpmAnalyzer.decodeWithStrategy）
            assertEquals("strategy=$strategy", text, decodeCandidates(candidates))
        }
    }

    // ---------------------------------------------------------------- 候选结构与取值

    @Test
    fun `strategy 0 returns two polarity-specific close candidates`() {
        val p = render("DPM-88A92-001", scale = 8)
        val candidates = DpmPreprocessor.preprocess(p.data, p.width, p.height, DpmPreprocessor.STRATEGY_DOT_PEEN)
        assertEquals(listOf("s0-dark-close", "s0-bright-close"), candidates.map { it.name })
        candidates.forEach { c ->
            assertEquals(DpmBinarizer.GLOBAL, c.binarizer)
            assertEquals(p.width * p.height, c.pixels.size)
            assertTrue("${c.name} must be binary", c.pixels.all { it == 0.toByte() || it == 255.toByte() })
        }
        // 两候选极性处理不同（像素内容不同，证明暗/亮分开做了形态学）
        assertFalse(candidates[0].pixels.contentEquals(candidates[1].pixels))
    }

    @Test
    fun `strategy 1 returns both adaptive candidates without white-count selection`() {
        val p = render("DPM-88A92-001", scale = 8, inverted = true)
        val candidates = DpmPreprocessor.preprocess(p.data, p.width, p.height, DpmPreprocessor.STRATEGY_INVERTED)
        // 不再按白像素数量二选一：两条候选全量返回
        assertEquals(listOf("s1-normal-adaptive", "s1-inverted-gray-adaptive"), candidates.map { it.name })
        candidates.forEach { c ->
            assertEquals(DpmBinarizer.GLOBAL, c.binarizer)
            assertEquals(p.width * p.height, c.pixels.size)
            assertTrue("${c.name} must be binary", c.pixels.all { it == 0.toByte() || it == 255.toByte() })
        }
        assertFalse(candidates[0].pixels.contentEquals(candidates[1].pixels))
    }

    @Test
    fun `strategy 2 returns two otsu morphology candidates`() {
        val p = render("DPM-88A92-001", scale = 8)
        val candidates = DpmPreprocessor.preprocess(p.data, p.width, p.height, DpmPreprocessor.STRATEGY_LASER_ETCHED)
        assertEquals(listOf("s2-dark-otsu-dilate", "s2-bright-otsu-dilate"), candidates.map { it.name })
        candidates.forEach { c ->
            assertEquals(DpmBinarizer.GLOBAL, c.binarizer)
            assertEquals(p.width * p.height, c.pixels.size)
            assertTrue("${c.name} must be binary", c.pixels.all { it == 0.toByte() || it == 255.toByte() })
        }
        assertFalse(candidates[0].pixels.contentEquals(candidates[1].pixels))
    }

    @Test
    fun `strategy 3 returns two hybrid gray candidates`() {
        val p = render("DPM-88A92-001", scale = 8)
        val candidates = DpmPreprocessor.preprocess(p.data, p.width, p.height, DpmPreprocessor.STRATEGY_ENHANCED_GRAY)
        assertEquals(listOf("s3-clahe-hybrid", "s3-gray-hybrid"), candidates.map { it.name })
        candidates.forEach { c ->
            assertEquals(DpmBinarizer.HYBRID, c.binarizer)
            assertEquals(p.width * p.height, c.pixels.size)
        }
        // 原图对照候选 = 输入灰度逐字节一致（未做硬二值化）
        val inputBytes = ByteArray(p.data.size) { p.data[it].toByte() }
        assertTrue(candidates[1].pixels.contentEquals(inputBytes))
        // CLAHE 增强候选与原图不同（增强生效）
        assertFalse(candidates[0].pixels.contentEquals(candidates[1].pixels))
    }

    @Test
    fun `all binary candidates contain only 0 and 255`() {
        val p = render("DPM-88A92-001", scale = 8, dotSpacing = 2)
        for (strategy in 0 until DpmPreprocessor.STRATEGY_COUNT - 1) {
            for (c in DpmPreprocessor.preprocess(p.data, p.width, p.height, strategy)) {
                assertTrue("s$strategy ${c.name} must be binary", c.pixels.all { it == 0.toByte() || it == 255.toByte() })
            }
        }
    }

    // ---------------------------------------------------------------- 策略轮转

    @Test
    fun `production strategy rotation follows frameCount modulo 4`() {
        for (frameId in 0L until 12L) {
            val strategies = DpmPreprocessor.strategiesForFrame(frameId) // 诊断开关默认关闭
            assertEquals(1, strategies.size)
            assertEquals("frameId=$frameId", (frameId % DpmPreprocessor.STRATEGY_COUNT).toInt(), strategies[0])
        }
    }

    @Test
    fun `diagnostic switch runs all strategies sequentially on the same frame`() {
        val strategies = DpmPreprocessor.strategiesForFrame(5L, debugAll = true)
        assertEquals(4, strategies.size)
        assertEquals(0, strategies[0])
        assertEquals(1, strategies[1])
        assertEquals(2, strategies[2])
        assertEquals(3, strategies[3])
    }

    // ---------------------------------------------------------------- 真实压痕凹点模型

    /**
     * 真实压痕凹点模型（对照源 synthetic_dpm.py make_dot_peen_scene 的圆点双圈）：
     * 每个深色模块 = **一个**圆形凹坑（针撞压痕），凹坑带方向性明暗双边缘
     * （暗圈偏 (-2,-2) = 阴影侧，亮圈偏 (+2,+2) = 反光侧）；底 [background] +
     * 高斯噪声 σ=2 (seed 20260820) + 水平渐变 -12..14 + GaussianBlur(3×3, 0.7)。
     * [inverted] = true 时主点亮/辅点暗（黑件：暗底上凹坑呈亮圈+暗影）。
     * 与 [render] 的 dotSpacing 点网格不同：每模块只 1 个凹点，模块视觉尺寸由
     * 凹点直径决定 —— 这才是真机压痕形态；其主解码路径是重型网格重建。
     */
    private fun renderDimpleScene(
        text: String = "DPM-DIMPLE-01",
        canvasWidth: Int = 400,
        canvasHeight: Int = 533,
        originX: Int = 70,
        originY: Int = 230,
        modulePx: Int = 14,
        inverted: Boolean = false,
        background: Double = 142.0,
    ): GrayPixels {
        val matrix = DataMatrixWriter().encode(text, BarcodeFormat.DATA_MATRIX, 0, 0)
        val main = if (inverted) 204.0 else 82.0
        val accent = if (inverted) 82.0 else 204.0
        val rng = Random(20260820)
        val grad = FloatArray(canvasWidth) { -12f + it * 26f / (canvasWidth - 1) }
        val mat = Mat(canvasHeight, canvasWidth, CvType.CV_32FC1)
        val base = FloatArray(canvasWidth * canvasHeight)
        for (y in 0 until canvasHeight) {
            for (x in 0 until canvasWidth) {
                base[y * canvasWidth + x] = (background + rng.nextGaussian() * 2.0 + grad[x]).toFloat()
            }
        }
        mat.put(0, 0, base)
        try {
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
                val out = IntArray(canvasWidth * canvasHeight)
                for (i in f.indices) out[i] = f[i].toInt().coerceIn(0, 255)
                return GrayPixels(out, canvasWidth)
            } finally {
                blur.release()
            }
        } finally {
            mat.release()
        }
    }

    /** 白件凹点：raw ZXing 失败（凹点低对比），重型网格重建解出 —— 凹点主路径 */
    @Test
    fun `dimple model raw fails and heavy grid decodes light workpiece`() {
        val p = renderDimpleScene()
        assertNull("凹点低对比图 raw ZXing 必须失败", decodeRaw(argbOf(p), p.width, p.height))
        val bytes = ByteArray(p.data.size) { p.data[it].toByte() }
        val result = ImportedDpmScanner.scanImage(bytes, p.width, p.height)
        println("dimple light-bg: ${result?.regionName}/${result?.variantName} dim=${result?.dimension}")
        assertEquals("白件凹点重型网格应解出", "DPM-DIMPLE-01", result?.text)
    }

    /** 黑件凹点：暗底凹坑呈亮圈+暗影（方向性双边缘），极性无关算法应同样解出 */
    @Test
    fun `dimple model heavy grid decodes dark workpiece`() {
        val p = renderDimpleScene(inverted = true, background = 70.0)
        assertNull("黑件凹点 raw ZXing 必须失败", decodeRaw(argbOf(p), p.width, p.height))
        val bytes = ByteArray(p.data.size) { p.data[it].toByte() }
        val result = ImportedDpmScanner.scanImage(bytes, p.width, p.height)
        println("dimple dark-bg: ${result?.regionName}/${result?.variantName} dim=${result?.dimension}")
        assertEquals("黑件凹点重型网格应解出", "DPM-DIMPLE-01", result?.text)
    }

    /** 各快速预处理策略在真实凹点上的可解性：结构断言 + 如实打印（不做未经验证的成功断言） */
    @Test
    fun `dimple model light preprocessor strategies report honestly`() {
        val p = renderDimpleScene()
        for (strategy in 0 until DpmPreprocessor.STRATEGY_COUNT) {
            val candidates = DpmPreprocessor.preprocess(p.data, p.width, p.height, strategy)
            assertEquals("strategy=$strategy 恒 2 条候选", 2, candidates.size)
            val decoded = decodeCandidates(candidates)
            println("dimple light-bg strategy=$strategy -> ${decoded ?: "MISS"}")
        }
    }

    // ---------------------------------------------------------------- Debug dump 配额

    /** 默认 0 配额：即使 debugDumpDir 已接线也不落盘（防 debug 包默认全量写盘） */
    @Test
    fun `dump writes nothing by default even with dir set`() {
        val dir = tmp.newFolder("dpm-dump")
        DpmPreprocessor.debugDumpDir = dir.absolutePath
        DpmPreprocessor.dumpBudget = DpmDumpBudget() // 0 授予 = 默认不写
        val p = render("DPM-88A92-001", scale = 8, dotSpacing = 2)
        DpmPreprocessor.preprocess(p.data, p.width, p.height, DpmPreprocessor.STRATEGY_DOT_PEEN, frameId = 1L)
        assertEquals("默认 0 配额不落盘", 0, (dir.listFiles() ?: emptyArray()).size)
    }

    /** 显式 request(1)：恰好写一组（input + 两条候选，文件名共享 frameId）；配额耗尽后不再写 */
    @Test
    fun `explicit dump request writes one frame set with shared frameId`() {
        val dir = tmp.newFolder("dpm-dump")
        DpmPreprocessor.debugDumpDir = dir.absolutePath
        DpmPreprocessor.dumpBudget = DpmDumpBudget()
        DpmPreprocessor.requestDebugDump(1)
        val p = render("DPM-88A92-001", scale = 8, dotSpacing = 2)
        DpmPreprocessor.preprocess(p.data, p.width, p.height, DpmPreprocessor.STRATEGY_DOT_PEEN, frameId = 42L)
        assertEquals(
            setOf(
                "input_s0_${p.width}x${p.height}_f42.png",
                "pre_s0_s0-dark-close_${p.width}x${p.height}_f42.png",
                "pre_s0_s0-bright-close_${p.width}x${p.height}_f42.png",
            ),
            dir.listFiles()!!.map { it.name }.toSet(),
        )
        // 配额已耗尽：下一帧（frameId=43）不再落盘
        DpmPreprocessor.preprocess(p.data, p.width, p.height, DpmPreprocessor.STRATEGY_DOT_PEEN, frameId = 43L)
        assertEquals("第二帧无配额不落盘", 3, dir.listFiles()!!.size)
    }

    /** 硬上限 30 组/进程（一组 = 一帧全部文件）：request(100) 截断，只有前 30 帧落盘 */
    @Test
    fun `dump budget caps at thirty frame sets per process`() {
        val dir = tmp.newFolder("dpm-dump")
        DpmPreprocessor.debugDumpDir = dir.absolutePath
        DpmPreprocessor.dumpBudget = DpmDumpBudget()
        DpmPreprocessor.requestDebugDump(100)
        val p = render("DPM-88A92-001", scale = 8, dotSpacing = 2)
        for (frameId in 1L..35L) {
            // 任意策略每帧写 input + 2 候选共 3 文件；30 组配额 → 前 30 帧 = 90 文件
            DpmPreprocessor.preprocess(p.data, p.width, p.height, DpmPreprocessor.STRATEGY_INVERTED, frameId)
        }
        assertEquals("35 帧只有前 30 帧落盘（每帧 3 文件）", 90, dir.listFiles()!!.size)
    }
}
