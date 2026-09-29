package com.wearable.inspection.mobile.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NMS（非极大值抑制）行为测试。
 *
 * 验证 NanoDetOutputDecoder 的 NMS 实现：
 * 1. 同类重叠候选被抑制（IoU > 0.6）
 * 2. 不同类候选独立保留
 * 3. 单候选不受 NMS 影响
 * 4. 低分候选被阈值过滤（NMS 之前）
 *
 * ## 与合成 decoder parity 的区别
 *
 * ExpModelParityInstrumentedTest 的合成 decoder parity 测试只包含单个候选，
 * 不验证 NMS 行为。本测试通过构造含多个重叠候选的合成张量，
 * 直接验证 Android 生产 decoder 的 NMS 逻辑。
 *
 * ## 合成张量构造方法
 *
 * 在 stride-8 网格上设置特定位置的类别分数和 DFL logits，
 * 使解码后的 bounding box 按预期重叠或分离。
 * DFL 编码：8 个 bin 的 logits → softmax → 加权期望 × stride = 像素距离。
 */
class NmsBehaviorTest {

    private val transform = NanoDetInputTransform(
        width = 416, height = 416,
        resizedWidth = 416, resizedHeight = 416,
        scaleX = 1.0, scaleY = 1.0,
    )

    // ── 辅助：创建全零张量 ──

    private fun zeroTensor(outputWidth: Int): FloatArray =
        FloatArray(outputWidth * NanoDetModelContract.OUTPUT_HEIGHT)

    /**
     * 在 stride-8 网格位置 (gridX, gridY) 设置类别分数和 DFL logits。
     * stride-8 的 featureSize = ceil(416/8) = 52，共 2704 个位置。
     * row = gridY * 52 + gridX。
     */
    private fun setStride8Position(
        tensor: FloatArray, outputWidth: Int,
        gridX: Int, gridY: Int,
        classScores: FloatArray,
        dflLogitsPerSide: Array<FloatArray>, // [4][8]，left/top/right/bottom
    ) {
        val featureSize = 52
        val row = gridY * featureSize + gridX
        val base = row * outputWidth

        // 类别分数
        for (c in classScores.indices) tensor[base + c] = classScores[c]

        // DFL logits（4 边 × 8 bin）
        val numClasses = classScores.size
        for (side in 0 until 4) {
            val sideBase = base + numClasses + side * 8
            for (bin in 0 until 8) tensor[sideBase + bin] = dflLogitsPerSide[side][bin]
        }
    }

    /**
     * 构造使 DFL 期望值 = targetPixels / stride 的 logits。
     * 使用 one-hot 在 targetBin 上，期望值恰好 = targetBin。
     * targetBin = targetPixels / stride。
     */
    private fun dflOneHot(targetBin: Int): FloatArray {
        require(targetBin in 0..7) { "targetBin must be 0..7, got $targetBin" }
        return FloatArray(8) { if (it == targetBin) 5f else 0f }
    }

    // ── 测试 1：同类重叠候选被 NMS 抑制 ──

