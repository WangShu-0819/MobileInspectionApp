package com.wearable.inspection.mobile.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 钢印 OCR 分层分析器 ——「ROI 输入 - 多候选增强 - 全候选结构化识别 - 几何行聚类 -
 * 字符级加权融合 - 版式/目录状态机」结构化管线（对齐《可变行数钢印 OCR 精确识别》）。
 *
 * 输入约定：**识别输入必须是原图裁剪出的高分辨率 ROI**（[OcrRoiCropUtils.decodeRoiRegion]，
 * 字符高度 30~60px），本层不再做整图降采样。
 *
 * - **协作式超时防跑飞**：[OCR_TIMEOUT_MS] 绝对预算，候选循环每个检查点截断，
 *   ML Kit 调用按剩余预算 Tasks.await —— 已识别的候选仍然参与融合，绝不返回半截数据；
 * - **多候选互为证据**：clahe/gamma/unsharp/adaptive × 双极性共 8 条候选全部过引擎
 *   （不再首中即停），按 [SteelStampLineCluster] 几何聚类 + [SteelStampCharFusion]
 *   字符级加权投票；格式规则只做约束、不无证据替换（3/5、N/H、0/5 混淆 → 低置信度
 *   标记，由 [SteelStampResultMachine] 状态机降级为 NEED_CONFIRMATION）；
 * - **可变行数**：行数 = 聚类检出（1..N），[SteelStampLayout] 如实表达；预期行数
 *   来自有限版式 schema（BMW 三行）或零件目录，检出 != 预期 → 疑似漏行 → 人工确认。
 *
 * 线程模型：analyzeStructured() 为同步 CPU 任务（OpenCV + ML Kit await 阻塞），
 * 调用方必须放到后台线程（Dispatchers.Default）；相机分析线程/主线程禁止直调。
 */
class SteelStampOcrAnalyzer {

    /** 轻量 ML Kit 文本识别（bundled 模型离线可用；识别优先 CLAHE 灰度候选） */
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * 失败诊断落盘目录（仅 DEBUG 构建设置）：识别失败时把输入 ROI + 全部预处理候选
     * 写成 PNG，供离线调优预处理参数。null = 不落盘。
     */
    var dumpDir: File? = null

    /**
     * 预热：进入拍照预览时后台调用一次。ML Kit 的 TFLite 模型（taser 检测器 + gocr
     * 识别器）**首次 process() 时同步加载，耗时 1~3s** —— 若放在点击拍照后的
     * [OCR_TIMEOUT_MS] 预算内，首拍必然被模型加载吃光预算而超时截断。
     */
    fun warmUp() {
        runCatching {
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            try {
                Tasks.await(
                    recognizer.process(InputImage.fromBitmap(bmp, 0)),
                    WARMUP_TIMEOUT_MS, TimeUnit.MILLISECONDS,
                )
            } finally {
                bmp.recycle()
            }
        }.onSuccess {
            Log.i(TAG, "[OCR] Stage=WarmUp done（模型已加载，点击拍照不再吃首帧预算）")
        }.onFailure {
            Log.w(TAG, "[OCR] Stage=WarmUp failed: ${it.message}")
        }
    }

