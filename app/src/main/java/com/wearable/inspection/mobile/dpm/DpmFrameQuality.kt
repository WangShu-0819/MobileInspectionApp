package com.wearable.inspection.mobile.dpm

/**
 * DPM 网格任务提交前的轻量帧质量门控结果（纯数据 + 诊断指标）。
 * [passed] = false 时 [reason] 说明拒绝原因（只做**保守拒绝**：严重过曝/欠曝、
 * 动态范围过小、极度失焦低纹理；不尝试在此定位 DPM，不因低对比金属码而误拒）。
 */
data class DpmFrameQuality(
    val passed: Boolean,
    val reason: String,
    val p05: Int,
    val p95: Int,
    val overexposedRatioPercent: Int,
    val underexposedRatioPercent: Int,
    val meanAbsGradient: Float,
) {
    /** 简短诊断串（一行日志，不含任何图像/payload 内容） */
    fun shortMetrics(): String =
        "p05=$p05 p95=$p95 over=${overexposedRatioPercent}% under=${underexposedRatioPercent}% grad=${"%.2f".format(meanAbsGradient)}"
}

/**
 * 帧质量门控（纯 JVM，可单测）：判断一帧灰度图是否**可能**包含可解的 DPM 码。
 *
 * 只做保守拒绝（真机故障帧：过曝、失焦、空桌面），绝不尝试完整定位 DPM：
 * 1. 严重过曝：≥ [OVEREXPOSED_VALUE] 的像素占比 ≥ [RATIO_REJECT_PERCENT]；
 * 2. 严重欠曝：≤ [UNDEREXPOSED_VALUE] 的像素占比 ≥ [RATIO_REJECT_PERCENT]；
 * 3. 动态范围过小：p95 - p05 < [MIN_DYNAMIC_RANGE]（无对比度，白板/纯色面）；
 * 4. 极低纹理/极度失焦：平均绝对梯度 < [MIN_MEAN_ABS_GRADIENT]。
 *
 * 与工件颜色无关：所有指标对物理极性（黑件/白件）不敏感 —— 曝光占比看两端、
 * 动态范围与梯度取绝对值，暗底亮码与亮底暗码对称。阈值刻意放宽：低对比金属
 * DPM（幅值 ~±16）与轻微噪声（σ≈2）都能通过，只有"无内容"的帧被拒。
 * 指标在 ≤ [MAX_SAMPLES] 的均匀子采样上计算（单帧 <1ms），不建多帧缓存。
 */
object DpmFrameQualityGate {

    /** 过曝判定阈值：亮度 ≥ 该值的像素视为过曝 */
    const val OVEREXPOSED_VALUE = 250

    /** 欠曝判定阈值：亮度 ≤ 该值的像素视为欠曝 */
    const val UNDEREXPOSED_VALUE = 5

    /** 过曝/欠曝占比达到该百分比即拒绝（保守：只有大面积死白/死黑才拒） */
    const val RATIO_REJECT_PERCENT = 45

    /** 最小动态范围（p95 - p05）：低于该值判定"无对比度"（纯色/白板/空画面） */
    const val MIN_DYNAMIC_RANGE = 16

    /** 最小平均绝对梯度（|gx|+|gy| 均值，0-255 亮度域）：低于该值判定"极低纹理/
     *  极度失焦"。纯高斯噪声 σ=2 的梯度均值 ≈2.3，低对比点阵 ≈3+，重度模糊 ≈1 以下 */
    const val MIN_MEAN_ABS_GRADIENT = 1.5f

    /** 指标计算的最大采样点数（均匀步长子采样，单帧成本 <1ms） */
    const val MAX_SAMPLES = 65536

    /**
     * 判断 [gray]（w×h，0-255 亮度）是否通过质量门控。
     * @throws IllegalArgumentException gray.size != w * h
     */
    fun check(gray: IntArray, w: Int, h: Int): DpmFrameQuality {
        require(gray.size == w * h) { "gray.size=${gray.size} != w*h=${w * h}" }
        val n = gray.size
        val step = maxOf(1, kotlin.math.sqrt(n.toDouble() / MAX_SAMPLES).toInt())

        // 256 桶直方图 → p05/p95/过曝欠曝占比（子采样，O(n/step)）
        val hist = IntArray(256)
        var overCount = 0
        var underCount = 0
        var sampled = 0
        var gxSum = 0L
        var gySum = 0L
        var i = 0
        while (i < n) {
            val v = gray[i] and 0xFF
            hist[v]++
            sampled++
            if (v >= OVEREXPOSED_VALUE) overCount++
            if (v <= UNDEREXPOSED_VALUE) underCount++
            // 水平/垂直绝对梯度（跳过行边界，用前一步子采样点）
            if (i + step < n && i / w == (i + step) / w) {
                gxSum += kotlin.math.abs((gray[i + step] and 0xFF) - v)
            }
            val down = i + step * w
            if (down < n) {
                gySum += kotlin.math.abs((gray[down] and 0xFF) - v)
            }
            i += step
        }

        val p05 = percentile(hist, sampled, 5)
        val p95 = percentile(hist, sampled, 95)
        val dynamicRange = p95 - p05
        val overPercent = overCount * 100 / sampled
        val underPercent = underCount * 100 / sampled
        // 平均绝对梯度 = (gx + gy) / 采样数（gy 计入 down< n 的采样点）
        val meanGrad = (gxSum + gySum).toFloat() / sampled

        val reason = when {
            overPercent >= RATIO_REJECT_PERCENT ->
                "severe overexposure (over=${overPercent}%)"
            underPercent >= RATIO_REJECT_PERCENT ->
                "severe underexposure (under=${underPercent}%)"
            dynamicRange < MIN_DYNAMIC_RANGE ->
                "dynamic range too small (range=$dynamicRange)"
            meanGrad < MIN_MEAN_ABS_GRADIENT ->
                "blurred / low texture (grad=${"%.2f".format(meanGrad)})"
            else -> ""
        }
        return DpmFrameQuality(
            passed = reason.isEmpty(),
            reason = reason,
            p05 = p05,
            p95 = p95,
            overexposedRatioPercent = overPercent,
            underexposedRatioPercent = underPercent,
            meanAbsGradient = meanGrad,
        )
    }

    /** 直方图 → 第 [percent] 分位亮度（线性累积） */
    private fun percentile(hist: IntArray, total: Int, percent: Int): Int {
        if (total == 0) return 0
        val target = (total.toLong() * percent + 99) / 100
        var cum = 0L
        for (v in hist.indices) {
            cum += hist[v]
            if (cum >= target) return v
        }
        return 255
    }
}
