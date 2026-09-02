package com.wearable.inspection.mobile.ocr

/**
 * 可变行数钢印 OCR 结构化结果与状态机（纯 Kotlin，JVM 单测直接覆盖）。
 *
 * 设计要点（对齐《可变行数钢印 OCR 精确识别》规格）：
 * - **不强制补行**：行数 = 几何聚类实际检出数（1..N），版式枚举如实表达；
 * - **漏行判定**：仅当 schema（有限枚举）/零件目录给出预期行数且检出 < 预期时，
 *   判定为「疑似漏识别」→ 状态降级 [OcrResultStatus.NEED_CONFIRMATION]，不静默补空行；
 * - **字符证据**：每个字符保留全部候选投票（[CharEvidence.votes]），低置信度
 *   （权重不足 / 两强竞争 / 字符集外）标记 uncertain，由操作人员确认；
 * - **状态机**：EXACT（自动成功，须同时通过候选共识 + 版式校验 + 可选目录唯一匹配）/
 *   NEED_CONFIRMATION（存在不确定字符、漏行疑云或目录冲突）/ FAILED（无内容）。
 */

/** 钢印版式（有限枚举）：行数 = 几何证据，绝不按默认值强猜 */
enum class SteelStampLayout { ONE_LINE, TWO_LINE, THREE_LINE, UNKNOWN }

/** 结果状态：EXACT = 自动成功；NEED_CONFIRMATION = 低置信度/漏行疑云/目录冲突，需人工确认 */
enum class OcrResultStatus { EXACT, NEED_CONFIRMATION, FAILED }

/** 单字符证据：融合投票结果 + 候选明细（供 UI 高亮与日志复盘） */
data class CharEvidence(
    /** 最终字符（融合投票胜者；不确定时仍保留最佳票，但 [uncertain] = true 交由人工裁决） */
    val char: Char,
    /** 归一化置信度 0..1（胜者票权 / 总票权；越高越可靠） */
    val confidence: Float,
    /** 全部候选对当前位置的投票（原始证据，规则只约束不篡改） */
    val votes: Map<Char, Float>,
    /** true = 低置信度：票差不足 / 双强竞争 / 字符集外 —— UI 高亮要求人工确认 */
    val uncertain: Boolean,
)

/** 单行融合结果 */
data class SteelStampLine(
    /** 融合后文本（逐字符拼接） */
    val text: String,
    /** 逐字符证据 */
    val chars: List<CharEvidence>,
    /** 参与本行融合的有效候选数（≥2 共识更强；1 = 单候选孤证） */
    val sourceCount: Int,
    /** 行在 ROI 内归一化 y 中心（0..1，自上而下排序依据） */
    val yCenter: Float,
)

/** 单候选的结构化识别产物（证据留存，供状态判定与日志） */
data class CandidateRecognition(
    /** 候选名（pos-clahe / inv-adaptive …） */
    val candidateName: String,
    /** 候选文本行（几何归一化框 + 文本；供跨候选行聚类与融合） */
    val lines: List<OcrLineBox>,
    /** 候选原始全文（诊断/回退展示） */
    val rawText: String,
    /** 本次识别实际耗时 ms */
    val elapsedMs: Long,
)

/** 有限版式 schema：名称 + 预期行数（后续可扩展校验规则） */
data class SteelStampSchema(
    val name: String,
    val expectedLines: Int,
)

/**
 * 零件目录匹配（目录校验的输入；不在此处访问数据库，保持纯逻辑可测）。
 * [matches] 为空 = 目录未收录；1 个 = 唯一匹配；>1 = 冲突。
 */
data class PartCatalogMatch(
    val query: String,
    val matches: List<String>,
)

