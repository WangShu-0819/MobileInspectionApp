package com.wearable.inspection.mobile.detection

import androidx.exifinterface.media.ExifInterface
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.data.entity.RoiTargetType
import com.wearable.inspection.mobile.registration.PhotoRegistrationEngine
import com.wearable.inspection.mobile.registration.ProjectedPoint
import com.wearable.inspection.mobile.registration.RegistrationStatus
import com.wearable.inspection.mobile.ui.screens.ContentRectBounds
import com.wearable.inspection.mobile.ui.screens.RoiCoordinateMapper
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

enum class RoiSimilarityStatus {
    NOT_RUN_NANODET_NOT_NG,
    TEMPLATE_UNREADABLE,
    PHOTO_UNREADABLE,
    SIMILARITY_RUNTIME_UNAVAILABLE,
    INVALID_ROI,
    ROI_IMAGE_UNAVAILABLE,
    ROI_EVIDENCE_SAVE_FAILED,
    REGISTRATION_FAILED,
    COMPARISON_ERROR,
    SCORED_NO_THRESHOLD,
    SCORED_WITH_CANDIDATE_THRESHOLD,
}

data class RoiSimilarityResult(
    val status: RoiSimilarityStatus,
    val score: Float? = null,
    val threshold: Float? = null,
    val candidate: NanoDetSuggestion? = null,
    val detail: String? = null,
) {
    val wasRun: Boolean get() = status in setOf(
        RoiSimilarityStatus.TEMPLATE_UNREADABLE,
        RoiSimilarityStatus.PHOTO_UNREADABLE,
        RoiSimilarityStatus.SIMILARITY_RUNTIME_UNAVAILABLE,
        RoiSimilarityStatus.INVALID_ROI,
        RoiSimilarityStatus.REGISTRATION_FAILED,
        RoiSimilarityStatus.COMPARISON_ERROR,
        RoiSimilarityStatus.SCORED_NO_THRESHOLD,
        RoiSimilarityStatus.SCORED_WITH_CANDIDATE_THRESHOLD,
    )
}

object RoiSimilarityThresholdPolicy {
    /** Uniform software candidate threshold for the four widget detection targets. */
    const val WIDGET_SIMILARITY_CANDIDATE_THRESHOLD = 0.75f

    @Suppress("UNUSED_PARAMETER")
    fun threshold(partId: String, targetType: RoiTargetType?): Float? =
        WIDGET_SIMILARITY_CANDIDATE_THRESHOLD.takeIf { targetType in WIDGET_TARGET_TYPES }

    private val WIDGET_TARGET_TYPES = setOf(
        RoiTargetType.THREAD,
        RoiTargetType.NUT,
        RoiTargetType.BOLT,
        RoiTargetType.NUTSERT,
    )
}

/** NanoDet NG and unsupported widget targets may request an independent similarity candidate. */
object RoiSimilarityFallbackPolicy {
    private val NG_STATUSES = setOf(
        NanoDetInferenceStatus.DETECTED_BELOW_THRESHOLD,
        NanoDetInferenceStatus.NO_DETECTION,
    )
    private val WIDGET_TARGET_TYPES = setOf(
        RoiTargetType.THREAD,
        RoiTargetType.NUT,
        RoiTargetType.BOLT,
        RoiTargetType.NUTSERT,
    )

    fun shouldRun(result: NanoDetRoiInferenceResult?, targetType: RoiTargetType?): Boolean {
        if (result == null || targetType !in WIDGET_TARGET_TYPES) return false
        return (result.status in NG_STATUSES && result.modelSuggestion == NanoDetSuggestion.NG) ||
            result.status == NanoDetInferenceStatus.MODEL_TARGET_UNSUPPORTED
    }
}

internal data class RoiSimilarityEvidenceExecution(
    val result: RoiSimilarityResult,
    val evidencePath: String?,
    val evidenceStatus: String,
)