    /**
     * 单 ROI 完整结构化识别管线。输入应为原图裁剪的高分辨率 ROI（字符高 30~60px）。
     * @return 结构化结果（永远非 null；失败由 [SteelStampResult.status]/[stage] 表达）
     */
    fun analyzeStructured(roiBitmap: Bitmap): SteelStampResult {
        val t0 = System.nanoTime()
        val deadline = t0 + OCR_TIMEOUT_MS * 1_000_000L
        var timedOut = false

        // ---- Stage 1: 清晰度校验（BlurCheck） ----
        val blurScore = OcrPreProcessor.computeBlurScore(roiBitmap)
        Log.i(TAG, "[OCR] Stage=BlurCheck score=${"%.1f".format(blurScore)}")
        if (blurScore < BLUR_REJECT_SCORE) {
            Log.i(TAG, "[OCR] Stage=BlurCheck REJECTED score=${"%.1f".format(blurScore)} < $BLUR_REJECT_SCORE")
            return SteelStampResultMachine.build(
                lines = emptyList(), rawCandidates = emptyList(), catalog = null,
                stage = "BlurCheck", blurScore = blurScore,
            )
        }

        // ---- Stage 2: 多极性采样与增强（PreProcess，含候选过滤） ----
        val candidates = OcrPreProcessor.buildCandidates(roiBitmap)
        if (candidates.isEmpty()) {
            Log.i(TAG, "[OCR] Stage=PreProcess EMPTY（全部极性候选被过滤层丢弃）")
            return SteelStampResultMachine.build(
                lines = emptyList(), rawCandidates = emptyList(), catalog = null,
                stage = "PreProcess", blurScore = blurScore,
            )
        }
        Log.i(
            TAG,
            "[OCR] Stage=PreProcess candidates=${candidates.size} t=${elapsedMs(t0)}ms input=${roiBitmap.width}x${roiBitmap.height}",
        )

        // ---- Stage 3: 全候选结构化识别（Recognize；预算内不首中即停，候选互为证据） ----
        val orderedCandidates = candidates.sortedBy { candidatePriority(it.name) }
        val recognitions = mutableListOf<Pair<OcrCandidate, CandidateRecognition>>()
        var refinementCount = 0
        var coreSeen = 0
        var earlyExitTried = false
        val dump = dumpDir?.let { mutableListOf<Pair<String, Bitmap>>() }
        for (candidate in orderedCandidates) {
            if (checkTimeout(deadline)) {
                timedOut = true
                Log.i(TAG, "[OCR] Stage=Recognize TIMEOUT（$OCR_TIMEOUT_MS ms 预算耗尽，保留 ${recognitions.size}/${candidates.size} 候选参与融合）")
                break
            }
            val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(50L)
            val tStart = System.nanoTime()
            try {
                val text = recognizeStructured(candidate.bitmap, remainingMs)
                val candMs = elapsedMs(tStart)
                if (text == null) {
                    Log.i(TAG, "[OCR] Stage=Recognize cand=${candidate.name} EMPTY t=${candMs}ms")
                    continue
                }
                val boxes = toLineBoxes(text, candidate.bitmap.width, candidate.bitmap.height)
                Log.i(TAG, "[OCR] Stage=Recognize cand=${candidate.name} t=${candMs}ms lines=${boxes.size} text=${text.text?.trim().orEmpty().take(60)}")
                recognitions.add(
                    candidate to CandidateRecognition(
                        candidateName = candidate.name,
                        lines = boxes,
                        rawText = text.text?.trim().orEmpty(),
                        elapsedMs = candMs,
                    ),
                )

                // 行级复识别
                if (!candidate.name.contains("adaptive")) {
                    for (box in boxes.sortedByDescending { it.width }) {
                        if (refinementCount >= MAX_LINE_REFINEMENTS || checkTimeout(deadline)) break
                        val rowRemainingMs = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(50L)
                        val rowStart = System.nanoTime()
                        val refined = recognizeLineCrop(candidate.bitmap, box, rowRemainingMs)
                        val rowMs = elapsedMs(rowStart)
                        refinementCount++
                        if (refined.isNullOrBlank()) continue
                        val refinedName = "${candidate.name}-row"
                        Log.i(TAG, "[OCR] Stage=Recognize cand=$refinedName t=${rowMs}ms text=${refined.take(60)}")
                        recognitions.add(
                            candidate to CandidateRecognition(
                                candidateName = refinedName,
                                lines = listOf(box.copy(text = refined)),
                                rawText = refined,
                                elapsedMs = rowMs,
                            ),
                        )
                    }
                }

                // 早停剪枝
                if (isCoreCandidate(candidate.name)) {
                    coreSeen++
                    if (coreSeen >= CORE_CANDIDATES_BEFORE_EXIT && !earlyExitTried) {
                        earlyExitTried = true
                        if (shouldEarlyExit(recognitions.map { it.second })) {
                            Log.i(TAG, "[OCR] Stage=Recognize EARLY_EXIT（主线 4 候选行数一致且文本重合 >= ${(EARLY_EXIT_MIN_SIMILARITY * 100).toInt()}%，跳过 gamma/adaptive 互补候选）")
                            break
                        }
                    }
                }
            } finally {
                if (dump != null) dump += candidate.name to candidate.bitmap else candidate.bitmap.recycle()
            }
        }
        if (dump != null) {
            if (recognitions.isEmpty() && dumpDir != null) saveDump(dumpDir!!, roiBitmap, dump)
            dump.forEach { it.second.recycle() }
        }

        // ---- Stage 4: 几何行聚类 + 字符级加权融合（Fuse） ----
        val tFuse = System.nanoTime()
        val entries = recognitions.flatMap { (cand, rec) ->
            val weight = SteelStampCharFusion.candidateWeight(rec.candidateName, cand.polarity)
            rec.lines.map { box -> BoxEntry(box, rec.candidateName, weight) }
        }
        val clusters = SteelStampLineCluster.cluster(entries.map { it.box })
        val fusedLines = clusters.mapNotNull { cluster ->
            val inputs = cluster.mapNotNull { box ->
                val entry = entries.first { it.box === box }
                LineFusionInput(entry.candidateName, box.text, entry.weight)
            }
            val centerY = cluster.map { it.centerY }.average().toFloat()
            SteelStampCharFusion.fuseLine(inputs)?.copy(yCenter = centerY)
        }
        val result = SteelStampResultMachine.build(
            lines = fusedLines,
            rawCandidates = recognitions.map { it.second },
            catalog = null,
            stage = if (recognitions.isEmpty()) "Recognize" else "Recognized",
            blurScore = blurScore,
            timedOut = timedOut,
        )
        // 格式化未生效时打根因
        BmwThreeLineFormatter.formatWhyUnchanged(fusedLines)?.let { reason ->
            Log.i(TAG, "[OCR] Stage=Format SKIP: $reason")
        }
        val fuseMs = elapsedMs(tFuse)

        // ---- 结果装配（缩略图 + 耗时统计日志） ----
        val final = result.copy(roiThumbnail = BitmapRef(scaleDown(roiBitmap, ROI_MAX_EDGE)))
        val totalMs = elapsedMs(t0)
        Log.i(
            TAG,
            "[OCR] Stage=Recognized status=${result.status} lines=${result.detectedLineCount}/" +
                "${result.expectedLineCount ?: "?"} layout=${result.layout} " +
                "uncertain=${result.uncertainPositions.size} part=${result.partNumber} " +
                "fuse=${fuseMs}ms total=${totalMs}ms",
        )
        result.lines.forEachIndexed { i, line ->
            Log.i(TAG, "[OCR] Line${i + 1}: ${line.text} (src=${line.sourceCount} y=${"%.2f".format(line.yCenter)})")
        }
        if (result.uncertainPositions.isNotEmpty()) {
            Log.i(TAG, "[OCR] Uncertain chars at ${result.uncertainPositions.joinToString { (li, ci) -> "L${li + 1}#${ci + 1}" }} — 需人工确认")
        }
        return final
    }

    // ---------- 引擎识别 ----------

    /** 单候选单次 ML Kit 结构化识别 */
    private fun recognizeStructured(bitmap: Bitmap, timeoutMs: Long): Text? {
        val input = InputImage.fromBitmap(bitmap, /* rotationDegrees = */ 0)
        return runCatching {
            Tasks.await(recognizer.process(input), timeoutMs, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    /** 单个已检测行裁剪、留白并放大后复识别 */
    private fun recognizeLineCrop(bitmap: Bitmap, box: OcrLineBox, timeoutMs: Long): String? {
        val padX = (box.width * bitmap.width * LINE_REFINE_X_MARGIN).toInt().coerceAtLeast(6)
        val padY = (box.height * bitmap.height * LINE_REFINE_Y_MARGIN).toInt().coerceAtLeast(4)
        val left = (box.left * bitmap.width).toInt().minus(padX).coerceIn(0, bitmap.width - 1)
        val top = (box.top * bitmap.height).toInt().minus(padY).coerceIn(0, bitmap.height - 1)
        val right = (box.right * bitmap.width).toInt().plus(padX).coerceIn(left + 1, bitmap.width)
        val bottom = (box.bottom * bitmap.height).toInt().plus(padY).coerceIn(top + 1, bitmap.height)
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val input = if (crop.height < LINE_REFINE_TARGET_HEIGHT) {
            val scale = LINE_REFINE_TARGET_HEIGHT.toFloat() / crop.height
            Bitmap.createScaledBitmap(
                crop,
                (crop.width * scale).toInt().coerceAtLeast(1),
                LINE_REFINE_TARGET_HEIGHT,
                true,
            )
        } else {
            crop
        }
        return try {
            val text = recognizeStructured(input, timeoutMs)?.text?.trim().orEmpty()
            text.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotBlank() }
        } finally {
            if (input !== crop) input.recycle()
            crop.recycle()
        }
    }

    /** ML Kit Text → 归一化行框（0..1 ROI 坐标） */
    private fun toLineBoxes(text: Text, w: Int, h: Int): List<OcrLineBox> {
        if (w <= 0 || h <= 0) return emptyList()
        val minH = h * LINE_MIN_HEIGHT_FRACTION
        return buildList {
            for (block in text.textBlocks) {
                for (line in block.lines) {
                    val raw = line.text ?: continue
                    if (raw.isBlank()) continue
                    val box = line.boundingBox ?: continue
                    if (box.height() < minH) continue
                    add(
                        OcrLineBox(
                            text = raw,
                            left = box.left.toFloat() / w,
                            top = box.top.toFloat() / h,
                            right = box.right.toFloat() / w,
                            bottom = box.bottom.toFloat() / h,
                        ),
                    )
                }
            }
        }
    }

    // ---------- 工具 ----------

    private fun scaleDown(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) {
            return Bitmap.createBitmap(src)
        }
        val scale = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            /* filter = */ true,
        )
    }

    private fun checkTimeout(deadlineNanos: Long): Boolean = System.nanoTime() > deadlineNanos

    private fun elapsedMs(sinceNanos: Long): Long = (System.nanoTime() - sinceNanos) / 1_000_000L

    // ---------- 失败诊断 ----------

    private fun saveDump(dir: File, input: Bitmap, candidates: List<Pair<String, Bitmap>>) {
        runCatching {
            dir.mkdirs()
            input.compress(
                Bitmap.CompressFormat.PNG, 100,
                FileOutputStream(File(dir, "input.png")),
            )
            for ((name, bmp) in candidates) {
                bmp.compress(
                    Bitmap.CompressFormat.PNG, 100,
                    FileOutputStream(File(dir, "cand_$name.png")),
                )
            }
        }.onSuccess {
            Log.i(TAG, "[OCR] Stage=Dump saved ${candidates.size} candidates + input -> ${dir.absolutePath}")
        }.onFailure {
            Log.w(TAG, "[OCR] Stage=Dump failed: ${it.message}")
        }
    }

    /** 聚类内部载体 */
    private data class BoxEntry(val box: OcrLineBox, val candidateName: String, val weight: Float)

    companion object {
        private const val TAG = "STEEL_OCR"

        /** OCR 总时间预算 */
        const val OCR_TIMEOUT_MS = 4500L

        /** 预热调用限时 */
        private const val WARMUP_TIMEOUT_MS = 8000L

        /** 清晰度门槛 */
        const val BLUR_REJECT_SCORE = 10.0

        /** ML Kit 行框最小高度（ROI 高度的比例） */
        const val LINE_MIN_HEIGHT_FRACTION = 0.05f

        /** 行级复识别最多追加次数 */
        private const val MAX_LINE_REFINEMENTS = 12
        private const val LINE_REFINE_TARGET_HEIGHT = 128
        private const val LINE_REFINE_X_MARGIN = 0.04f
        private const val LINE_REFINE_Y_MARGIN = 0.30f

        /** ROI 缩略图长边上限 */
        private const val ROI_MAX_EDGE = 360

        // ---------- 候选管线剪枝（纯函数，JVM 单测覆盖） ----------

        fun candidatePriority(candidateName: String): Int {
            val method = when {
                candidateName.contains("clahe") -> 0
                candidateName.contains("unsharp") -> 1
                candidateName.contains("gamma") -> 2
                else -> 3 // adaptive
            }
            val polarity = if (candidateName.startsWith("inv")) 1 else 0
            return method * 2 + polarity
        }

        fun isCoreCandidate(candidateName: String): Boolean =
            !candidateName.contains("-row") &&
                (candidateName.contains("clahe") || candidateName.contains("unsharp"))

        fun shouldEarlyExit(recognitions: List<CandidateRecognition>): Boolean {
            val valid = recognitions.filter { it.lines.size >= MIN_EARLY_EXIT_LINES }
            if (valid.size < MIN_EARLY_EXIT_CANDIDATES) return false
            val maxRows = valid.maxOf { it.lines.size }
            if (valid.any { maxRows - it.lines.size > MAX_ROW_SPREAD }) return false
            val reference = valid.first { it.lines.size == maxRows }
            val refLines = reference.lines.sortedBy { it.centerY }
            var totalSim = 0.0
            var compared = 0
            for (other in valid) {
                if (other === reference) continue
                val otherLines = other.lines.sortedBy { it.centerY }
                val n = minOf(refLines.size, otherLines.size)
                for (i in 0 until n) {
                    val a = refLines[i].text.trim().uppercase().replace(OCR_WHITESPACE, " ")
                    val b = otherLines[i].text.trim().uppercase().replace(OCR_WHITESPACE, " ")
                    if (a.isBlank() || b.isBlank()) continue
                    val sim = 1.0 - levenshteinDistance(a, b).toDouble() / maxOf(a.length, b.length, 1)
                    totalSim += sim
                    compared++
                }
            }
            return compared > 0 && totalSim / compared >= EARLY_EXIT_MIN_SIMILARITY
        }

        fun levenshteinDistance(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]
                dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(
                        dp[j] + 1,
                        dp[j - 1] + 1,
                        prev + if (a[i - 1] == b[j - 1]) 0 else 1,
                    )
                    prev = tmp
                }
            }
            return dp[b.length]
        }

        private const val CORE_CANDIDATES_BEFORE_EXIT = 4
        const val MIN_EARLY_EXIT_CANDIDATES = 2
        const val MIN_EARLY_EXIT_LINES = 2
        const val MAX_ROW_SPREAD = 1
        const val EARLY_EXIT_MIN_SIMILARITY = 0.75
    }
}

