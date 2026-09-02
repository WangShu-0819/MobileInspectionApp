package com.wearable.inspection.mobile.ocr

/**
 * BMW 三行钢印的结构/字符类型规范化（纯逻辑）。
 *
 * 规则只描述字段类型和分隔符，不保存任何照片真值：
 * `BMW AAAA NNNNNNN-NN / NNNNNN NN AA / NNNNN AA NNNN`。
 * 具体字母或数字仍来自 OCR 证据；同类型歧义（3/5、N/H）不会被规则强改。
 */
internal object BmwThreeLineFormatter {

    fun format(lines: List<SteelStampLine>): List<SteelStampLine> {
        if (lines.isEmpty() || !looksLikeBmw(lines.first())) return lines
        val first = formatFirst(lines[0])
        if (lines.size != 3) return listOf(first) + lines.drop(1)
        return listOf(
            first,
            formatTyped(lines[1], intArrayOf(6, 2, 2), arrayOf(FieldType.DIGIT, FieldType.DIGIT, FieldType.LETTER)),
            formatTyped(lines[2], intArrayOf(5, 2, 4), arrayOf(FieldType.DIGIT, FieldType.LETTER, FieldType.DIGIT)),
        )
    }

    private fun looksLikeBmw(line: SteelStampLine): Boolean {
        val glyphs = glyphs(line)
        if (glyphs.size < 15) return false
        val brand = glyphs.take(3).joinToString("") { it.char.uppercaseChar().toString() }
        val brandClose = editDistance(brand, "BMW") <= 1 || glyphs.getOrNull(2)?.votes?.keys?.any { it.uppercaseChar() == 'W' } == true
        return brandClose && glyphs.takeLast(9).all { digitOf(it.char) != null }
    }

    /**
     * 诊断（纯逻辑，供分析器在「格式化未生效」时打日志）：返回首行未格式化的原因，
     * null = 格式化已生效。真机定位：融合行结构与预期不符时的逐项根因。
     */
    internal fun formatWhyUnchanged(lines: List<SteelStampLine>): String? {
        if (lines.isEmpty()) return "empty"
        val first = lines.first()
        val glyphs = glyphs(first)
        if (glyphs.size < 15) return "glyphs=${glyphs.size} < 15 text=[${first.text}] chars=${first.chars.size} len=${first.text.length}"
        val brand = glyphs.take(3).joinToString("") { it.char.uppercaseChar().toString() }
        val brandClose = editDistance(brand, "BMW") <= 1 ||
            glyphs.getOrNull(2)?.votes?.keys?.any { it.uppercaseChar() == 'W' } == true
        if (!brandClose) return "brand=[$brand] 非 BMW 邻近（ed=${editDistance(brand, "BMW")}, 无 W 票）"
        val tail = glyphs.takeLast(9).map { it.char.uppercaseChar() }
        if (tail.any { digitOf(it) == null }) return "brand=[$brand] tail=[${tail.joinToString("")}] 含非数字"
        val model = glyphs.subList(3, glyphs.size - 9)
        if (model.size !in 3..5) return "brand=[$brand] model.size=${model.size} 不在 3..5"
        if (model.any { digitOf(it.char) == null }) return "brand=[$brand] model=[${model.joinToString("")}] 含非数字"
        return null
    }

    private fun formatFirst(line: SteelStampLine): SteelStampLine {
        val glyphs = glyphs(line)
        if (glyphs.size < 15) return line
        val partStart = glyphs.size - 9
        val model = glyphs.subList(3, partStart)
        if (model.size !in 3..5 || model.any { digitOf(it.char) == null }) return line

        val out = mutableListOf<CharEvidence>()
        val brand = glyphs.take(3)
        "BMW".forEachIndexed { index, expected -> out += expectedLiteral(brand[index], expected) }
        out += separator(' ')
        out += model.map { typed(it, FieldType.DIGIT) }
        out += separator(' ')
        val part = glyphs.takeLast(9).map { typed(it, FieldType.DIGIT) }
        out += part.take(7)
        out += separator('-')
        out += part.takeLast(2)
        return line.copy(text = out.text(), chars = out)
    }

    private fun formatTyped(
        line: SteelStampLine,
        lengths: IntArray,
        types: Array<FieldType>,
    ): SteelStampLine {
        val glyphs = glyphs(line)
        if (glyphs.size != lengths.sum()) return line
        val out = mutableListOf<CharEvidence>()
        var offset = 0
        lengths.forEachIndexed { field, length ->
            if (field > 0) out += separator(' ')
            repeat(length) { out += typed(glyphs[offset++], types[field]) }
        }
        return line.copy(text = out.text(), chars = out)
    }

    private fun glyphs(line: SteelStampLine): List<CharEvidence> {
        if (line.chars.size == line.text.length) return line.chars.filterNot { it.char.isWhitespace() || it.char == '-' }
        // 兼容旧结果/测试数据；降级证据保持 uncertain，绝不制造确定字符。
        return line.text.filterNot { it.isWhitespace() || it == '-' }.map { ch ->
            CharEvidence(ch, 0.5f, mapOf(ch to 1f), uncertain = true)
        }
    }

    private fun expectedLiteral(glyph: CharEvidence, expected: Char): CharEvidence {
        if (glyph.char.uppercaseChar() == expected) return glyph.copy(char = expected)
        val expectedVote = glyph.votes.entries.firstOrNull { it.key.uppercaseChar() == expected }?.value ?: return glyph
        val total = glyph.votes.values.sum().coerceAtLeast(expectedVote)
        return glyph.copy(char = expected, confidence = expectedVote / total, uncertain = true)
    }

    private fun typed(glyph: CharEvidence, type: FieldType): CharEvidence {
        val normalized = when (type) {
            FieldType.DIGIT -> digitOf(glyph.char)
            FieldType.LETTER -> letterOf(glyph.char)
        } ?: return glyph.copy(uncertain = true)
        return if (normalized == glyph.char.uppercaseChar()) {
            glyph.copy(char = normalized)
        } else {
            glyph.copy(char = normalized, uncertain = true)
        }
    }

    private fun digitOf(ch: Char): Char? = when (ch.uppercaseChar()) {
        in '0'..'9' -> ch
        'O', 'Q', 'D' -> '0'
        'I', 'L' -> '1'
        'Z' -> '2'
        'S', '$' -> '5'
        'G' -> '6'
        'B' -> '8'
        else -> null
    }

    private fun letterOf(ch: Char): Char? = when (ch.uppercaseChar()) {
        in 'A'..'Z' -> ch.uppercaseChar()
        '0' -> 'O'
        '1' -> 'I'
        '5' -> 'S'
        '8' -> 'B'
        else -> null
    }

    private fun separator(ch: Char) = CharEvidence(ch, 1f, mapOf(ch to 1f), uncertain = false)

    private fun List<CharEvidence>.text(): String = joinToString("") { it.char.toString() }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in a.indices) {
            cur[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                cur[j + 1] = minOf(prev[j + 1] + 1, cur[j] + 1, prev[j] + cost)
            }
            val swap = prev; prev = cur; cur = swap
        }
        return prev[b.length]
    }

    private enum class FieldType { DIGIT, LETTER }
}