/** Enforces save-before-compare and leaves evidence status separate from comparison status. */
internal suspend fun runRoiSimilarityAfterSavingEvidence(
    bitmapAvailable: Boolean,
    saveEvidence: suspend () -> String?,
    compare: suspend (evidencePath: String) -> RoiSimilarityResult,
): RoiSimilarityEvidenceExecution {
    if (!bitmapAvailable) {
        return RoiSimilarityEvidenceExecution(
            RoiSimilarityResult(RoiSimilarityStatus.ROI_IMAGE_UNAVAILABLE, detail = "ROI 裁剪图不可用；未运行相似度比较"),
            null,
            "ROI_BITMAP_UNAVAILABLE",
        )
    }
    val path = try {
        saveEvidence()
    } catch (e: Exception) {
        null
    }
    if (path.isNullOrBlank()) {
        return RoiSimilarityEvidenceExecution(
            RoiSimilarityResult(RoiSimilarityStatus.ROI_EVIDENCE_SAVE_FAILED, detail = "ROI 图片保存失败；未运行相似度比较"),
            null,
            "SAVE_FAILED_NOT_RUN",
        )
    }
    val comparison = try {
        compare(path)
    } catch (e: Exception) {
        RoiSimilarityResult(RoiSimilarityStatus.COMPARISON_ERROR, detail = e.message ?: "ROI 相似度计算失败")
    }
    return RoiSimilarityEvidenceExecution(comparison, path, "SAVED")
}

/** Registration-gated SSIM for NanoDet NG results. It only reports a candidate; human confirmation stays authoritative. */
class RoiSimilarityFallback {
    fun evaluate(
        partId: String,
        templatePath: String?,
        photoPath: String,
        roi: RoiDefinitionEntity,
    ): RoiSimilarityResult = try {
        evaluateInternal(partId, templatePath, photoPath, roi)
    } catch (e: LinkageError) {
        RoiSimilarityResult(RoiSimilarityStatus.SIMILARITY_RUNTIME_UNAVAILABLE, detail = e.message ?: "OpenCV 相似度运行库不可用")
    } catch (e: Exception) {
        RoiSimilarityResult(RoiSimilarityStatus.COMPARISON_ERROR, detail = e.message ?: "ROI 相似度计算失败")
    }

