package com.wearable.inspection.mobile.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 钢印文本区自动定位聚类逻辑 JVM 单测（纯几何，无 ML Kit/Android 依赖）。
 * 定位层：无 UI 引导框时把降采样整图检出的文本行聚成「钢印块」包围盒。
 */
class StampRegionLocatorTest {

    private val locator = StampRegionLocator()

    private fun line(text: String, left: Float, top: Float, right: Float, bottom: Float) =
        OcrLineBox(text, left, top, right, bottom)

    // ---------- 三行钢印块（典型版式：x 重叠、行距 ≈1.3 倍行高） ----------

    @Test
    fun `three stacked lines with x overlap form one region`() {
        val lines = listOf(
            line("BMW 3332 6894228-02", 0.10f, 0.10f, 0.90f, 0.17f),
            line("230447 10 CN", 0.12f, 0.20f, 0.88f, 0.27f),
            line("16924 AA 0050", 0.15f, 0.30f, 0.85f, 0.37f),
        )
        val region = locator.clusterToRegion(lines, 1000, 1000)
        assertNotNull(region)
        assertEquals(0.10f, region!!.left, 0.001f)
        assertEquals(0.10f, region.top, 0.001f)
        assertEquals(0.90f, region.right, 0.001f)
        assertEquals(0.37f, region.bottom, 0.001f)
    }

    // ---------- 单行太短不构成钢印块（<2 行且 <8 字符） ----------

    @Test
    fun `single short line is not a stamp region`() {
        val lines = listOf(line("OPEN", 0.30f, 0.50f, 0.60f, 0.56f))
        assertNull(locator.clusterToRegion(lines, 1000, 1000))
    }

    @Test
    fun `single long line can be a stamp region`() {
        val lines = listOf(line("BMW3332 6894228-02", 0.20f, 0.40f, 0.80f, 0.47f))
        val region = locator.clusterToRegion(lines, 1000, 1000)
        assertNotNull(region)
        assertEquals(0.20f, region!!.left, 0.001f)
        assertEquals(0.47f, region.bottom, 0.001f)
    }

    // ---------- 跨 TextBlock 联合：首行与下两行行距大被 ML Kit 拆块 ----------

    @Test
    fun `header line in separate block unions with stacked lines`() {
        // 模拟真机：首行（BMW…）与下两行行距 ≈2 倍行高 → 阶段 1 不并入同链
        // （yGap 0.12 > 1.5×0.07）；阶段 2 链间联合必须把它并回同一钢印块
        val lines = listOf(
            line("BMW 3332 6894228-02", 0.10f, 0.10f, 0.90f, 0.17f),
            line("230447 10 CN", 0.12f, 0.22f, 0.88f, 0.29f),
            line("16924 AA 0050", 0.15f, 0.32f, 0.85f, 0.39f),
        )
        val region = locator.clusterToRegion(lines, 1000, 1000)
        assertNotNull(region)
        // Union 后覆盖全部 3 行（含首行），不漏行
        assertEquals(0.10f, region!!.top, 0.001f)
        assertEquals(0.39f, region.bottom, 0.001f)
        assertEquals(0.10f, region.left, 0.001f)
        assertEquals(0.90f, region.right, 0.001f)
    }

    @Test
    fun `separated blocks with weak x overlap do not union`() {
        // 首行块与下方块 x 重叠仅 ~33%（<40%），gap=3.3×行高 → 阈值恒 40% → 不合并
        val lines = listOf(
            line("BMW 3332 6894228-02", 0.10f, 0.10f, 0.50f, 0.17f),
            line("230447 10 CN", 0.30f, 0.40f, 0.90f, 0.47f),
        )
        val region = locator.clusterToRegion(lines, 1000, 1000)
        // 两块均单行但字符 ≥8 → 各自构成钢印块；互不合并，取总字符数多者
        //（首行 18 字符 > 下行 11 字符 → 首行块胜出，同时验证「字符数优先」）
        assertNotNull(region)
        assertEquals(0.10f, region!!.top, 0.001f)
        assertEquals(0.50f, region.right, 0.001f)
    }

