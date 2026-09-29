package com.wearable.inspection.mobile.detection

import com.wearable.inspection.mobile.data.entity.RoiTargetType

object NanoDetModelContract {
    const val VERSION = "nanodet-ncnn-exp09-4class"
    const val INPUT_BLOB = "in0"
    const val OUTPUT_BLOB = "out0"
    const val INPUT_SIZE = 416
    const val OUTPUT_WIDTH = 36
    const val OUTPUT_HEIGHT = 3598
    const val CANDIDATE_THRESHOLD = 0.05f
    const val STARTING_BUSINESS_THRESHOLD = 0.50f
    const val PARAM_SHA256 = "B81B824FEA9FF949F72F3715A8F20C6EBE96C2792370DD80B7A7A69A65335960"
    const val MODEL_SHA256 = "A9C6792BC13B926BF1BAA4F73C001E6EB7E08A3668682B9755E6796D8556C137"

    /** exp09 模型配置（已验证资产）。 */
    val CONFIG = ModelConfig(
        version = VERSION,
        paramSha256 = PARAM_SHA256,
        modelSha256 = MODEL_SHA256,
        assetParamPath = "nanodet/nanodet.ncnn.param",
        assetModelPath = "nanodet/nanodet.ncnn.bin",
        modelDirName = VERSION,
        outputWidth = OUTPUT_WIDTH,
        outputHeight = OUTPUT_HEIGHT,
        classCount = 4,
        assetsVerified = true,
    )
}

/** exp22 黑件模型合约（资产已验证，推理已启用）。 */
object NanoDetModelContractExp22 {
    const val VERSION = "nanodet-ncnn-exp22-black-4class"
    const val INPUT_BLOB = "in0"
    const val OUTPUT_BLOB = "out0"
    const val INPUT_SIZE = 416
    const val OUTPUT_WIDTH = 36
    const val OUTPUT_HEIGHT = 3598
    const val PARAM_SHA256 = "B81B824FEA9FF949F72F3715A8F20C6EBE96C2792370DD80B7A7A69A65335960"
    const val MODEL_SHA256 = "D3A16EDB715050B376F40B5F396FC748B984D781590AA1BF07B3F42A3D92A3D7"

    val CONFIG = ModelConfig(
        version = VERSION,
        paramSha256 = PARAM_SHA256,
        modelSha256 = MODEL_SHA256,
        assetParamPath = "nanodet/exp22/nanodet.ncnn.param",
        assetModelPath = "nanodet/exp22/nanodet.ncnn.bin",
        modelDirName = VERSION,
        outputWidth = OUTPUT_WIDTH,
        outputHeight = OUTPUT_HEIGHT,
        classCount = 4,
        assetsVerified = true,
    )
}

/**
 * exp23 白件模型合约（资产已验证，推理已启用）。
 *
 * outputWidth = 34（2 类 + 32 回归 bin）；本次 Android 固定图 instrumentation 实测 NCNN Mat.w=34。
 */
object NanoDetModelContractExp23 {
    const val VERSION = "nanodet-ncnn-exp23-white-2class"
    const val INPUT_BLOB = "in0"
    const val OUTPUT_BLOB = "out0"
    const val INPUT_SIZE = 416
    const val OUTPUT_WIDTH = 34
    const val OUTPUT_HEIGHT = 3598
    const val PARAM_SHA256 = "AD45E2F3FCB6777A5E924AA9A3095C23B2C5F900632720C389D7816A1A390BDD"
    const val MODEL_SHA256 = "1B662094CE94F4A3A8C899F40E1CB449E01AA10AE7A1ADDABCB92BFEF18DA35D"

    val CONFIG = ModelConfig(
        version = VERSION,
        paramSha256 = PARAM_SHA256,
        modelSha256 = MODEL_SHA256,
        assetParamPath = "nanodet/exp23/nanodet.ncnn.param",
        assetModelPath = "nanodet/exp23/nanodet.ncnn.bin",
        modelDirName = VERSION,
        outputWidth = OUTPUT_WIDTH,
        outputHeight = OUTPUT_HEIGHT,
        classCount = 2,
        assetsVerified = true,
    )
}

data class NanoDetBox(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
)

data class NanoDetCandidate(
    val classIndex: Int,
    val className: String,
    val score: Float,
    val box: NanoDetBox,
    val point: Int
)

data class NanoDetDetection(
    val classIndex: Int,
    val className: String,
    val score: Float,
    val point: Int,
    /** Detection coordinates relative to the cropped ROI, in upright source-image pixels. */
    val roiBox: NanoDetBox,
    /** Detection coordinates in the complete upright saved photo, in pixels. */
    val imageBox: NanoDetBox
)

enum class NanoDetSuggestion { OK, NG }