    private fun evaluateInternal(
        partId: String,
        templatePath: String?,
        photoPath: String,
        roi: RoiDefinitionEntity,
    ): RoiSimilarityResult {
        if (templatePath.isNullOrBlank()) return RoiSimilarityResult(RoiSimilarityStatus.TEMPLATE_UNREADABLE, detail = "模板图片路径不可用")
        val templateGeometry = RoiCoordinateMapper.getImageGeometry(templatePath)
            ?: return RoiSimilarityResult(RoiSimilarityStatus.TEMPLATE_UNREADABLE, detail = "无法读取模板图片尺寸或 EXIF")
        val photoGeometry = RoiCoordinateMapper.getImageGeometry(photoPath)
            ?: return RoiSimilarityResult(RoiSimilarityStatus.PHOTO_UNREADABLE, detail = "无法读取现场图片尺寸或 EXIF")
        val normalized = RoiCoordinateMapper.parseNormalizedRect(roi.normalizedRect)
            ?: return RoiSimilarityResult(RoiSimilarityStatus.INVALID_ROI, detail = "ROI 坐标无效")

        val rawTemplate = Imgcodecs.imread(templatePath, Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION)
        val rawPhoto = Imgcodecs.imread(photoPath, Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION)
        if (rawTemplate.empty()) {
            rawTemplate.release()
            rawPhoto.release()
            return RoiSimilarityResult(RoiSimilarityStatus.TEMPLATE_UNREADABLE, detail = "模板图片无法解码")
        }
        if (rawPhoto.empty()) {
            rawTemplate.release()
            rawPhoto.release()
            return RoiSimilarityResult(RoiSimilarityStatus.PHOTO_UNREADABLE, detail = "现场图片无法解码")
        }

        val templateUprightBgr = orientBgrMat(rawTemplate, templateGeometry.exifOrientation)
        val photoUprightBgr = orientBgrMat(rawPhoto, photoGeometry.exifOrientation)
        val templateRgb = Mat()
        val photoRgb = Mat()
        val templateRectNormalized = RoiCoordinateMapper.transformNormalizedRect(normalized, templateGeometry.exifOrientation)
        val templateRect = RoiCoordinateMapper.mapToImagePixels(templateRectNormalized, templateGeometry.width, templateGeometry.height)
        return try {
            if (templateUprightBgr.cols() != templateGeometry.width || templateUprightBgr.rows() != templateGeometry.height ||
                photoUprightBgr.cols() != photoGeometry.width || photoUprightBgr.rows() != photoGeometry.height ||
                templateRect.width < MIN_ROI_SIDE || templateRect.height < MIN_ROI_SIDE
            ) {
                return RoiSimilarityResult(RoiSimilarityStatus.INVALID_ROI, detail = "ROI 尺寸或图片方向不一致")
            }
            Imgproc.cvtColor(templateUprightBgr, templateRgb, Imgproc.COLOR_BGR2RGB)
            Imgproc.cvtColor(photoUprightBgr, photoRgb, Imgproc.COLOR_BGR2RGB)
            compareRegisteredRoi(templateRgb, photoRgb, templateRect, partId, RoiTargetType.fromName(roi.targetType))
        } finally {
            if (templateUprightBgr !== rawTemplate) templateUprightBgr.release()
            if (photoUprightBgr !== rawPhoto) photoUprightBgr.release()
            rawTemplate.release()
            rawPhoto.release()
            templateRgb.release()
            photoRgb.release()
        }
    }

    private fun compareRegisteredRoi(
        templateRgb: Mat,
        photoRgb: Mat,
        rect: ContentRectBounds,
        partId: String,
        targetType: RoiTargetType?,
    ): RoiSimilarityResult {
        val x0 = rect.left.toDouble()
        val y0 = rect.top.toDouble()
        val x1 = (rect.right - 1).toDouble()
        val y1 = (rect.bottom - 1).toDouble()
        val engine = PhotoRegistrationEngine()
        val registration = try {
            engine.register(
                templateRgb,
                photoRgb,
                listOf(ProjectedPoint(x0, y0), ProjectedPoint(x1, y0), ProjectedPoint(x1, y1), ProjectedPoint(x0, y1)),
            )
        } finally {
            engine.clear()
        }
        if (!registration.isSuccess) {
            return RoiSimilarityResult(
                RoiSimilarityStatus.REGISTRATION_FAILED,
                detail = "${registration.status}: ${registration.failureReason ?: "未通过配准质量门禁"}",
            )
        }

        val templateRoi = templateRgb.submat(org.opencv.core.Rect(rect.left, rect.top, rect.width, rect.height))
        val sourceCorners = MatOfPoint2f(*registration.projectedRoiCorners!!.map { Point(it.x, it.y) }.toTypedArray())
        val destinationCorners = MatOfPoint2f(
            Point(0.0, 0.0), Point((rect.width - 1).toDouble(), 0.0),
            Point((rect.width - 1).toDouble(), (rect.height - 1).toDouble()), Point(0.0, (rect.height - 1).toDouble()),
        )
        val transform = Imgproc.getPerspectiveTransform(sourceCorners, destinationCorners)
        val alignedPhoto = Mat()
        return try {
            Imgproc.warpPerspective(photoRgb, alignedPhoto, transform, Size(rect.width.toDouble(), rect.height.toDouble()), Imgproc.INTER_LINEAR)
            val score = grayscaleSsim(templateRoi, alignedPhoto)
            val threshold = RoiSimilarityThresholdPolicy.threshold(partId, targetType)
            val candidate = threshold?.let { if (score >= it) NanoDetSuggestion.OK else NanoDetSuggestion.NG }
            RoiSimilarityResult(
                status = if (threshold == null) RoiSimilarityStatus.SCORED_NO_THRESHOLD else RoiSimilarityStatus.SCORED_WITH_CANDIDATE_THRESHOLD,
                score = score,
                threshold = threshold,
                candidate = candidate,
            )
        } finally {
            templateRoi.release()
            sourceCorners.release()
            destinationCorners.release()
            transform.release()
            alignedPhoto.release()
        }
    }

