package com.wearable.inspection.mobile.dpm

import com.wearable.inspection.mobile.dpm.DpmDimensionMode
import com.wearable.inspection.mobile.dpm.DpmScanControl
import com.wearable.inspection.mobile.dpm.ImportedDpmScanner

/**
 * 工业 DPM DataMatrix 网格重建解码器（纯 JVM：仅 OpenCV 核心 + imgproc + ZXing，可单测）。
 *
 * **2026-08-21 重构**：类接口与调用契约完全保留（[reconstruct]、[reconstructWithRenders]、
 * [GridDecodeResult]、[GridRender]），**内部实现整体替换**为 Python 参考实现
 * （DPM_Scanner_Source_20260820，对 DPM 码识别率 79~91%）的一模一样 Kotlin 移植
 * [ImportedDpmScanner]。原锚点→粗搜→精修的网格重建逻辑被移植算法取代——
 * 原因：新批次真机 dump（D:\dpm_dump_20260820）上旧锚点算法 0/115 全失败（码为
 * 低对比点阵冲击式），参考实现同批次 79/115（68.7%）且用户指定帧
 * input_400x533_1787219111940 可解出 L0549630AE092212080057。
 *
 * [GridDecodeResult.renders] 不再产生（移植算法内部自带 ZXing 解码，无需上层
 * ML Kit 渲染图兜底），保留字段仅为兼容 DpmAnalyzer 调用契约，恒为空列表。
 *
 * 线程模型：无状态 object，由 DpmAnalyzer 提交到 Dispatchers.Default 后台协程执行，
 * 1.5s 时间预算由上层 withTimeoutOrNull 截断（超时丢弃结果，冷却后重试）。
 */
object DpmGridReconstructor {

    /**
     * 重建并解码一帧 ROI 灰度图（亮/暗码极性均可）。默认 AUTO：16/18/20 三尺寸
     * 配额+交错。[control] 为协作式截止控制（可空，DpmAnalyzer 生产路径必传）。
     * @return 解码文本，未识别返回 null。
     */
    fun reconstruct(
        gray: IntArray,
        w: Int,
        h: Int,
        mode: DpmDimensionMode = DpmDimensionMode.AUTO,
        control: DpmScanControl? = null,
    ): String? = reconstructWithRenders(gray, w, h, mode, control)?.code

    /**
     * 重建入口：同 [reconstruct]，但保留 [GridDecodeResult] 契约
     * （renders 恒为空，兼容上层渲染图解码路径；code 为移植算法解出的文本，
     * dimension 为命中的网格重建尺寸，0 = 尺寸无关的九宫格×变体兜底）。
     * [mode] 只约束重型网格重建（AUTO = 16/18/20 配额交错；固定 = 指定尺寸 Top 24/12），
     * 快速路径/变体兜底不受影响。[control] 协作式截止（deadline/取消）贯穿整个
     * ImportedDpmScanner 扫描 —— 中止后返回 null 且 control.abortReason 可诊断。
     * @return null = 未识别或已中止；否则含 code（移植算法解出，可能仍为 null 仅当
     *         算法内部解码失败且无渲染图产物——当前实现 code==null 即返回 null）
     */
    fun reconstructWithRenders(
        gray: IntArray,
        w: Int,
        h: Int,
        mode: DpmDimensionMode = DpmDimensionMode.AUTO,
        control: DpmScanControl? = null,
    ): GridDecodeResult? {
        val bytes = ByteArray(w * h) { gray[it].toByte() }
        val result = ImportedDpmScanner.scanImage(bytes, w, h, mode, control)
        return result?.let { GridDecodeResult(it.text, emptyList(), result.dimension) }
    }

    /** 渲染图输出（保留契约；移植算法内部自解码，不再产出渲染图） */
    data class GridRender(val pixels: ByteArray, val size: Int)

    /** 网格重建结果（保留契约；code 为移植算法解出文本，renders 恒空，dimension 为
     *  命中尺寸，0 = 尺寸无关兜底） */
    data class GridDecodeResult(
        val code: String?,
        val renders: List<GridRender>,
        val dimension: Int = 0,
    )
}