/** 钢印 OCR 结构化结果 */
data class SteelStampResult(
    /** 融合后的行（自上而下；空 = 未识别到内容） */
    val lines: List<SteelStampLine>,
    /** 检出行数（几何聚类） */
    val detectedLineCount: Int,
    /** 预期行数（schema/目录给出；null = 未知，不强猜） */
    val expectedLineCount: Int?,
    /** 版式（1~3 行枚举；检出为 0 或 >3 时 UNKNOWN） */
    val layout: SteelStampLayout,
    /** 命中的有限 schema（BMW 三行等）；null = 未匹配任何已知版式 */
    val matchedSchema: SteelStampSchema?,
    /** 零件号（首行 BMW 格式 `\d{7}-\d{2}`；其他版式取首行合法钢印 token） */
    val partNumber: String?,
    /** 结构化字段（schema 解析出的语义槽；BMW 三行：partNo/batch/dateCode 等） */
    val fields: Map<String, String>,
    /** 全部候选识别产物（证据留存，报告与诊断用） */
    val rawCandidates: List<CandidateRecognition>,
    /** 不确定字符位置（行号 0 起, 列号 0 起）——UI 高亮 + 人工确认清单 */
    val uncertainPositions: List<Pair<Int, Int>>,
    /** 最终状态（状态机见 [SteelStampResultMachine]） */
    val status: OcrResultStatus,
    /** 分层诊断：BlurCheck / PreProcess / Recognize / Recognized */
    val stage: String,
    /** ROI 缩略图（原图裁剪后的识别输入，供工人对比真伪；null = 无内容区域） */
    val roiThumbnail: BitmapRef? = null,
    /** 清晰度分值（Laplacian 方差；供状态行展示） */
    val blurScore: Double = 0.0,
    /** 识别阶段是否超时截断 */
    val timedOut: Boolean = false,
)

/**
 * Bitmap 引用（避免纯模型文件依赖 android.graphics；android 侧构造，
 * 由弹窗消费后 recycle —— 所有权显式转移，杜绝泄漏）。
 */
class BitmapRef(val bitmap: android.graphics.Bitmap, val label: String = "roi") {
    fun recycle() = bitmap.recycle()
}

/** 状态机与结果装配（纯逻辑） */
object SteelStampResultMachine {

    /** 置信度门限：胜者票权占比低于该值 → 低置信度 */
    const val MIN_CONFIDENCE = 0.55f

    /**
     * 两强竞争门限：第一名与第二名票差占比低于该值 → 低置信度（3/5、N/H、0/5 混淆）。
     * 保守策略：任何实质分歧（2:1、5:3 等）都需人工确认；唯一共识（3:0、6:2 等
     * 75% 以上一致）才自动成功 ——「充分证据才不打扰操作员」。
     */
    const val MIN_VOTE_GAP = 0.6f

    /** 允许的字符集（钢印格式约束：只约束类别，不无证据替换） */
    val STAMP_CHARSET = ('A'..'Z') + ('0'..'9') + listOf('-', '.', '/', ' ')

    /** 行数 → 版式 */
    fun layoutOf(lineCount: Int): SteelStampLayout = when (lineCount) {
        1 -> SteelStampLayout.ONE_LINE
        2 -> SteelStampLayout.TWO_LINE
        3 -> SteelStampLayout.THREE_LINE
        else -> SteelStampLayout.UNKNOWN
    }

    /**
     * 版式 schema 检测（有限枚举）：BMW 三行件 —— 首行形如 `BMW 3332 6894228-02`。
     * 命中 → 预期行数 = 3。新版式只需在此扩展枚举，不写死照片/哈希。
     */
    fun detectSchema(lines: List<String>): SteelStampSchema? {
        val first = lines.firstOrNull()?.trim()?.uppercase() ?: return null
        return if (BMW_FIRST_LINE_REGEX.containsMatchIn(first)) BMW_THREE_LINE else null
    }

    /**
     * 零件号提取（首行 BMW 格式 `\d{7}-\d{2}`；非 BMW 版式回退到首行首钢印 token）。
     * BMW 首行损坏时不回退到车型码（如 3332）冒充零件号 —— 宁可返回 null。
     */
    fun extractPartNumber(lines: List<String>): String? {
        for (line in lines) {
            BMW_PART_REGEX.find(line)?.let {
                return "${it.groupValues[1]}-${it.groupValues[2]}"
            }
        }
        val first = lines.firstOrNull()?.trim()?.uppercase() ?: return null
        if (first.startsWith("BMW")) return null
        return first.split(' ', '\t')
            .firstOrNull { it.length in 4..20 && it.all { c -> c in STAMP_CHARSET } }
    }

    /** 行字段解析（BMW 三行版式）：partNo / modelCode / batch / dateCode / line / material 等语义槽 */
    fun parseFields(lines: List<String>, schema: SteelStampSchema?): Map<String, String> {
        if (schema?.name != BMW_THREE_LINE.name || lines.size < 3) return emptyMap()
        val line1 = lines[0].split(' ').filter { it.isNotBlank() }
        val line2 = lines[1].split(' ').filter { it.isNotBlank() }
        val line3 = lines[2].split(' ').filter { it.isNotBlank() }
        return buildMap {
            put("brand", line1.getOrNull(0) ?: "")
            put("modelCode", line1.getOrNull(1) ?: "")
            if (line1.size >= 3) put("partNo", line1.subList(2, line1.size).joinToString(" "))
            if (line2.isNotEmpty()) put("batch", line2.joinToString(" "))
            if (line3.isNotEmpty()) put("dateCode", line3.joinToString(" "))
        }.filterValues { it.isNotEmpty() }
    }

