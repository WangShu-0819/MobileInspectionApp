package com.wearable.inspection.mobile.ocr

/**
 * 几何行聚类（纯逻辑，JVM 单测覆盖）。
 *
 * 输入：单候选内 ML Kit 检出的文本行（归一化 0..1 坐标框）。
 * 输出：按 y 中心自顶向下的行聚类 —— 每个聚类对应钢印的一行文本。
 *
 * 聚类准则（对齐规格「Y-center/baseline/X-overlap/line-spacing」）：
 * - **y 邻近**：两行 y-center 间距 ≤ 较大行高的 60% 视为同行（ML Kit 常把同一行
 *   拆成多个 fragment，跨行误并需要 y 距离超过一个行高才发生）；
 * - **x 重叠**：同行 fragment 应水平重叠或相邻（同一行文字水平连续）；
 * - 聚类间行距（line-spacing）不做等距假设 —— 钢印行距可能不均，只按局部判据分组。
 *
 * 跨候选对齐（供字符融合）：各候选的聚类再按「y-center 最近」配对（见
 * [SteelStampCharFusion]，候选间无显式几何对齐，只依赖 ML Kit 各行 y-center）。
 */
object SteelStampLineCluster {

    /**
     * 行聚类：贪心自顶向下。返回聚类列表（每项 = 同行 fragment 集合，已按 x 排序）。
     * 空输入返回空列表。
     */
    fun cluster(boxes: List<OcrLineBox>): List<List<OcrLineBox>> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedBy { it.centerY }
        val clusters = mutableListOf<MutableList<OcrLineBox>>()
        for (line in sorted) {
            // 找 y 邻近（≤ 60% 行高）且 x 重叠（≥ 15%）或水平相邻（双向间隙 ≤ 聚类宽 8%）的现有聚类
            val target = clusters.firstOrNull { cluster ->
                val ref = cluster.maxBy { it.centerY }
                val gapOk = line.yGapTo(ref) <= maxOf(ref.height, line.height) * 0.6f
                val clusterLeft = cluster.minOf { it.left }
                val clusterRight = cluster.maxOf { it.right }
                val refWidth = clusterRight - clusterLeft
                val xOk = line.xOverlapWith(ref) >= 0.15f ||
                    (line.left <= clusterRight + refWidth * 0.08f && line.right >= clusterLeft - refWidth * 0.08f)
                gapOk && xOk
            }
            if (target != null) target.add(line) else clusters.add(mutableListOf(line))
        }
        // 同行内按 x 排序；整体按 y-center 排序（自上而下）
        clusters.forEach { it.sortBy { c -> c.left } }
        return clusters.sortedBy { cluster -> cluster.map { it.centerY }.average() }
    }

    /**
     * 跨候选行配对：给定一个候选聚类（y-center 均值），从另一候选的全部行中
     * 找 y-center 最近且间距 ≤ [maxGap] 的行。maxGap 默认 50% 行高（ROI 尺度下
     * 抗轻微候选间 y 漂移）。
     */
    fun nearestLine(candidateLines: List<OcrLineBox>, clusterCenterY: Float, maxGapFraction: Float = 0.5f): OcrLineBox? {
        if (candidateLines.isEmpty()) return null
        val closest = candidateLines.minByOrNull { kotlin.math.abs(it.centerY - clusterCenterY) } ?: return null
        val maxGap = maxOf(closest.height, 0.01f) * maxGapFraction
        return if (kotlin.math.abs(closest.centerY - clusterCenterY) <= maxGap) closest else null
    }
}