    @Test
    fun `same-class overlapping candidates are suppressed by NMS`() {
        val classNames = Exp22BlackClassPolicy.classNames // 4 classes, outputWidth=36
        val outputWidth = 36
        val tensor = zeroTensor(outputWidth)

        // 位置 (0,0) stride-8：class 0 (Black Thread) = 0.9
        // DFL: 期望 bin=2 → 2*8=16px 距离 → box=[-16, -16, 16, 16]
        val dfl16 = dflOneHot(2)
        setStride8Position(tensor, outputWidth, 0, 0,
            classScores = floatArrayOf(0.9f, 0f, 0f, 0f),
            dflLogitsPerSide = arrayOf(dfl16, dfl16, dfl16, dfl16),
        )

        // 位置 (1,0) stride-8：class 0 (Black Thread) = 0.8
        // 相同 DFL → box=[8-16, -16, 8+16, 16] = [-8, -16, 24, 16]
        // 与第一个 box 重叠：IoU = 24*32 / (32*32 + 32*32 - 24*32) = 768/(1024+1024-768)=768/1280=0.6
        // 严格 IoU > 0.6 才抑制，IoU == 0.6 不抑制 → 两个都保留
        // 改用更大重叠：bin=3 → 24px → box=[-24,-24,24,24] 和 [-16,-24,32,24]
        // intersection = 16*48=768, area=48*48=2304 → IoU=768/(2304+2304-768)=768/3840=0.2 不够
        // 使用 bin=1 → 8px → box=[-8,-8,8,8] 和 [0,-8,16,8]
        // intersection = 8*16=128, area=16*16=256 → IoU=128/(256+256-128)=128/384=0.333 不够
        // 需要更近的位置或更大的框。
        // 改用同一位置但不同分数 → 同心框 → IoU=1.0
        setStride8Position(tensor, outputWidth, 0, 0,
            classScores = floatArrayOf(0.9f, 0f, 0f, 0f),
            dflLogitsPerSide = arrayOf(dfl16, dfl16, dfl16, dfl16),
        )

        // 第二个候选也放在 (0,0) 但通过不同 stride 来实现
        // 不行，不同 stride 的 row 计算方式不同。
        // 改用策略：在相邻位置用足够大的框使 IoU > 0.6
        // 位置 (0,0) 和 (2,0)：中心距离 16px
        // 框1: [-16,-16,16,16], 框2: [16-16,-16,16+16,16]=[0,-16,32,16]
        // intersection=16*32=512, area=32*32=1024 → IoU=512/(1024+1024-512)=512/1536=0.333 不够
        // 最终策略：位置 (0,0) 和 (1,0)，使用 bin=4 → 32px
        // 框1: [-32,-32,32,32], 框2: [8-32,-32,8+32,32]=[-24,-32,40,32]
        // intersection = 56*64=3584, area=64*64=4096
        // IoU = 3584/(4096+4096-3584) = 3584/4608 = 0.778 > 0.6 ✓
        val dfl32 = dflOneHot(4)
        setStride8Position(tensor, outputWidth, 0, 0,
            classScores = floatArrayOf(0.9f, 0f, 0f, 0f),
            dflLogitsPerSide = arrayOf(dfl32, dfl32, dfl32, dfl32),
        )
        setStride8Position(tensor, outputWidth, 1, 0,
            classScores = floatArrayOf(0.8f, 0f, 0f, 0f),
            dflLogitsPerSide = arrayOf(dfl32, dfl32, dfl32, dfl32),
        )

        val candidates = NanoDetOutputDecoder.decode(tensor, transform, classNames)

        // NMS 应抑制低分的那个（score=0.8），保留高分的（score=0.9）
        val threadCandidates = candidates.filter { it.classIndex == 0 }
        assertEquals(
            "同类重叠候选经 NMS 后应只保留 1 个",
            1, threadCandidates.size
        )
        assertEquals("保留的应为高分候选", 0.9f, threadCandidates[0].score, 0.01f)
    }

    // ── 测试 2：不同类候选独立保留 ──

    @Test
    fun `different-class candidates at different positions are independently preserved`() {
        val classNames = Exp22BlackClassPolicy.classNames
        val outputWidth = 36
        val tensor = zeroTensor(outputWidth)

        // NanoDet decoder 对每个位置做 argmax，只取最高分类别。
        // 因此不同类别必须放在不同位置。
        // 位置 (0,0)：class 0 (Black Thread) = 0.85
        val dfl4 = dflOneHot(4)
        setStride8Position(tensor, outputWidth, 0, 0,
            classScores = floatArrayOf(0.85f, 0f, 0f, 0f),
            dflLogitsPerSide = arrayOf(dfl4, dfl4, dfl4, dfl4),
        )
        // 位置 (5,5)：class 1 (Black Nutsert) = 0.75
        setStride8Position(tensor, outputWidth, 5, 5,
            classScores = floatArrayOf(0f, 0.75f, 0f, 0f),
            dflLogitsPerSide = arrayOf(dfl4, dfl4, dfl4, dfl4),
        )

        val candidates = NanoDetOutputDecoder.decode(tensor, transform, classNames)

        // 两个不同类别应各自保留（位置远，NMS 不跨类别）
        val threadCandidates = candidates.filter { it.classIndex == 0 }
        val nutsertCandidates = candidates.filter { it.classIndex == 1 }
        assertEquals("class 0 应保留 1 个", 1, threadCandidates.size)
        assertEquals("class 1 应保留 1 个", 1, nutsertCandidates.size)
    }

    // ── 测试 3：单候选不受 NMS 影响 ──

    @Test
    fun `single candidate passes through NMS unchanged`() {
        val classNames = Exp22BlackClassPolicy.classNames
        val outputWidth = 36
        val tensor = zeroTensor(outputWidth)

        val dfl4 = dflOneHot(4)
        setStride8Position(tensor, outputWidth, 5, 5,
            classScores = floatArrayOf(0.95f, 0f, 0f, 0f),
            dflLogitsPerSide = arrayOf(dfl4, dfl4, dfl4, dfl4),
        )

        val candidates = NanoDetOutputDecoder.decode(tensor, transform, classNames)
        assertEquals("单候选应原样保留", 1, candidates.size)
        assertEquals(0.95f, candidates[0].score, 0.01f)
    }

    // ── 测试 4：低分候选被阈值过滤（NMS 之前）──

    @Test
    fun `low-score candidates are filtered before NMS`() {
        val classNames = Exp22BlackClassPolicy.classNames
        val outputWidth = 36
        val tensor = zeroTensor(outputWidth)

        // 所有类别分数低于 CANDIDATE_THRESHOLD (0.05)
        val dfl4 = dflOneHot(4)
        setStride8Position(tensor, outputWidth, 3, 3,
            classScores = floatArrayOf(0.04f, 0.03f, 0.02f, 0.01f),
            dflLogitsPerSide = arrayOf(dfl4, dfl4, dfl4, dfl4),
        )

        val candidates = NanoDetOutputDecoder.decode(tensor, transform, classNames)
        assertTrue("低于阈值的候选应被过滤", candidates.isEmpty())
    }