/** Pure OCR text post-processing */
internal fun extractSteelStamp(raw: String): String? {
    val lines = raw.split('\n')
        .map { it.trim().uppercase().replace(OCR_WHITESPACE, " ").trim() }
        .filter { it.isNotEmpty() }
    for (line in lines) {
        BMW_SHORT_PART_REGEX.find(line)?.let { match ->
            return "${match.groupValues[1]}-${match.groupValues[2]}"
        }
    }
    if (lines.any { BMW_LIKE_LINE_REGEX.containsMatchIn(it) }) return null
    val full = lines.joinToString(" ")
    val searchOrder = buildList {
        add(full)
        addAll(lines)
        for (line in lines) {
            line.split(' ').forEach { token -> if (token.isNotEmpty()) add(token) }
        }
    }
    return searchOrder.firstOrNull { STEEL_STAMP_REGEX.matches(it) }
}

private val STEEL_STAMP_REGEX = Regex("^[A-Z0-9\\-\\.]{4,20}$")
private val BMW_SHORT_PART_REGEX = Regex("(?<!\\d)(\\d{7})[\\s-]+(\\d{2})(?!\\d)")
private val BMW_LIKE_LINE_REGEX = Regex("^B[A-Z]{1,2}\\s+.*\\s+.*")
private val OCR_WHITESPACE = Regex("\\s+")