    @Test
    fun `partial header with tight gap unions despite weak x overlap`() {
        // 真机样本：ML Kit 首行「BMW 3332 6894228-02」只检出右半「6894228 07」
        //（x 范围 0.49-0.88），与下两行 x 重叠仅 21%（<40%）；但 gap=0 紧贴 →
        // 阈值联动放宽到 15% → 三行必须 Union 成同一钢印块（首行不再被丢）
        val lines = listOf(
            line("6894228 07", 0.49f, 0.43f, 0.88f, 0.49f),
            line("250447 0 CH", 0.12f, 0.49f, 0.59f, 0.55f),
            line("1692400", 0.14f, 0.55f, 0.58f, 0.59f),
        )
        val region = locator.clusterToRegion(lines, 1000, 1000)
        assertNotNull(region)
        // Union 覆盖全部 3 行（含被部分检出的首行）
        assertEquals(0.12f, region!!.left, 0.001f)
        assertEquals(0.43f, region.top, 0.001f)
        assertEquals(0.88f, region.right, 0.001f)
        assertEquals(0.59f, region.bottom, 0.001f)
    }

    // ---------- 多块并存取总字符数最多者 ----------

    @Test
    fun `pick cluster with most lines when several exist`() {
        val lines = listOf(
            // 两块：左上角 2 行小字 + 中央 3 行钢印
            line("L1", 0.05f, 0.05f, 0.35f, 0.09f),
            line("L2", 0.05f, 0.10f, 0.35f, 0.14f),
            line("BMW 3332", 0.20f, 0.40f, 0.80f, 0.47f),
            line("230447 10 CN", 0.22f, 0.50f, 0.78f, 0.57f),
            line("16924 AA 0050", 0.25f, 0.60f, 0.75f, 0.67f),
        )
        val region = locator.clusterToRegion(lines, 1000, 1000)
        assertNotNull(region)
        assertEquals(0.20f, region!!.left, 0.001f) // 中央钢印块，而非左上角 2 行块
        assertEquals(0.40f, region.top, 0.001f)
    }

    // ---------- x 不重叠的堆叠行不合并 ----------

    @Test
    fun `lines without x overlap are separate clusters`() {
        val lines = listOf(
            line("BMW 3332", 0.05f, 0.20f, 0.40f, 0.27f),
            line("230447", 0.60f, 0.20f, 0.95f, 0.27f),
        )
        // 两行 x 不重叠（左/右错开）→ 各自独立成簇；每簇单行且字符 <8（BMW3332=7、
        // 230447=6）→ 均不构成钢印块
        assertNull(locator.clusterToRegion(lines, 1000, 1000))
    }

    // ---------- 空输入 ----------

    @Test
    fun `empty lines yield null`() {
        assertNull(locator.clusterToRegion(emptyList(), 1000, 1000))
    }

    // ---------- 归一化坐标到原图缩放（locate 的纯几何部分） ----------

    @Test
    fun `scaled region maps back to source pixels with margin`() {
        val small = listOf(
            line("BMW 3332", 0.20f, 0.40f, 0.80f, 0.47f),
            line("230447 10 CN", 0.22f, 0.50f, 0.78f, 0.57f),
        )
        // clusterToRegion 输出归一化（0..1）块；locate() 内乘**原图尺寸**映射回原图像素
        val region = locator.clusterToRegion(small, 1200, 1600) ?: return
        val scaled = RoiBox(
            region.left * 3000f,
            region.top * 4000f,
            region.right * 3000f,
            region.bottom * 4000f,
        ).expandCentered(StampRegionLocator.LOCATE_MARGIN_FRACTION, 3000, 4000)
        // 中心点守恒：归一化块中心 × 原图尺寸 = 原图块中心
        val expCenterX = (region.left + region.right) / 2f * 3000f
        val expCenterY = (region.top + region.bottom) / 2f * 4000f
        assertEquals(expCenterX, scaled.left + scaled.width / 2f, 3f)
        assertEquals(expCenterY, scaled.top + scaled.height / 2f, 3f)
        // 外扩 8% 后仍在原图内
        assert(scaled.left >= 0f && scaled.right <= 3000f)
        assert(scaled.top >= 0f && scaled.bottom <= 4000f)
    }
}
