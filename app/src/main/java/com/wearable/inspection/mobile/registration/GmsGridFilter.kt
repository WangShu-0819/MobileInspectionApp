package com.wearable.inspection.mobile.registration

/**
 * 纯 Kotlin 网格运动统计 (Grid-based Motion Statistics, GMS) 滤波。
 *
 * 官方 matchGMS 位于 opencv_contrib/xfeatures2d，Android 官方 SDK 与桌面 openpnp
 * 均不含 contrib 模块 → 手写等价实现。原理（GMS 论文）：
 * 真实匹配的运动是局部平滑的 —— 若一对匹配 (a→b) 正确，则落在「a 的 3×3 网格邻域 ∩
 * b 的 3×3 网格邻域」内的其它匹配也应正确，该支持度远高于误匹配。按支持度阈值保留。
 *
 * 纯 Kotlin 实现，仅使用基础数据类型，JVM 单测无需加载 OpenCV 原生库。
 */
object GmsGridFilter {

    data class Point2D(val x: Double, val y: Double)

    /**
     * 对 Lowe 筛选后的匹配对做网格运动平滑性滤波。
     *
     * @param srcPts 模板侧特征点坐标（与匹配 queryIdx 对应）
     * @param dstPts 帧侧特征点坐标（与匹配 trainIdx 对应）
     * @param templateW/templateH 模板尺寸（模板侧网格划分基准）
     * @param sceneW/sceneH 场景尺寸（帧侧网格划分基准）
     * @return 与输入等长的保留标记数组（true = 支持度达标，进入几何估计）
     */
    fun filter(
        srcPts: List<Point2D>,
        dstPts: List<Point2D>,
        templateW: Int,
        templateH: Int,
        sceneW: Int,
        sceneH: Int,
        gridSize: Int = RegistrationConfig.GMS_GRID_SIZE,
        supportThreshold: Int = RegistrationConfig.GMS_SUPPORT_THRESHOLD,
    ): BooleanArray {
        if (srcPts.isEmpty() || srcPts.size != dstPts.size || gridSize <= 0) {
            return BooleanArray(srcPts.size)
        }

        val n = srcPts.size
        val g = gridSize
        val tRow = IntArray(n); val tCol = IntArray(n)
        val fRow = IntArray(n); val fCol = IntArray(n)
        for (i in 0 until n) {
            tCol[i] = cellCoord(srcPts[i].x, templateW, g)
            tRow[i] = cellCoord(srcPts[i].y, templateH, g)
            fCol[i] = cellCoord(dstPts[i].x, sceneW, g)
            fRow[i] = cellCoord(dstPts[i].y, sceneH, g)
        }

        // O(N) 装箱计数
        val counts = IntArray(g * g * g * g)
        for (i in 0 until n) {
            counts[(tRow[i] * g + tCol[i]) * g * g + (fRow[i] * g + fCol[i])]++
        }

        val keep = BooleanArray(n)
        for (i in 0 until n) {
            var support = 0
            for (dt in -1..1) {
                val tr = tRow[i] + dt
                if (tr < 0 || tr >= g) continue
                for (dc in -1..1) {
                    val tc = tCol[i] + dc
                    if (tc < 0 || tc >= g) continue
                    val tBase = (tr * g + tc) * g * g
                    for (df in -1..1) {
                        val fr = fRow[i] + df
                        if (fr < 0 || fr >= g) continue
                        for (dg in -1..1) {
                            val fc = fCol[i] + dg
                            if (fc < 0 || fc >= g) continue
                            support += counts[tBase + fr * g + fc]
                        }
                    }
                }
            }
            keep[i] = support - 1 >= supportThreshold
        }
        return keep
    }

    private fun cellCoord(v: Double, span: Int, gridSize: Int): Int =
        (if (span > 0) (v * gridSize / span).toInt() else 0).coerceIn(0, gridSize - 1)
}
