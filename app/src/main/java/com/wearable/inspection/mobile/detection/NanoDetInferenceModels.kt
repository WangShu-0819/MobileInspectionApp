package com.wearable.inspection.mobile.detection

import com.wearable.inspection.mobile.data.entity.RoiTargetType

object NanoDetModelContract {
    const val VERSION = "nanodet-ncnn-20260526-opt2"
    const val INPUT_BLOB = "in0"
    const val OUTPUT_BLOB = "out0"
    const val INPUT_SIZE = 416
    const val OUTPUT_WIDTH = 34
    const val OUTPUT_HEIGHT = 3598
    const val CANDIDATE_THRESHOLD = 0.05f
    const val STARTING_BUSINESS_THRESHOLD = 0.37f
    const val PARAM_SHA256 = "AD45E2F3FCB6777A5E924AA9A3095C23B2C5F900632720C389D7816A1A390BDD"
    const val MODEL_SHA256 = "38F67190D669C9F047546F1B34DF541704E9A20429C4921182DDA8085E8F54E3"
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
    val threshold: Float = NanoDetModelContract.STARTING_BUSINESS_THRESHOLD,
    val candidateThreshold: Float = NanoDetModelContract.CANDIDATE_THRESHOLD,
    val modelVersion: String = NanoDetModelContract.VERSION,
    val modelParamSha256: String = NanoDetModelContract.PARAM_SHA256,
    val modelSha256: String = NanoDetModelContract.MODEL_SHA256,
    val elapsedMs: Long = 0,
    val inputShape: String = "[1,3,416,416]",
    val outputBlob: String = NanoDetModelContract.OUTPUT_BLOB,
    val outputShape: String = "[3598,34]",
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val exifOrientation: Int? = null,
    val roiBounds: List<Int>? = null,
    val detections: List<NanoDetDetection> = emptyList(),
    val detail: String? = null
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
    fun create(paramPath: String, modelPath: String): NanoDetTensorRuntime
}

object NanoDetDecisionPolicy {
    fun classIndex(targetType: RoiTargetType?): Int? = when (targetType) {
        RoiTargetType.NUT -> 0
        RoiTargetType.THREAD -> 1
        RoiTargetType.FEATURE, null -> null
    }

    fun decide(
        targetType: RoiTargetType?,
        candidates: List<NanoDetCandidate>,
        threshold: Float = NanoDetModelContract.STARTING_BUSINESS_THRESHOLD
    ): NanoDetDecision {
        require(threshold.isFinite() && threshold in 0f..1f) { "threshold must be finite and in [0,1]" }
        if (targetType == null) {
            return NanoDetDecision(NanoDetInferenceStatus.ROI_NOT_CONFIGURED, null, null, null)
        }
        if (targetType == RoiTargetType.FEATURE) {
            return NanoDetDecision(NanoDetInferenceStatus.FEATURE_UNSUPPORTED, null, null, null)
        }

        val targetClass = checkNotNull(classIndex(targetType))
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