    private fun grayscaleSsim(firstRgb: Mat, secondRgb: Mat): Float {
        val first = Mat()
        val second = Mat()
        val mats = mutableListOf<Mat>()
        try {
            Imgproc.cvtColor(firstRgb, first, Imgproc.COLOR_RGB2GRAY)
            Imgproc.cvtColor(secondRgb, second, Imgproc.COLOR_RGB2GRAY)
            fun mat() = Mat().also(mats::add)
            val muFirst = mat(); val muSecond = mat()
            Imgproc.GaussianBlur(first, muFirst, Size(11.0, 11.0), 1.5)
            Imgproc.GaussianBlur(second, muSecond, Size(11.0, 11.0), 1.5)
            val muFirstSq = mat(); val muSecondSq = mat(); val muProduct = mat()
            Core.multiply(muFirst, muFirst, muFirstSq)
            Core.multiply(muSecond, muSecond, muSecondSq)
            Core.multiply(muFirst, muSecond, muProduct)
            val firstSq = mat(); val secondSq = mat(); val product = mat()
            Core.multiply(first, first, firstSq); Core.multiply(second, second, secondSq); Core.multiply(first, second, product)
            val sigmaFirstSq = mat(); val sigmaSecondSq = mat(); val sigmaProduct = mat()
            Imgproc.GaussianBlur(firstSq, sigmaFirstSq, Size(11.0, 11.0), 1.5)
            Imgproc.GaussianBlur(secondSq, sigmaSecondSq, Size(11.0, 11.0), 1.5)
            Imgproc.GaussianBlur(product, sigmaProduct, Size(11.0, 11.0), 1.5)
            Core.subtract(sigmaFirstSq, muFirstSq, sigmaFirstSq)
            Core.subtract(sigmaSecondSq, muSecondSq, sigmaSecondSq)
            Core.subtract(sigmaProduct, muProduct, sigmaProduct)
            val numeratorA = mat(); val numeratorB = mat(); val denominatorA = mat(); val denominatorB = mat()
            Core.multiply(muProduct, Scalar(2.0), numeratorA); Core.add(numeratorA, Scalar(C1), numeratorA)
            Core.multiply(sigmaProduct, Scalar(2.0), numeratorB); Core.add(numeratorB, Scalar(C2), numeratorB)
            Core.add(muFirstSq, muSecondSq, denominatorA); Core.add(denominatorA, Scalar(C1), denominatorA)
            Core.add(sigmaFirstSq, sigmaSecondSq, denominatorB); Core.add(denominatorB, Scalar(C2), denominatorB)
            val numerator = mat(); val denominator = mat(); val map = mat()
            Core.multiply(numeratorA, numeratorB, numerator)
            Core.multiply(denominatorA, denominatorB, denominator)
            Core.divide(numerator, denominator, map)
            val score = Core.mean(map).`val`[0]
            require(score.isFinite()) { "SSIM 非有限" }
            return score.toFloat().coerceIn(-1f, 1f)
        } finally {
            first.release(); second.release(); mats.forEach(Mat::release)
        }
    }

    companion object {
        private const val MIN_ROI_SIDE = 16
        private const val C1 = (0.01 * 255) * (0.01 * 255)
        private const val C2 = (0.03 * 255) * (0.03 * 255)
    }
}
