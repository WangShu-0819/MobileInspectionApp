package com.wearable.inspection.mobile.dpm

/**
 * DPM 重型网格重建的 Data Matrix 尺寸模式（高级设置，纯逻辑无 Android 依赖，可 JVM 单测）。
 *
 * 语义：
 * - [AUTO]：默认，网格候选按 16×16 / 18×18 / 20×20 三个维度配额生成并交错解码；
 * - [DIM_16] / [DIM_18] / [DIM_20]：固定尺寸，只生成指定维度的网格候选。
 *
 * 该模式**只约束 ImportedDpmScanner 重型网格重建路径**；快速路径（中心 ROI/全图 ZXing、
 * ML Kit）与 DpmPreprocessor 多候选预处理不做尺寸区分（ZXing/ML Kit 自身自适应尺寸）。
 *
 * 候选配额（防单一 dimension 占满候选列表 / 吃掉全部时间预算）：
 * - AUTO 普通网格：每尺寸 Top 8（3 尺寸共 24）；旋转网格：每尺寸 Top 4（共 12）；
 * - 固定模式普通网格：该尺寸 Top 24；旋转网格：该尺寸 Top 12。
 * 配额由 [gridQuotaPerDimension] / [rotatedQuotaPerDimension] 纯函数给出（按尺寸数
 * 是否 >1 判断 AUTO/固定，与 [dimensions] 映射一致），解码顺序由 [capPerDimension] +
 * [interleave] 交错（各尺寸第 1 名 → 各尺寸第 2 名 → ……，保证公平、18×18 不独占预算）。
 */
enum class DpmDimensionMode(val label: String) {
    AUTO("自动（16/18/20）"),
    DIM_16("16×16"),
    DIM_18("18×18"),
    DIM_20("20×20");

    /** 该模式对应的网格重建尝试尺寸（AUTO = 16/18/20 全尝试，固定 = 单尺寸） */
    fun dimensions(): IntArray = when (this) {
        AUTO -> intArrayOf(16, 18, 20)
        DIM_16 -> intArrayOf(16)
        DIM_18 -> intArrayOf(18)
        DIM_20 -> intArrayOf(20)
    }

    companion object {
        /** 普通网格每尺寸候选配额：AUTO（多尺寸）8 条/尺寸，固定（单尺寸）24 条 */
        const val AUTO_GRID_PER_DIMENSION = 8
        const val FIXED_GRID_PER_DIMENSION = 24

        /** 旋转网格每尺寸候选配额：AUTO（多尺寸）4 条/尺寸，固定（单尺寸）12 条 */
        const val AUTO_ROTATED_PER_DIMENSION = 4
        const val FIXED_ROTATED_PER_DIMENSION = 12

        /**
         * SharedPreferences 原始值 → 尺寸模式。null/非法/历史值一律安全回退 [AUTO]
         * （历史版本无该 key；值非法时按名字匹配失败即回退，绝不抛异常）。
         */
        fun parse(raw: String?): DpmDimensionMode =
            entries.firstOrNull { it.name == raw } ?: AUTO
    }
}

/**
 * 普通网格每尺寸候选配额（纯函数）：多尺寸（AUTO 语义）每尺寸 8 条、单尺寸（固定模式）
 * 24 条。调用方传入的 dimensions 数量决定模式语义，与 [DpmDimensionMode.dimensions] 一致。
 */
fun gridQuotaPerDimension(dimensionCount: Int): Int =
    if (dimensionCount > 1) DpmDimensionMode.AUTO_GRID_PER_DIMENSION
    else DpmDimensionMode.FIXED_GRID_PER_DIMENSION

/**
 * 旋转网格每尺寸候选配额（纯函数）：多尺寸（AUTO 语义）每尺寸 4 条、单尺寸（固定模式）
 * 12 条。
 */
fun rotatedQuotaPerDimension(dimensionCount: Int): Int =
    if (dimensionCount > 1) DpmDimensionMode.AUTO_ROTATED_PER_DIMENSION
    else DpmDimensionMode.FIXED_ROTATED_PER_DIMENSION

/**
 * 按维度分组保留配额（纯函数，输入需已按分数降序）：每个维度最多保留 [perDimension]
 * 条，组内保持输入顺序，分组顺序 = 该维度首个候选在输入中的出现顺序。
 * 返回 List<List<T>>（一维一组），供 [interleave] 交错。
 */
fun <T> capPerDimension(sorted: List<T>, dimensionOf: (T) -> Int, perDimension: Int): List<List<T>> {
    val groups = LinkedHashMap<Int, MutableList<T>>()
    for (item in sorted) {
        val group = groups.getOrPut(dimensionOf(item)) { ArrayList(perDimension) }
        if (group.size < perDimension) group.add(item)
    }
    return groups.values.map { it.toList() }
}

/**
 * 分组交错（round-robin，纯函数）：各分组第 1 名 → 各分组第 2 名 → ……
 * 保证 AUTO 下解码顺序跨尺寸公平（18×18 不独占时间预算）。
 */
fun <T> interleave(groups: List<List<T>>): List<T> {
    if (groups.isEmpty()) return emptyList()
    val maxLen = groups.maxOf { it.size }
    val out = ArrayList<T>()
    for (rank in 0 until maxLen) {
        for (group in groups) {
            if (rank < group.size) out.add(group[rank])
        }
    }
    return out
}
