package com.wearable.inspection.mobile.ocr

/** 单个预处理候选对一条物理钢印行的识别结果。 */
data class LineFusionInput(
    val candidateName: String,
    val text: String,
    val weight: Float,
)

/**
 * 跨候选字符融合。
 *
 * ML Kit 会随机增删空格、连字符或单个字符。旧实现先按 token 贪心配对，两个 token
 * 相差两个字符时完全不能互投，后续 token 也容易错列。这里改为：
 *
 * 1. 同一候选被拆成多个横向 fragment 时先合并，保证每个候选最多一票；
 * 2. 选择信息最完整的整行作为参考，用 Levenshtein 回溯把其他整行对齐到参考位；
 * 3. 每个对齐位置按候选权重投票，缺字符记空票而不是把后续字符左移；
 * 4. 具体字符只能来自 OCR 候选。格式规则可以在结果层校验，但不能在这里猜真值。
 */
object SteelStampCharFusion {

    fun candidateWeight(candidateName: String, polarity: OcrPolarity): Float {
        val base = when {
            candidateName.contains("clahe") -> 1.00f
            candidateName.contains("gamma") -> 0.95f
            candidateName.contains("unsharp") -> 0.95f
            candidateName.contains("adaptive") -> 0.90f
            else -> 0.85f
        }
        val polarityWeight = if (polarity == OcrPolarity.INVERTED) 0.98f else 1.00f
        // 行级复识别与原候选共享同一图像证据，不应被当作完全独立的一票。
        val independenceWeight = if (candidateName.contains("-row")) 0.75f else 1.00f
        return base * polarityWeight * independenceWeight
    }

    fun fuseLine(inputs: List<LineFusionInput>): SteelStampLine? {
        val effective = mergeCandidateFragments(inputs)
        if (effective.isEmpty()) return null

        // 最长文本最可能保留了缺失 token/分隔符；并列时使用先验权重更高的候选。
        val reference = effective.maxWithOrNull(
            compareBy<LineFusionInput> { it.text.length }.thenBy { it.weight },
        )?.text ?: return null
        val aligned = effective.map { it to alignToReference(reference, it.text) }

        val evidence = ArrayList<CharEvidence>(reference.length)
        for (index in reference.indices) {
            val votes = linkedMapOf<Char, Float>()
            var voters = 0
            for ((input, chars) in aligned) {
                val raw = chars[index] ?: continue
                val ch = normalizeVote(raw)
                val weight = if (ch in SteelStampResultMachine.STAMP_CHARSET) input.weight else input.weight * 0.5f
                votes[ch] = (votes[ch] ?: 0f) + weight
                voters++
            }
            if (votes.isEmpty()) continue
            val total = votes.values.sum()
            val sorted = votes.entries.sortedByDescending { it.value }
            val best = sorted.first()
            val second = sorted.getOrNull(1)?.value ?: 0f
            val confidence = if (total > 0f) best.value / total else 0f
            val structuralSpace = best.key == ' '
            val uncertain = !structuralSpace && (
                voters < 2 ||
                    confidence < SteelStampResultMachine.MIN_CONFIDENCE ||
                    (second > 0f && (best.value - second) / best.value < SteelStampResultMachine.MIN_VOTE_GAP) ||
                    best.key !in SteelStampResultMachine.STAMP_CHARSET
                )
            evidence += CharEvidence(
                char = best.key,
                confidence = confidence,
                votes = votes,
                uncertain = uncertain,
            )
        }

        val compact = compactSpaces(evidence)
        if (compact.isEmpty()) return null
        val finalEvidence = if (effective.size == 1) {
            compact.map { ev ->
                if (ev.char == ' ') ev else ev.copy(uncertain = true, confidence = ev.confidence * 0.5f)
            }
        } else {
            compact
        }
        return SteelStampLine(
            text = finalEvidence.joinToString("") { it.char.toString() },
            chars = finalEvidence,
            sourceCount = effective.size,
            yCenter = 0f,
        )
    }

    fun bestRawLine(inputs: List<LineFusionInput>): String? =
        mergeCandidateFragments(inputs).maxByOrNull { it.weight }?.text

    /** 同一候选在同一几何聚类内只能投一票；片段按调用顺序重新拼成一行。 */
    private fun mergeCandidateFragments(inputs: List<LineFusionInput>): List<LineFusionInput> =
        inputs.asSequence()
            .filter { it.text.isNotBlank() }
            .groupBy { it.candidateName }
            .map { (name, fragments) ->
                LineFusionInput(
                    candidateName = name,
                    text = normalizeText(fragments.joinToString(" ") { it.text }),
                    weight = fragments.maxOf { it.weight },
                )
            }
            .filter { it.text.isNotBlank() }

    private fun normalizeText(text: String): String = text
        .trim()
        .uppercase()
        .replace(Regex("\\s+"), " ")

    private fun normalizeVote(ch: Char): Char = if (ch.isWhitespace()) ' ' else ch.uppercaseChar()

    /** 去首尾空格并折叠连续空格，同时保持 CharEvidence 下标与最终文本完全一致。 */
    private fun compactSpaces(chars: List<CharEvidence>): List<CharEvidence> {
        val out = mutableListOf<CharEvidence>()
        for (ev in chars) {
            if (ev.char == ' ' && (out.isEmpty() || out.last().char == ' ')) continue
            out += ev
        }
        while (out.lastOrNull()?.char == ' ') out.removeAt(out.lastIndex)
        return out
    }

    /**
     * 把 [candidate] 对齐到 [reference] 的每个字符位。回溯优先对角线，保证相同长度的
     * `3332/3552` 直接逐位比较；删除产生 null 空票，插入字符不挤动后续位置。
     */
    private fun alignToReference(reference: String, candidate: String): Array<Char?> {
        val rows = reference.length + 1
        val cols = candidate.length + 1
        val dp = Array(rows) { IntArray(cols) }
        for (i in 0 until rows) dp[i][0] = i
        for (j in 0 until cols) dp[0][j] = j
        for (i in 1 until rows) {
            for (j in 1 until cols) {
                val replace = dp[i - 1][j - 1] + if (reference[i - 1] == candidate[j - 1]) 0 else 1
                dp[i][j] = minOf(replace, dp[i - 1][j] + 1, dp[i][j - 1] + 1)
            }
        }

        val out = arrayOfNulls<Char>(reference.length)
        var i = reference.length
        var j = candidate.length
        while (i > 0 || j > 0) {
            val diagonal = if (i > 0 && j > 0) {
                dp[i - 1][j - 1] + if (reference[i - 1] == candidate[j - 1]) 0 else 1
            } else Int.MAX_VALUE
            when {
                i > 0 && j > 0 && dp[i][j] == diagonal -> {
                    out[i - 1] = candidate[j - 1]
                    i--; j--
                }
                i > 0 && dp[i][j] == dp[i - 1][j] + 1 -> i--
                else -> j--
            }
        }
        return out
    }
}
