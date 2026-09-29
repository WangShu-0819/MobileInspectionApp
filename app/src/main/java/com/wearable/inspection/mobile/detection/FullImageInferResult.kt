package com.wearable.inspection.mobile.detection

/**
 * 整图 NanoDet 推理结果。
 *
 * 当配准状态为 FALLBACK_FULL_IMAGE 或 FAILED 时，
 * 不做 ROI 裁剪，将整张照片作为输入运行 NanoDet，
 * 返回此结构供人工审阅。
 */
data class FullImageInferResult(
    /** 整图中检出的所有目标 */
    val detections: List<NanoDetDetection>,
    /**
     * 整图模式不按类别伪造 OK/NG，始终为 null。
     * 无检测、低置信度或类别/数量歧义时均由人工确认。
     */
    val aggregatedSuggestion: NanoDetSuggestion? = null,
    /** 所有检测中的最高分；无检测时为 null */
    val highestScore: Float?,
    /** 推理耗时 (ms) */
    val elapsedMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val exifOrientation: Int?,
    /** 推理状态 */
    val status: NanoDetInferenceStatus,
    val detail: String? = null,
    val modelVersion: String? = null,
    val modelParamSha256: String? = null,
    val modelSha256: String? = null,
    val threshold: Float? = NanoDetModelContract.STARTING_BUSINESS_THRESHOLD,
)