    /**
     * 状态机装配：
     * 1. 无内容 → FAILED；
     * 2. 无不确定字符 + 版式证据充分（schema 命中且检出 == 预期；或无 schema 时
     *    行数 1~3 且检出 == 预期(未配置时按检出)）→ EXACT；
     * 4. 其余（存在不确定字符 / 检出 != 预期 → 疑似漏行 / 目录冲突）→ NEED_CONFIRMATION。
     */
    fun build(
        lines: List<SteelStampLine>,
        rawCandidates: List<CandidateRecognition>,
        catalog: PartCatalogMatch?,
        stage: String = "Recognized",
        blurScore: Double = 0.0,
        timedOut: Boolean = false,
    ): SteelStampResult {
        val formattedLines = BmwThreeLineFormatter.format(lines)
        val texts = formattedLines.map { it.text }
        val layout = layoutOf(formattedLines.size)
        val schema = detectSchema(texts)
        val expected = schema?.expectedLines
        val uncertainPositions = buildList {
            formattedLines.forEachIndexed { li, line ->
                line.chars.forEachIndexed { ci, ev ->
                    if (ev.uncertain) add(li to ci)
                }
            }
        }

        val status = when {
            formattedLines.isEmpty() -> OcrResultStatus.FAILED
            uncertainPositions.isNotEmpty() -> OcrResultStatus.NEED_CONFIRMATION
            // 目录冲突是反证；目录唯一只验证零件号，不能证明其余钢印行逐字正确。
            catalog != null && catalog.matches.size > 1 -> OcrResultStatus.NEED_CONFIRMATION
            // 漏行判定：版式已知且检出 != 预期 → 疑似漏识别/多识别，需人工确认（不补空行）
            expected != null && formattedLines.size != expected -> OcrResultStatus.NEED_CONFIRMATION
            // 无预期版式：无法区分「漏识别」与「本来无该行」→ 强制需人工确认
            expected == null -> OcrResultStatus.NEED_CONFIRMATION
            // 未知版式（检出 0/超 3 行）：几何证据异常 → 需人工确认
            layout == SteelStampLayout.UNKNOWN -> OcrResultStatus.NEED_CONFIRMATION
            else -> OcrResultStatus.EXACT
        }

        return SteelStampResult(
            lines = formattedLines,
            detectedLineCount = formattedLines.size,
            expectedLineCount = expected,
            layout = layout,
            matchedSchema = schema,
            partNumber = extractPartNumber(texts),
            fields = parseFields(texts, schema),
            rawCandidates = rawCandidates,
            uncertainPositions = uncertainPositions,
            status = status,
            stage = stage,
            blurScore = blurScore,
            timedOut = timedOut,
        )
    }

    /** 目录校验后置合并：唯一/无匹配保持算法状态；冲突（>1）降级待人工确认。 */
    fun applyCatalogValidation(result: SteelStampResult, catalog: PartCatalogMatch): SteelStampResult {
        if (result.status == OcrResultStatus.FAILED) return result
        val validated = if (catalog.matches.size > 1) {
            OcrResultStatus.NEED_CONFIRMATION
        } else {
            result.status
        }
        return result.copy(status = validated)
    }

    // ---------- 已知版式（有限枚举） ----------

    /** BMW 三行件：`BMW 3332 6894228-02` / `230447 10 CN` / `16924 AA 0050` */
    val BMW_THREE_LINE = SteelStampSchema(name = "BMW_3L", expectedLines = 3)

    /** 首行 BMW 版式识别（格式约束：品牌 + 车型码 + 零件号；不匹配具体字符） */
    private val BMW_FIRST_LINE_REGEX = Regex("^BMW\\s+\\S+\\s+\\d{6,7}[-\\s]\\d{2}")

    /** 零件号提取（BMW 短零件号 `\d{7}-\d{2}`；与既有 extractSteelStamp 同源） */
    private val BMW_PART_REGEX = Regex("(?<!\\d)(\\d{7})[\\s-]+(\\d{2})(?!\\d)")
}
