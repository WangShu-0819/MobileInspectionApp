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
    private val runtimeFactory: NanoDetTensorRuntimeFactory = NanoDetTensorRuntimeFactory(::RuntimeSession),
    /** 零件 ID；用于前缀路由选择模型。空字符串表示旧零件（exp09）。 */
    private val partId: String = "",
) : AutoCloseable {
    private val appContext = context.applicationContext

    /** 路由结果：根据 partId 前缀选择模型配置和类别策略。 */
    private val routeResult: ModelRouteResult = PartModelRouter.resolveModelConfig(partId)
    /** 当前模型配置；ModelUnavailable 时为 null，不携带任何模型元数据。 */
    private val activeConfig: ModelConfig? = when (val r = routeResult) {
        is ModelRouteResult.Success -> r.config
        is ModelRouteResult.ModelUnavailable -> null
    }
    /** 当前类别策略；ModelUnavailable 时降级为 exp09（仅用于不需要模型的路径）。 */
    private val classPolicy: ModelClassPolicy = when (val r = routeResult) {
        is ModelRouteResult.Success -> r.classPolicy
        is ModelRouteResult.ModelUnavailable -> Exp09ClassPolicy
    }
    /** 模型是否可用（路由成功且资产已验证）。 */
    private val modelAvailable: Boolean = routeResult is ModelRouteResult.Success && (activeConfig?.assetsVerified == true)

    private val modelDirectory = activeConfig?.let { File(appContext.filesDir, "models/${it.modelDirName}") }
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

        // 模型不可用时，所有 ROI 返回明确的 MODEL_UNAVAILABLE
        // 不携带任何模型合同字段（targetClassIndex、shape、threshold 等均为 null）
        if (!modelAvailable) {
            val unavailableReason = (routeResult as ModelRouteResult.ModelUnavailable).reason
            return rois.associate { roi ->
                roi.id to NanoDetRoiInferenceResult(
                    roiId = roi.id,
                    status = NanoDetInferenceStatus.MODEL_UNAVAILABLE,
                    modelSuggestion = null,
                    matchingScore = null,
                    targetClassIndex = null,
                    threshold = null,
                    candidateThreshold = null,
                    modelVersion = null,
                    modelParamSha256 = null,
                    modelSha256 = null,
                    inputShape = null,
                    outputBlob = null,
                    outputShape = null,
                    detail = "模型不可用: $unavailableReason",
                )
            }
        }

        val results = linkedMapOf<String, NanoDetRoiInferenceResult>()
        val pending = mutableListOf<PendingRoi>()

        for (roi in rois) {
            val targetType = RoiTargetType.fromName(roi.targetType)
            val unsupported = when (targetType) {
                null -> NanoDetInferenceStatus.ROI_NOT_CONFIGURED
                RoiTargetType.FEATURE -> NanoDetInferenceStatus.FEATURE_UNSUPPORTED
                else -> if (!classPolicy.isTargetSupported(targetType))
                    NanoDetInferenceStatus.MODEL_TARGET_UNSUPPORTED else null
            }
            if (unsupported != null) {
                val detail = if (unsupported == NanoDetInferenceStatus.MODEL_TARGET_UNSUPPORTED)
                    "${activeConfig?.version ?: "未知"} 模型不支持 ${targetType?.name} 类别" else null
                results[roi.id] = result(
                    roi.id, unsupported,
                    targetClass = classPolicy.classIndex(targetType),
                    detail = detail
                )
                continue
            }
            val normalized = RoiCoordinateMapper.parseNormalizedRect(roi.normalizedRect)
            if (normalized == null) {
                results[roi.id] = result(roi.id, NanoDetInferenceStatus.INVALID_ROI, targetClass = classPolicy.classIndex(targetType))
                continue
            }
            if (templateExifOrientation == null) {
                results[roi.id] = result(roi.id, NanoDetInferenceStatus.TEMPLATE_IMAGE_UNREADABLE, targetClass = classPolicy.classIndex(targetType))
                continue
            }
            pending += PendingRoi(roi, checkNotNull(targetType), normalized)
        }
        if (pending.isEmpty()) return results

        val photoGeometry = RoiCoordinateMapper.getImageGeometry(photoPath)
        if (photoGeometry == null) {
            pending.forEach {
                results[it.roi.id] = result(it.roi.id, NanoDetInferenceStatus.IMAGE_UNREADABLE, targetClass = classPolicy.classIndex(it.targetType))
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
                    targetClass = classPolicy.classIndex(pendingRoi.targetType),
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
                    targetClass = classPolicy.classIndex(it.pending.targetType),
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
                    targetClass = classPolicy.classIndex(it.pending.targetType),
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
                    targetClass = classPolicy.classIndex(it.pending.targetType),
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
                    targetClass = classPolicy.classIndex(it.pending.targetType),
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
                    targetClass = classPolicy.classIndex(it.pending.targetType),
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
                    targetClass = classPolicy.classIndex(it.pending.targetType),
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
                    targetClass = classPolicy.classIndex(it.pending.targetType),
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
                            targetClass = classPolicy.classIndex(item.pending.targetType),
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
                            targetClass = classPolicy.classIndex(item.pending.targetType),
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
                            targetClass = classPolicy.classIndex(item.pending.targetType),
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
                        NanoDetOutputDecoder.decode(output, preprocessed.transform, classPolicy.classNames)
                    } catch (e: IllegalArgumentException) {
                        results[item.pending.roi.id] = result(
                            item.pending.roi.id,
                            NanoDetInferenceStatus.INFERENCE_ERROR,
                            targetClass = classPolicy.classIndex(item.pending.targetType),
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
                    val decision = NanoDetDecisionPolicy.decideWithPolicy(item.pending.targetType, candidates, businessThreshold, classPolicy)
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
                        detections = detections,
                        modelVersion = activeConfig!!.version,
                        modelParamSha256 = activeConfig.paramSha256,
                        modelSha256 = activeConfig.modelSha256,
                        inputShape = "[1,3,${NanoDetModelContract.INPUT_SIZE},${NanoDetModelContract.INPUT_SIZE}]",
                        outputBlob = NanoDetModelContract.OUTPUT_BLOB,
                        outputShape = "[${activeConfig.outputHeight},${activeConfig.outputWidth}]",
                    )
                } catch (e: LinkageError) {
                    results[item.pending.roi.id] = result(
                        item.pending.roi.id,
                        NanoDetInferenceStatus.RUNTIME_UNAVAILABLE,
                        targetClass = classPolicy.classIndex(item.pending.targetType),
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
                        targetClass = classPolicy.classIndex(item.pending.targetType),
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

    /**
     * 整图 NanoDet 推理：不做 ROI 裁剪，将整张照片作为输入。
     *
     * 当配准状态为 FALLBACK_FULL_IMAGE 或 FAILED 时使用此方法。
     * 返回所有检出目标及聚合建议，供人工审阅。
     */
    @Synchronized
    fun inferFullImage(photoPath: String): FullImageInferResult {
        val startedAt = System.nanoTime()

        if (!modelAvailable) {
            val unavailableReason = (routeResult as ModelRouteResult.ModelUnavailable).reason
            return FullImageInferResult(
                detections = emptyList(), aggregatedSuggestion = null, highestScore = null,
                elapsedMs = elapsedMillis(startedAt), imageWidth = 0, imageHeight = 0,
                exifOrientation = null, status = NanoDetInferenceStatus.MODEL_UNAVAILABLE,
                detail = "模型不可用: $unavailableReason",
                modelVersion = null,
                modelParamSha256 = null,
                modelSha256 = null,
                threshold = null,
            )
        }

        if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            return FullImageInferResult(
                detections = emptyList(), aggregatedSuggestion = null, highestScore = null,
                elapsedMs = elapsedMillis(startedAt), imageWidth = 0, imageHeight = 0,
                exifOrientation = null, status = NanoDetInferenceStatus.ABI_UNSUPPORTED,
            )
        }
        val openCvReady = try { OpenCVLoader.initLocal() } catch (_: Exception) { false }
        if (!openCvReady) {
            return FullImageInferResult(
                detections = emptyList(), aggregatedSuggestion = null, highestScore = null,
                elapsedMs = elapsedMillis(startedAt), imageWidth = 0, imageHeight = 0,
                exifOrientation = null, status = NanoDetInferenceStatus.RUNTIME_UNAVAILABLE,
                detail = "OpenCV 初始化失败",
            )
        }

        val photoGeometry = RoiCoordinateMapper.getImageGeometry(photoPath)
        if (photoGeometry == null) {
            return FullImageInferResult(
                detections = emptyList(), aggregatedSuggestion = null, highestScore = null,
                elapsedMs = elapsedMillis(startedAt), imageWidth = 0, imageHeight = 0,
                exifOrientation = null, status = NanoDetInferenceStatus.IMAGE_UNREADABLE,
            )
        }

        val rawImage = try {
            Imgcodecs.imread(photoPath, Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION)
        } catch (e: LinkageError) {
            return FullImageInferResult(
                detections = emptyList(), aggregatedSuggestion = null, highestScore = null,
                elapsedMs = elapsedMillis(startedAt), imageWidth = photoGeometry.width,
                imageHeight = photoGeometry.height, exifOrientation = photoGeometry.exifOrientation,
                status = NanoDetInferenceStatus.RUNTIME_UNAVAILABLE,
                detail = e.message ?: "OpenCV native runtime 不可用",
            )
        } catch (e: Exception) {
            return FullImageInferResult(
                detections = emptyList(), aggregatedSuggestion = null, highestScore = null,
                elapsedMs = elapsedMillis(startedAt), imageWidth = photoGeometry.width,
                imageHeight = photoGeometry.height, exifOrientation = photoGeometry.exifOrientation,
                status = NanoDetInferenceStatus.IMAGE_UNREADABLE,
                detail = e.message ?: "无法解码照片",
            )
        }
        if (rawImage.empty()) {
            rawImage.release()
            return FullImageInferResult(
                detections = emptyList(), aggregatedSuggestion = null, highestScore = null,
                elapsedMs = elapsedMillis(startedAt), imageWidth = photoGeometry.width,
                imageHeight = photoGeometry.height, exifOrientation = photoGeometry.exifOrientation,
                status = NanoDetInferenceStatus.IMAGE_UNREADABLE,
            )
        }

        val uprightImage = try {
            orientBgrMat(rawImage, photoGeometry.exifOrientation)
        } catch (e: Exception) {
            rawImage.release()
            return FullImageInferResult(
                detections = emptyList(), aggregatedSuggestion = null, highestScore = null,
                elapsedMs = elapsedMillis(startedAt), imageWidth = photoGeometry.width,
                imageHeight = photoGeometry.height, exifOrientation = photoGeometry.exifOrientation,
                status = NanoDetInferenceStatus.IMAGE_UNREADABLE,
                detail = "照片方向处理失败: ${e.message}",
            )
        }

        try {
            val activeRuntime = try {
                runtime ?: createRuntime().also { runtime = it }
            } catch (e: LinkageError) {
                return FullImageInferResult(
                    detections = emptyList(), aggregatedSuggestion = null, highestScore = null,
                    elapsedMs = elapsedMillis(startedAt), imageWidth = photoGeometry.width,
                    imageHeight = photoGeometry.height, exifOrientation = photoGeometry.exifOrientation,
                    status = NanoDetInferenceStatus.RUNTIME_UNAVAILABLE,
                    detail = e.message ?: "NCNN native runtime 不可用",
                )
            } catch (e: Exception) {
                return FullImageInferResult(
                    detections = emptyList(), aggregatedSuggestion = null, highestScore = null,
                    elapsedMs = elapsedMillis(startedAt), imageWidth = photoGeometry.width,
                    imageHeight = photoGeometry.height, exifOrientation = photoGeometry.exifOrientation,
                    status = NanoDetInferenceStatus.MODEL_UNAVAILABLE,
                    detail = e.message ?: "NanoDet 模型不可用",
                )
            }

            val preprocessed = NanoDetImagePreprocessor.preprocess(uprightImage)
            val output = try {
                activeRuntime.infer(preprocessed.tensorNchw)
            } catch (e: Exception) {
                return FullImageInferResult(
                    detections = emptyList(), aggregatedSuggestion = null, highestScore = null,
                    elapsedMs = elapsedMillis(startedAt), imageWidth = photoGeometry.width,
                    imageHeight = photoGeometry.height, exifOrientation = photoGeometry.exifOrientation,
                    status = NanoDetInferenceStatus.INFERENCE_ERROR,
                    detail = e.message ?: "NCNN 推理失败",
                )
            }
            val candidates = try {
                NanoDetOutputDecoder.decode(output, preprocessed.transform, classPolicy.classNames)
            } catch (e: IllegalArgumentException) {
                return FullImageInferResult(
                    detections = emptyList(), aggregatedSuggestion = null, highestScore = null,
                    elapsedMs = elapsedMillis(startedAt), imageWidth = photoGeometry.width,
                    imageHeight = photoGeometry.height, exifOrientation = photoGeometry.exifOrientation,
                    status = NanoDetInferenceStatus.INFERENCE_ERROR,
                    detail = e.message ?: "NCNN 输出格式无效",
                )
            }

            val fullImageBounds = com.wearable.inspection.mobile.ui.screens.ContentRectBounds(
                0, 0, photoGeometry.width, photoGeometry.height,
            )
            val detections = candidates.mapNotNull { candidate ->
                NanoDetCoordinateMapper.mapRoiBoxToPhoto(
                    candidate.box, fullImageBounds, photoGeometry.width, photoGeometry.height,
                )?.let { mapped ->
                    NanoDetDetection(
                        classIndex = candidate.classIndex,
                        className = candidate.className,
                        score = candidate.score,
                        point = candidate.point,
                        roiBox = mapped.roiBox,
                        imageBox = mapped.imageBox,
                    )
                }
            }
            val highestScore = detections.maxOfOrNull { it.score }

            return FullImageInferResult(
                detections = detections,
                aggregatedSuggestion = null,
                highestScore = highestScore,
                elapsedMs = elapsedMillis(startedAt),
                imageWidth = photoGeometry.width,
                imageHeight = photoGeometry.height,
                exifOrientation = photoGeometry.exifOrientation,
                status = if (detections.isNotEmpty()) NanoDetInferenceStatus.DETECTED else NanoDetInferenceStatus.NO_DETECTION,
                modelVersion = activeConfig!!.version,
                modelParamSha256 = activeConfig.paramSha256,
                modelSha256 = activeConfig.modelSha256,
            )
        } finally {
            if (uprightImage !== rawImage) uprightImage.release()
            rawImage.release()
        }
    }

    private fun createRuntime(): NanoDetTensorRuntime {
        val config = activeConfig!! // modelAvailable 为 true 时 activeConfig 必非 null
        val paramFile = ensureModel(
            localName = config.assetParamPath.substringAfterLast('/'),
            assetPath = config.assetParamPath,
            expectedSha256 = config.paramSha256,
        )
        val modelFile = ensureModel(
            localName = config.assetModelPath.substringAfterLast('/'),
            assetPath = config.assetModelPath,
            expectedSha256 = config.modelSha256,
        )
        return runtimeFactory.create(paramFile.absolutePath, modelFile.absolutePath, config.outputWidth)
    }

    /**
     * 确保模型资产已部署到本地缓存目录。
     *
     * 按完整资产路径（而非仅文件名）从 assets 复制到 models/{modelDirName}/。
     * 各模型使用独立的缓存目录（由 activeConfig.modelDirName 隔离）和独立的 SHA-256。
     * 不靠文件名判断模型类型；资产路径由 ModelConfig 显式指定。
     */
    private fun ensureModel(localName: String, assetPath: String, expectedSha256: String): File {
        val dir = modelDirectory!! // 仅在 modelAvailable 路径调用，modelDirectory 必非 null
        val file = File(dir, localName)
        if (!file.isFile || sha256(file) != expectedSha256) {
            if (!dir.exists() && !dir.mkdirs()) error("无法创建模型目录")
            file.delete()
            appContext.assets.open(assetPath).use { input -> file.outputStream().use(input::copyTo) }
        }
        check(sha256(file) == expectedSha256) { "$localName SHA-256 与已验证模型不匹配" }
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
        detail = detail,
        modelVersion = activeConfig?.version,
        modelParamSha256 = activeConfig?.paramSha256,
        modelSha256 = activeConfig?.modelSha256,
        inputShape = activeConfig?.let { "[1,3,${NanoDetModelContract.INPUT_SIZE},${NanoDetModelContract.INPUT_SIZE}]" },
        outputBlob = activeConfig?.let { NanoDetModelContract.OUTPUT_BLOB },
        outputShape = activeConfig?.let { "[${it.outputHeight},${it.outputWidth}]" },
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

    private class RuntimeSession(paramPath: String, modelPath: String, outputWidth: Int) : NanoDetTensorRuntime {
        private var handle = NanoDetNcnnNative.create(paramPath, modelPath, outputWidth)

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
