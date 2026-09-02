package com.wearable.inspection.mobile.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钢印分析器候选管线剪枝纯函数 JVM 单测：
 * 候选优先级排序、核心候选判定、早停判据、Levenshtein 距离。
 * 只测 companion object 纯函数，不触发 ML Kit 初始化（TextRecognition.getClient
 * 在无 Android Context 时抛 MlKitContext 未初始化）。
 */
class SteelStampOcrAnalyzerTest {

    private fun line(text: String, top: Float, bottom: Float) =
        OcrLineBox(text, 0.1f, top, 0.9f, bottom)

    private fun rec(
        name: String,
        rows: List<Pair<String, Float>>, // text to centerY
        elapsed: Long = 100L,
    ) = CandidateRecognition(
        candidateName = name,
        lines = rows.map { (text, y) ->
            val h = 0.06f
            line(text, y - h / 2f, y + h / 2f).copy(text = text)
        },
        rawText = rows.joinToString(" "),
        elapsedMs = elapsed,
    )

    // ---------- candidatePriority（优先级队列：增强主线在前） ----------

    @Test
    fun `candidate priority orders core enhancement before complement`() {
        val names = listOf(
            "pos-clahe", "inv-clahe", "pos-unsharp", "inv-unsharp",
            "pos-gamma", "inv-gamma", "pos-adaptive", "inv-adaptive",
        )
        assertEquals(names, names.sortedBy { SteelStampOcrAnalyzer.candidatePriority(it) })
    }

    @Test
    fun `candidate priority is stable and total`() {
        // 8 个候选必须映射到 8 个连续不重复的优先级
        val names = listOf(
            "pos-clahe", "inv-clahe", "pos-unsharp", "inv-unsharp",
            "pos-gamma", "inv-gamma", "pos-adaptive", "inv-adaptive",
        )
        val prios = names.map { SteelStampOcrAnalyzer.candidatePriority(it) }.sorted()
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), prios)
    }

    // ---------- isCoreCandidate（增强主线判定） ----------

    @Test
    fun `core candidates are clahe and unsharp`() {
        assertTrue(SteelStampOcrAnalyzer.isCoreCandidate("pos-clahe"))
        assertTrue(SteelStampOcrAnalyzer.isCoreCandidate("inv-clahe"))
        assertTrue(SteelStampOcrAnalyzer.isCoreCandidate("pos-unsharp"))
        assertTrue(SteelStampOcrAnalyzer.isCoreCandidate("inv-unsharp"))
        assertFalse(SteelStampOcrAnalyzer.isCoreCandidate("pos-gamma"))
        assertFalse(SteelStampOcrAnalyzer.isCoreCandidate("inv-adaptive"))
        // 行级复识别是同一候选的证据扩展，不重复计数
        assertFalse(SteelStampOcrAnalyzer.isCoreCandidate("pos-clahe-row"))
    }

    // ---------- shouldEarlyExit（主线一致无分歧 → 跳过互补候选） ----------

    @Test
    fun `early exit when core candidates agree on rows and text`() {
        val rows = listOf("BMW 3332 6894228-02", "230447 10 CN", "16924 AA 0050")
        val y = listOf(0.13f, 0.25f, 0.37f)
        val recs = listOf(
            rec("pos-clahe", rows.zip(y)),
            rec("inv-clahe", rows.zip(y)),
            rec("pos-unsharp", rows.zip(y)),
        )
        assertTrue(SteelStampOcrAnalyzer.shouldEarlyExit(recs))
    }

    @Test
    fun `no early exit when candidates disagree on characters`() {
        // 真机形态：W→N、3→5 系统性误读 + 行序错位（pos-clahe 整行漏检 L1）
        // —— 候选文本重合度低，必须保留互补候选投票，不能早停
        val recs = listOf(
            rec("pos-clahe", listOf("230447 0" to 0.25f, "16924A A 00" to 0.37f)),
            rec("inv-clahe", listOf("BMN 3537 (894228- 02" to 0.13f, "230447 C EH" to 0.25f, "16924AA00" to 0.37f)),
            rec("pos-unsharp", listOf("BMN S33 6694228 07" to 0.13f, "230447 I0 CH" to 0.25f, "16924A A 00" to 0.37f)),
        )
        assertFalse(SteelStampOcrAnalyzer.shouldEarlyExit(recs))
    }

    @Test
    fun `no early exit with too few valid candidates`() {
        val rows = listOf("BMW 3332 6894228-02", "230447 10 CN", "16924 AA 0050")
        // 只有 1 个候选识别成功 → 证据不足，不早停
        val one = listOf(rec("pos-clahe", rows.mapIndexed { i, t -> t to 0.13f + i * 0.12f }))
        assertFalse(SteelStampOcrAnalyzer.shouldEarlyExit(one))
        // 候选识别了但行数太少（单行碎片）→ 不早停
        val sparse = listOf(
            rec("pos-clahe", rows.mapIndexed { i, t -> t to 0.13f + i * 0.12f }),
            rec("inv-clahe", listOf("230447 10 CN" to 0.25f)),
        )
        assertFalse(SteelStampOcrAnalyzer.shouldEarlyExit(sparse))
    }

    @Test
    fun `no early exit when row counts spread too far`() {
        // 一个候选 3 行、另一个 1 行 → 行数跨度 2 > 1（有候选整行漏检）→ 不早停
        val recs = listOf(
            rec("pos-clahe", listOf("BMW 3332 6894228-02" to 0.13f, "230447 10 CN" to 0.25f, "16924 AA 0050" to 0.37f)),
            rec("inv-clahe", listOf("BMW 3332 6894228-02" to 0.13f)),
        )
        assertFalse(SteelStampOcrAnalyzer.shouldEarlyExit(recs))
    }

    @Test
    fun `no early exit on empty recognitions`() {
        assertFalse(SteelStampOcrAnalyzer.shouldEarlyExit(emptyList()))
    }

    // ---------- levenshteinDistance（早停一致性基础） ----------

    @Test
    fun `levenshtein distance basics`() {
        assertEquals(0, SteelStampOcrAnalyzer.levenshteinDistance("BMW 3332", "BMW 3332"))
        assertEquals(1, SteelStampOcrAnalyzer.levenshteinDistance("BMW 3332", "BMN 3332"))
        assertEquals(2, SteelStampOcrAnalyzer.levenshteinDistance("BMW 3332", "BMN 3532"))
        // 插入空格 + 插入 0 = 2（与融合行错位观察一致）
        assertEquals(2, SteelStampOcrAnalyzer.levenshteinDistance("16924 AA 0050", "16924 A A 000"))
        assertEquals(0, SteelStampOcrAnalyzer.levenshteinDistance("", ""))
        assertEquals(5, SteelStampOcrAnalyzer.levenshteinDistance("", "ABCDE"))
    }
}