enum class NanoDetInferenceStatus {
    DETECTED,
    DETECTED_BELOW_THRESHOLD,
    NO_DETECTION,
    ROI_NOT_CONFIGURED,
    FEATURE_UNSUPPORTED,
    /** 模型不支持的目标类型（如 exp23 白件模型不支持 BOLT/NUTSERT），与 FEATURE 无关。 */
    MODEL_TARGET_UNSUPPORTED,
    INVALID_ROI,
    PHOTO_ASSOCIATION_ERROR,
    IMAGE_UNREADABLE,
    TEMPLATE_IMAGE_UNREADABLE,
    ABI_UNSUPPORTED,
    RUNTIME_UNAVAILABLE,
    MODEL_UNAVAILABLE,
    INFERENCE_ERROR
}

data class NanoDetRoiInferenceResult(
    val roiId: String,
    val status: NanoDetInferenceStatus,
    val modelSuggestion: NanoDetSuggestion?,
    /** Highest retained score for the ROI's mapped target class; null when there is no match. */
    val matchingScore: Float?,
    val targetClassIndex: Int?,
    val threshold: Float? = NanoDetModelContract.STARTING_BUSINESS_THRESHOLD,
    val candidateThreshold: Float? = NanoDetModelContract.CANDIDATE_THRESHOLD,
    val modelVersion: String? = null,
    val modelParamSha256: String? = null,
    val modelSha256: String? = null,
    val elapsedMs: Long = 0,
    val inputShape: String? = "[1,3,416,416]",
    val outputBlob: String? = NanoDetModelContract.OUTPUT_BLOB,
    val outputShape: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val exifOrientation: Int? = null,
    val roiBounds: List<Int>? = null,
    val detections: List<NanoDetDetection> = emptyList(),
    val detail: String? = null,
    val similarity: RoiSimilarityResult = RoiSimilarityResult(RoiSimilarityStatus.NOT_RUN_NANODET_NOT_NG),
    val similarityEvidencePath: String? = null,
    val similarityEvidenceStatus: String? = null,
)

data class NanoDetDecision(
    val status: NanoDetInferenceStatus,
    val suggestion: NanoDetSuggestion?,
    val matchingScore: Float?,
    val targetClassIndex: Int?
)

interface NanoDetTensorRuntime : AutoCloseable {
    fun infer(inputNchw: FloatArray): FloatArray
}

fun interface NanoDetTensorRuntimeFactory {
    fun create(paramPath: String, modelPath: String, outputWidth: Int): NanoDetTensorRuntime
}

object NanoDetDecisionPolicy {
    fun classIndex(targetType: RoiTargetType?): Int? = Exp09ClassPolicy.classIndex(targetType)

    fun decide(
        targetType: RoiTargetType?,
        candidates: List<NanoDetCandidate>,
        threshold: Float = NanoDetModelContract.STARTING_BUSINESS_THRESHOLD
    ): NanoDetDecision = decideWithPolicy(targetType, candidates, threshold, Exp09ClassPolicy)

    /**
     * 模型感知的决策：使用指定的 [classPolicy] 进行类别映射。
     *
     * 当 [classPolicy] 不支持 [targetType] 时：
     * - RoiTargetType.FEATURE → [NanoDetInferenceStatus.FEATURE_UNSUPPORTED]
     * - 其他不支持类型（如白件 BOLT/NUTSERT）→ [NanoDetInferenceStatus.MODEL_TARGET_UNSUPPORTED]
     */
    fun decideWithPolicy(
        targetType: RoiTargetType?,
        candidates: List<NanoDetCandidate>,
        threshold: Float,
        classPolicy: ModelClassPolicy,
    ): NanoDetDecision {
        require(threshold.isFinite() && threshold in 0f..1f) { "threshold must be finite and in [0,1]" }
        if (targetType == null) {
            return NanoDetDecision(NanoDetInferenceStatus.ROI_NOT_CONFIGURED, null, null, null)
        }
        if (targetType == RoiTargetType.FEATURE) {
            return NanoDetDecision(NanoDetInferenceStatus.FEATURE_UNSUPPORTED, null, null, null)
        }
        if (!classPolicy.isTargetSupported(targetType)) {
            // 模型不支持的目标类型（如白件 BOLT/NUTSERT），明确区分于 FEATURE
            return NanoDetDecision(NanoDetInferenceStatus.MODEL_TARGET_UNSUPPORTED, null, null, null)
        }

        val targetClass = checkNotNull(classPolicy.classIndex(targetType))
        val best = candidates.asSequence()
            .filter { it.classIndex == targetClass }
            .maxByOrNull { it.score }
            ?: return NanoDetDecision(
                NanoDetInferenceStatus.NO_DETECTION,
                NanoDetSuggestion.NG,
                null,
                targetClass
            )

        return if (best.score >= threshold) {
            NanoDetDecision(NanoDetInferenceStatus.DETECTED, NanoDetSuggestion.OK, best.score, targetClass)
        } else {
            NanoDetDecision(
                NanoDetInferenceStatus.DETECTED_BELOW_THRESHOLD,
                NanoDetSuggestion.NG,
                best.score,
                targetClass
            )
        }
    }
}
