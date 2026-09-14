package com.wearable.inspection.mobile.detection

import android.content.Context
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.data.entity.RoiTargetType
import com.wearable.inspection.mobile.ui.screens.RoiCoordinateMapper
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.security.MessageDigest

class NanoDetRoiInferenceService(
    context: Context,
    private val businessThreshold: Float = NanoDetModelContract.STARTING_BUSINESS_THRESHOLD,
    private val runtimeFactory: NanoDetTensorRuntimeFactory = NanoDetTensorRuntimeFactory(::RuntimeSession)
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val modelDirectory = File(appContext.filesDir, "models/${NanoDetModelContract.VERSION}")
    private var runtime: NanoDetTensorRuntime? = null

    init {
        require(businessThreshold.isFinite() && businessThreshold in 0f..1f)
    }

    /** Runs only on a saved photo path. ROI rectangles are transformed from raw template space to upright photo space. */
    @Synchronized
    fun inferSavedPhoto(
        photoPath: String,
        rois: List<RoiDefinitionEntity>,
        templateExifOrientation: Int?
    ): Map<String, NanoDetRoiInferenceResult> {
        if (rois.isEmpty()) return emptyMap()
        val results = linkedMapOf<String, NanoDetRoiInferenceResult>()
        val pending = mutableListOf<PendingRoi>()

        for (roi in rois) {
            val targetType = RoiTargetType.fromName(roi.targetType)
            val unsupported = when (targetType) {
                null -> NanoDetInferenceStatus.ROI_NOT_CONFIGURED
                RoiTargetType.FEATURE -> NanoDetInferenceStatus.FEATURE_UNSUPPORTED
                else -> null
            }
            if (unsupported != null) {
                results[roi.id] = result(roi.id, unsupported, targetClass = NanoDetDecisionPolicy.classIndex(targetType))
                continue
            }
            val normalized = RoiCoordinateMapper.parseNormalizedRect(roi.normalizedRect)
            if (normalized == null) {
                results[roi.id] = result(roi.id, NanoDetInferenceStatus.INVALID_ROI, targetClass = NanoDetDecisionPolicy.classIndex(targetType))
                continue
            }
            if (templateExifOrientation == null) {
                results[roi.id] = result(roi.id, NanoDetInferenceStatus.TEMPLATE_IMAGE_UNREADABLE, targetClass = NanoDetDecisionPolicy.classIndex(targetType))
                continue
            }
            pending += PendingRoi(roi, checkNotNull(targetType), normalized)
        }
        if (pending.isEmpty()) return results

        val photoGeometry = RoiCoordinateMapper.getImageGeometry(photoPath)
        if (photoGeometry == null) {
            pending.forEach {
                results[it.roi.id] = result(it.roi.id, NanoDetInferenceStatus.IMAGE_UNREADABLE, targetClass = NanoDetDecisionPolicy.classIndex(it.targetType))
            }
            return results
        }

        val mapped = pending.mapNotNull { pendingRoi ->
            val bounds = runCatching {
                RoiCoordinateMapper.mapTemplateRoiToPhotoPixels(
                    pendingRoi.normalizedRect,
                    checkNotNull(templateExifOrientation),
                    photoGeometry
                )
            }.getOrNull()
            if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
                results[pendingRoi.roi.id] = result(
                    pendingRoi.roi.id,
                    NanoDetInferenceStatus.INVALID_ROI,
                    targetClass = NanoDetDecisionPolicy.classIndex(pendingRoi.targetType),
                    imageWidth = photoGeometry.width,
                    imageHeight = photoGeometry.height,
                    exifOrientation = photoGeometry.exifOrientation
                )
                null
            } else {
                MappedRoi(pendingRoi, bounds)
            }
        }
        if (mapped.isEmpty()) return results

        if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            mapped.forEach {
                results[it.pending.roi.id] = result(
                    it.pending.roi.id,
                    NanoDetInferenceStatus.ABI_UNSUPPORTED,
                    targetClass = NanoDetDecisionPolicy.classIndex(it.pending.targetType),
                    imageWidth = photoGeometry.width,
                    imageHeight = photoGeometry.height,
                    exifOrientation = photoGeometry.exifOrientation,
                    roiBounds = it.bounds.asList()
                )
            }
            return results
        }
        val openCvReady = try {
            OpenCVLoader.initLocal()
        } catch (_: LinkageError) {
            false
        } catch (_: Exception) {
            false
        }
        if (!openCvReady) {
            mapped.forEach {
                results[it.pending.roi.id] = result(
                    it.pending.roi.id,
                    NanoDetInferenceStatus.RUNTIME_UNAVAILABLE,
                    targetClass = NanoDetDecisionPolicy.classIndex(it.pending.targetType),
                    imageWidth = photoGeometry.width,
                    imageHeight = photoGeometry.height,
                    exifOrientation = photoGeometry.exifOrientation,
                    roiBounds = it.bounds.asList(),
                    detail = "OpenCV 初始化失败"
                )
            }
            return results
        }

        val rawImage = try {
            Imgcodecs.imread(
                photoPath,
                Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION
            )
        } catch (e: LinkageError) {
            mapped.forEach {
                results[it.pending.roi.id] = result(
                    it.pending.roi.id,
                    NanoDetInferenceStatus.RUNTIME_UNAVAILABLE,
                    targetClass = NanoDetDecisionPolicy.classIndex(it.pending.targetType),
                    imageWidth = photoGeometry.width,
                    imageHeight = photoGeometry.height,
                    exifOrientation = photoGeometry.exifOrientation,
                    roiBounds = it.bounds.asList(),
                    detail = e.message ?: "OpenCV native runtime 不可用"
                )
            }
            return results
        } catch (e: Exception) {
            mapped.forEach {
                results[it.pending.roi.id] = result(
                    it.pending.roi.id,
                    NanoDetInferenceStatus.IMAGE_UNREADABLE,
                    targetClass = NanoDetDecisionPolicy.classIndex(it.pending.targetType),
                    imageWidth = photoGeometry.width,
                    imageHeight = photoGeometry.height,
                    exifOrientation = photoGeometry.exifOrientation,
                    roiBounds = it.bounds.asList(),
                    detail = e.message ?: "无法解码保存照片"
                )
            }
            return results
        }
        if (rawImage.empty()) {
            rawImage.release()
            mapped.forEach {
                results[it.pending.roi.id] = result(
                    it.pending.roi.id,
                    NanoDetInferenceStatus.IMAGE_UNREADABLE,
                    targetClass = NanoDetDecisionPolicy.classIndex(it.pending.targetType),
                    imageWidth = photoGeometry.width,
                    imageHeight = photoGeometry.height,
                    exifOrientation = photoGeometry.exifOrientation,
                    roiBounds = it.bounds.asList()
                )
            }
            return results
        }

        val uprightImage = try {
            orientBgrMat(rawImage, photoGeometry.exifOrientation)
        } catch (e: Exception) {
            rawImage.release()
            mapped.forEach {
                results[it.pending.roi.id] = result(
                    it.pending.roi.id,
                    NanoDetInferenceStatus.IMAGE_UNREADABLE,
                    targetClass = NanoDetDecisionPolicy.classIndex(it.pending.targetType),
                    imageWidth = photoGeometry.width,
                    imageHeight = photoGeometry.height,
                    exifOrientation = photoGeometry.exifOrientation,
                    roiBounds = it.bounds.asList(),
                    detail = "照片方向处理失败: ${e.message}"
                )
            }
            return results
        }
        if (uprightImage.cols() != photoGeometry.width || uprightImage.rows() != photoGeometry.height) {
            if (uprightImage !== rawImage) uprightImage.release()
            rawImage.release()
            mapped.forEach {
                results[it.pending.roi.id] = result(
                    it.pending.roi.id,
                    NanoDetInferenceStatus.IMAGE_UNREADABLE,
                    targetClass = NanoDetDecisionPolicy.classIndex(it.pending.targetType),
                    imageWidth = photoGeometry.width,
                    imageHeight = photoGeometry.height,
                    exifOrientation = photoGeometry.exifOrientation,
                    detail = "EXIF 方向与解码图像尺寸不一致"
                )
            }
            return results
        }

        try {
            for (item in mapped) {
                val startedAt = System.nanoTime()
                val roiMat = uprightImage.submat(Rect(item.bounds.left, item.bounds.top, item.bounds.width, item.bounds.height))
                try {
                    val preprocessed = NanoDetImagePreprocessor.preprocess(roiMat)
                    val activeRuntime = try {
                        runtime ?: createRuntime().also { runtime = it }
                    } catch (e: LinkageError) {
                        results[item.pending.roi.id] = result(
                            item.pending.roi.id,
                            NanoDetInferenceStatus.RUNTIME_UNAVAILABLE,
                            targetClass = NanoDetDecisionPolicy.classIndex(item.pending.targetType),
                            elapsedMs = elapsedMillis(startedAt),
                            imageWidth = photoGeometry.width,
                            imageHeight = photoGeometry.height,
                            exifOrientation = photoGeometry.exifOrientation,
                            roiBounds = item.bounds.asList(),
                            detail = e.message ?: "NCNN native runtime 不可用"
                        )
                        continue
                    } catch (e: Exception) {
                        results[item.pending.roi.id] = result(
                            item.pending.roi.id,
                            NanoDetInferenceStatus.MODEL_UNAVAILABLE,
                            targetClass = NanoDetDecisionPolicy.classIndex(item.pending.targetType),
                            elapsedMs = elapsedMillis(startedAt),
                            imageWidth = photoGeometry.width,
                            imageHeight = photoGeometry.height,
                            exifOrientation = photoGeometry.exifOrientation,
                            roiBounds = item.bounds.asList(),
                            detail = e.message ?: "NanoDet 模型不可用"
                        )
                        continue
                    }
                    val output = try {
                        activeRuntime.infer(preprocessed.tensorNchw)
                    } catch (e: Exception) {
                        results[item.pending.roi.id] = result(
                            item.pending.roi.id,
                            NanoDetInferenceStatus.INFERENCE_ERROR,
                            targetClass = NanoDetDecisionPolicy.classIndex(item.pending.targetType),
                            elapsedMs = elapsedMillis(startedAt),
                            imageWidth = photoGeometry.width,
                            imageHeight = photoGeometry.height,
                            exifOrientation = photoGeometry.exifOrientation,
                            roiBounds = item.bounds.asList(),
                            detail = e.message ?: "NCNN 推理失败"
                        )
                        continue
                    }
                    val candidates = try {
                        NanoDetOutputDecoder.decode(output, preprocessed.transform)
                    } catch (e: IllegalArgumentException) {
                        results[item.pending.roi.id] = result(
                            item.pending.roi.id,
                            NanoDetInferenceStatus.INFERENCE_ERROR,
                            targetClass = NanoDetDecisionPolicy.classIndex(item.pending.targetType),
                            elapsedMs = elapsedMillis(startedAt),
                            imageWidth = photoGeometry.width,
                            imageHeight = photoGeometry.height,
                            exifOrientation = photoGeometry.exifOrientation,
                            roiBounds = item.bounds.asList(),
                            detail = e.message ?: "NCNN 输出格式无效"
                        )
                        continue
                    }
                    val mappedCandidates = candidates.mapNotNull { candidate ->
                        NanoDetCoordinateMapper.mapRoiBoxToPhoto(
                            candidate.box,
                            item.bounds,
                            photoGeometry.width,
                            photoGeometry.height
                        )?.let { candidate to it }
                    }
                    val detections = mappedCandidates.map { (candidate, mappedBox) ->
                        NanoDetDetection(
                            classIndex = candidate.classIndex,
                            className = candidate.className,
                            score = candidate.score,
                            point = candidate.point,
                            roiBox = mappedBox.roiBox,
                            imageBox = mappedBox.imageBox
                        )
                    }
                    val decision = NanoDetDecisionPolicy.decide(item.pending.targetType, candidates, businessThreshold)
                    results[item.pending.roi.id] = NanoDetRoiInferenceResult(
                        roiId = item.pending.roi.id,
                        status = decision.status,
                        modelSuggestion = decision.suggestion,
                        matchingScore = decision.matchingScore,
                        targetClassIndex = decision.targetClassIndex,
                        threshold = businessThreshold,
                        elapsedMs = elapsedMillis(startedAt),
                        imageWidth = photoGeometry.width,
                        imageHeight = photoGeometry.height,
                        exifOrientation = photoGeometry.exifOrientation,
                        roiBounds = item.bounds.asList(),
                        detections = detections
                    )
                } catch (e: LinkageError) {
                    results[item.pending.roi.id] = result(
                        item.pending.roi.id,
                        NanoDetInferenceStatus.RUNTIME_UNAVAILABLE,
                        targetClass = NanoDetDecisionPolicy.classIndex(item.pending.targetType),
                        elapsedMs = elapsedMillis(startedAt),
                        imageWidth = photoGeometry.width,
                        imageHeight = photoGeometry.height,
                        exifOrientation = photoGeometry.exifOrientation,
                        roiBounds = item.bounds.asList(),
                        detail = e.message ?: "Native runtime 不可用"
                    )
                } catch (e: Exception) {
                    results[item.pending.roi.id] = result(
                        item.pending.roi.id,
                        NanoDetInferenceStatus.INFERENCE_ERROR,
                        targetClass = NanoDetDecisionPolicy.classIndex(item.pending.targetType),
                        elapsedMs = elapsedMillis(startedAt),
                        imageWidth = photoGeometry.width,
                        imageHeight = photoGeometry.height,
                        exifOrientation = photoGeometry.exifOrientation,
                        roiBounds = item.bounds.asList(),
                        detail = e.message ?: "NanoDet ROI 推理失败"
                    )
                } finally {
                    roiMat.release()
                }
            }
        } finally {
            if (uprightImage !== rawImage) uprightImage.release()
            rawImage.release()
        }
        return results
    }

    private fun createRuntime(): NanoDetTensorRuntime {
        val paramFile = ensureModel("nanodet.ncnn.param", NanoDetModelContract.PARAM_SHA256)
        val modelFile = ensureModel("nanodet.ncnn.bin", NanoDetModelContract.MODEL_SHA256)
        return runtimeFactory.create(paramFile.absolutePath, modelFile.absolutePath)
    }

    private fun ensureModel(name: String, expectedSha256: String): File {
        val file = File(modelDirectory, name)
        if (!file.isFile || sha256(file) != expectedSha256) {
            if (!modelDirectory.exists() && !modelDirectory.mkdirs()) error("无法创建模型目录")
            file.delete()
            val assetPath = "nanodet/$name"
            appContext.assets.open(assetPath).use { input -> file.outputStream().use(input::copyTo) }
        }
        check(sha256(file) == expectedSha256) { "$name SHA-256 与已验证模型不匹配" }
        return file
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02X".format(it) }

    private fun elapsedMillis(startedAt: Long) = (System.nanoTime() - startedAt) / 1_000_000L

    private fun result(
        roiId: String,
        status: NanoDetInferenceStatus,
        targetClass: Int?,
        elapsedMs: Long = 0,
        imageWidth: Int? = null,
        imageHeight: Int? = null,
        exifOrientation: Int? = null,
        roiBounds: List<Int>? = null,
        detail: String? = null
    ) = NanoDetRoiInferenceResult(
        roiId = roiId,
        status = status,
        modelSuggestion = null,
        matchingScore = null,
        targetClassIndex = targetClass,
        threshold = businessThreshold,
        elapsedMs = elapsedMs,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        exifOrientation = exifOrientation,
        roiBounds = roiBounds,
        detail = detail
    )

    @Synchronized
    override fun close() {
        runtime?.close()
        runtime = null
    }

    private data class PendingRoi(
        val roi: RoiDefinitionEntity,
        val targetType: RoiTargetType,
        val normalizedRect: com.wearable.inspection.mobile.ui.screens.NormalizedRect
    )

    private data class MappedRoi(val pending: PendingRoi, val bounds: com.wearable.inspection.mobile.ui.screens.ContentRectBounds)

    private class RuntimeSession(paramPath: String, modelPath: String) : NanoDetTensorRuntime {
        private var handle = NanoDetNcnnNative.create(paramPath, modelPath)

        init {
            check(handle != 0L) { "NCNN 返回无效 runtime handle" }
        }

        override fun infer(inputNchw: FloatArray): FloatArray {
            check(handle != 0L) { "NCNN runtime 已关闭" }
            return NanoDetNcnnNative.infer(handle, inputNchw)
        }

        override fun close() {
            if (handle != 0L) {
                NanoDetNcnnNative.destroy(handle)
                handle = 0L
            }
        }
    }
}

internal fun orientBgrMat(raw: Mat, orientation: Int): Mat {
    if (orientation == ExifInterface.ORIENTATION_NORMAL) return raw
    val upright = Mat()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Core.flip(raw, upright, 1)
        ExifInterface.ORIENTATION_ROTATE_180 -> Core.rotate(raw, upright, Core.ROTATE_180)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Core.flip(raw, upright, 0)
        ExifInterface.ORIENTATION_TRANSPOSE -> Core.transpose(raw, upright)
        ExifInterface.ORIENTATION_ROTATE_90 -> Core.rotate(raw, upright, Core.ROTATE_90_CLOCKWISE)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            val flipped = Mat()
            try {
                Core.flip(raw, flipped, -1)
                Core.transpose(flipped, upright)
            } finally {
                flipped.release()
            }
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> Core.rotate(raw, upright, Core.ROTATE_90_COUNTERCLOCKWISE)
        else -> {
            upright.release()
            return raw
        }
    }
    return upright
}

private fun com.wearable.inspection.mobile.ui.screens.ContentRectBounds.asList() =
    listOf(left, top, right, bottom)