    // ── 测试 5：边界阈值候选通过 ──

    @Test
    fun `candidate at exact threshold passes filtering`() {
        val classNames = Exp22BlackClassPolicy.classNames
        val outputWidth = 36
        val tensor = zeroTensor(outputWidth)

        val dfl4 = dflOneHot(4)
        setStride8Position(tensor, outputWidth, 3, 3,
            classScores = floatArrayOf(NanoDetModelContract.CANDIDATE_THRESHOLD, 0f, 0f, 0f),
            dflLogitsPerSide = arrayOf(dfl4, dfl4, dfl4, dfl4),
        )

        val candidates = NanoDetOutputDecoder.decode(tensor, transform, classNames)
        assertEquals("恰好等于阈值的候选应通过", 1, candidates.size)
    }

    // ── 测试 6：远距离同类候选不被 NMS 抑制 ──

    @Test
    fun `distant same-class candidates are not suppressed by NMS`() {
        val classNames = Exp22BlackClassPolicy.classNames
        val outputWidth = 36
        val tensor = zeroTensor(outputWidth)

        // 位置 (0,0) 和 (20,20)：中心距离很远，IoU ≈ 0
        val dfl2 = dflOneHot(2) // 16px 距离
        setStride8Position(tensor, outputWidth, 0, 0,
            classScores = floatArrayOf(0.9f, 0f, 0f, 0f),
            dflLogitsPerSide = arrayOf(dfl2, dfl2, dfl2, dfl2),
        )
        setStride8Position(tensor, outputWidth, 20, 20,
            classScores = floatArrayOf(0.8f, 0f, 0f, 0f),
            dflLogitsPerSide = arrayOf(dfl2, dfl2, dfl2, dfl2),
        )

        val candidates = NanoDetOutputDecoder.decode(tensor, transform, classNames)
        val threadCandidates = candidates.filter { it.classIndex == 0 }
        assertEquals("远距离同类候选应各自保留", 2, threadCandidates.size)
    }

    // ── 测试 7：不同类别高 IoU 重叠候选独立保留 ──

    @Test
    fun `different-class high-IoU overlapping candidates are both preserved`() {
        val classNames = Exp22BlackClassPolicy.classNames // 4 classes, outputWidth=36
        val outputWidth = 36
        val tensor = zeroTensor(outputWidth)

        // 位置 (0,0) 和 (1,0) stride-8，中心距离 = 8px。
        // 使用 bin=4 → DFL 期望 = 4 → 4*8 = 32px 距离。
        // class 0 at (0,0): box = clamp((0-32),0,416) ... = [0, 0, 32, 32]
        // class 1 at (1,0): box = clamp((8-32),0,416) ... = [0, 0, 40, 32]
        //
        // iouInclusive 计算（含 +1.0 调整）：
        //   x1 = max(0,0)=0, y1 = max(0,0)=0
        //   x2 = min(32,40)=32, y2 = min(32,32)=32
        //   intersection = (32-0+1)*(32-0+1) = 33*33 = 1089
        //   areaA = (32-0+1)*(32-0+1) = 1089
        //   areaB = (40-0+1)*(32-0+1) = 41*33 = 1353
        //   IoU = 1089 / (1089+1353-1089) = 1089/1353 ≈ 0.805 > 0.6
        //
        // NMS 按类别独立执行（byClass.flatMap(::nms)），不同类不互相抑制。
        // 断言两个候选均保留。
        val dfl32 = dflOneHot(4)
        setStride8Position(tensor, outputWidth, 0, 0,
            classScores = floatArrayOf(0.9f, 0f, 0f, 0f),  // class 0
            dflLogitsPerSide = arrayOf(dfl32, dfl32, dfl32, dfl32),
        )
        setStride8Position(tensor, outputWidth, 1, 0,
            classScores = floatArrayOf(0f, 0.85f, 0f, 0f),  // class 1
            dflLogitsPerSide = arrayOf(dfl32, dfl32, dfl32, dfl32),
        )

        val candidates = NanoDetOutputDecoder.decode(tensor, transform, classNames)

        val class0Candidates = candidates.filter { it.classIndex == 0 }
        val class1Candidates = candidates.filter { it.classIndex == 1 }
        assertEquals("高 IoU 重叠但不同类 → class 0 应保留 1 个", 1, class0Candidates.size)
        assertEquals("高 IoU 重叠但不同类 → class 1 应保留 1 个", 1, class1Candidates.size)
        assertEquals("class 0 score 不变", 0.9f, class0Candidates[0].score, 0.01f)
        assertEquals("class 1 score 不变", 0.85f, class1Candidates[0].score, 0.01f)
    }
}