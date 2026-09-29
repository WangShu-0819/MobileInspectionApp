package com.wearable.inspection.mobile.ui.screens

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.data.entity.RoiTargetType
import com.wearable.inspection.mobile.data.entity.ViewRoiConfirmEntity
import com.wearable.inspection.mobile.data.repository.InspectionRepository
import com.wearable.inspection.mobile.detection.FullImageInferResult
import com.wearable.inspection.mobile.detection.NanoDetDecisionPolicy
import com.wearable.inspection.mobile.detection.NanoDetInferenceStatus
import com.wearable.inspection.mobile.detection.NanoDetModelContract
import com.wearable.inspection.mobile.detection.NanoDetRoiInferenceResult
import com.wearable.inspection.mobile.detection.NanoDetRoiInferenceService
import com.wearable.inspection.mobile.detection.NanoDetSuggestion
import com.wearable.inspection.mobile.detection.RoiSimilarityFallback
import com.wearable.inspection.mobile.detection.RoiSimilarityFallbackPolicy
import com.wearable.inspection.mobile.detection.RoiSimilarityResult
import com.wearable.inspection.mobile.detection.RoiSimilarityStatus
import com.wearable.inspection.mobile.detection.runRoiSimilarityAfterSavingEvidence
import com.wearable.inspection.mobile.data.image.MobileImageStore
import com.wearable.inspection.mobile.registration.RegistrationStatus
import android.util.Log
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * View 人工确认 ViewModel
 *
 * 管理单个 View 的 ROI 确认流程：
 * 1. 加载照片 + ROI 定义
 * 2. 裁剪 ROI 子图
 * 3. 跟踪每个 ROI 的 OK/NG 选择
 * 4. 跟踪总体 OK/NG
 * 5. 保存确认结果
 */
class ViewConfirmationViewModel(
    private val repository: InspectionRepository,
    private val batchId: String,
    private val photoId: Long,
    /** 现场照片文件路径（供 UI 加载照片和检测框叠加） */
    val photoPath: String,
    private val viewIndex: Int,
    private val templateId: String,
    private val templateName: String,
    private val partId: String,
    private val totalViews: Int,
    private val inferenceService: NanoDetRoiInferenceService,
    private val imageStore: MobileImageStore,
    private val isFullImageFallback: Boolean = false,
) : ViewModel() {

    /** 当前 View 的 ROI 定义列表 */
    var rois by mutableStateOf<List<RoiDefinitionEntity>>(emptyList())
        private set

    /** 每个 ROI 的裁剪子图 (roiId → Bitmap) */
    val roiBitmaps = mutableStateMapOf<String, Bitmap>()

    /** Per-ROI model output for this saved photo; manual confirmation state remains separate. */
    val inferenceResults = mutableStateMapOf<String, NanoDetRoiInferenceResult>()

    /** 每个 ROI 的人工确认结果 (roiId → "OK"/"NG") */
    val roiResults = mutableStateMapOf<String, String>()

    /** 总体人工确认结果 */
    var overallResult by mutableStateOf<String?>(null)
        private set

    /**
     * 设置总体确认结果
     */
    fun selectOverallResult(result: String) {
        overallResult = result
    }

    /** 是否正在保存 */
    var isSaving by mutableStateOf(false)
        private set

    /** 错误信息 */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** 保存完成 */
    var saveCompleted by mutableStateOf(false)
        private set

    /** 已保存的改判证据（roiId → path + overrideTime），从数据库恢复。 */
    private data class EvidenceRef(val path: String, val overrideTime: Long)
    private val savedOverrideEvidence = mutableMapOf<String, EvidenceRef>()
    private val savedSimilarityEvidence = mutableMapOf<String, String>()
    private val newSimilarityEvidencePaths = mutableSetOf<String>()

    /** 整图推理结果（FALLBACK_FULL_IMAGE 或 FAILED 路径） */
    var fullImageInferResult by mutableStateOf<FullImageInferResult?>(null)
        private set

    /** 当前是否为整图确认模式 */
    var isFullImageMode by mutableStateOf(false)
        private set

    /** 加载完成 */
    var isLoaded by mutableStateOf(false)
        private set

    private var photoGeometry: RoiCoordinateMapper.PhotoGeometry? = null
    private var templateExifOrientation: Int? = null
    private var templateImagePath: String? = null
    private var photoAssociationValid = false
    private val similarityFallback = RoiSimilarityFallback()

    /**
     * 投影 ROI 像素坐标缓存（roiId → ContentRectBounds）。
     * 在 PROJECTED 路径推理时填充，saveRoiConfirms() 直接复用，
     * 确保保存的 roiPixelRect 与 NanoDet 检测输入完全一致。
     */
    private val projectedPixelRects = mutableMapOf<String, ContentRectBounds>()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                val photo = repository.getCapturedPhoto(photoId)
                val photoMatchesView = photo != null &&
                    photo.photoId == photoId &&
                    photo.batchId == batchId &&
                    photo.filePath == photoPath &&
                    photo.viewIndex == viewIndex &&
                    photo.templateId == templateId
                if (!photoMatchesView) {
                    rois = repository.getRois(templateId).filter { it.enabled }
                    errorMessage = "照片记录与当前视角不一致"
                    setInferenceFailure(NanoDetInferenceStatus.PHOTO_ASSOCIATION_ERROR, errorMessage!!)
                    isLoaded = true
                    return@launch
                }
                photoAssociationValid = true
                val roiList = repository.getRois(templateId).filter { it.enabled }
                rois = roiList
                restoreManualSelections(repository.getViewRoiConfirmsByPhoto(batchId, photoId), roiList)
                templateImagePath = repository.getTemplate(templateId)?.mainImagePath
                templateExifOrientation = withContext(Dispatchers.IO) {
                    templateImagePath?.let(RoiCoordinateMapper::getImageGeometry)?.exifOrientation
                }

                val geometry = withContext(Dispatchers.IO) {
                    RoiCoordinateMapper.getImageGeometry(photoPath)
                }
                if (geometry == null) {
                    errorMessage = "无法读取照片"
                    setInferenceFailure(NanoDetInferenceStatus.IMAGE_UNREADABLE, errorMessage!!)
                    isLoaded = true
                    return@launch
                }
                photoGeometry = geometry

                // 读取进程级 Session ROI 缓存
                val cached = SessionRoiRegistry.readAndConsume(batchId, photoId, viewIndex)
                val path = resolveLoadingPath(cached, isFullImageFallback)

                when (path) {
                    LoadingPath.PROJECTED -> {
                        loadWithProjectedRois(roiList, geometry, cached!!.sessionRois)
                    }
                    LoadingPath.FULL_IMAGE -> {
                        if (cached != null && cached.registrationStatus != RegistrationStatus.SUCCESS) {
                            Log.w(TAG, "Registry status=${cached.registrationStatus}, using full-image fallback")
                        }
                        if (cached != null && cached.sessionRois.isEmpty()) {
                            Log.w(TAG, "Registry sessionRois empty, using full-image fallback")
                        }
                        if (cached == null && !isFullImageFallback) {
                            Log.w(TAG, "Registry cache miss (batchId=$batchId, photoId=$photoId, viewIndex=$viewIndex); " +
                                "fail-closed to full-image mode")
                        }
                        loadFullImageFallback(geometry)
                    }
                    LoadingPath.TEMPLATE -> {
                        // 理论上不应到达（resolveLoadingPath 不返回 TEMPLATE）
                        Log.w(TAG, "Unexpected TEMPLATE path — falling back to full-image mode")
                        loadFullImageFallback(geometry)
                    }
                }
                isLoaded = true
            } catch (e: Exception) {
                errorMessage = "加载失败：${e.message ?: "未知错误"}"
                setInferenceFailure(NanoDetInferenceStatus.INFERENCE_ERROR, errorMessage!!)
                isLoaded = true
            }
        }
    }

    /** SUCCESS 路径：使用 Session ROI 投影坐标裁剪和推理。 */
    private suspend fun loadWithProjectedRois(
        roiList: List<RoiDefinitionEntity>,
        geometry: RoiCoordinateMapper.PhotoGeometry,
        sessionRois: List<SessionRoi>,
    ) {
        val sessionRoiMap = sessionRois.associateBy { it.id }

        // 全量覆盖检查：所有可检测 ROI 都必须有对应的投影 SessionRoi
        val missingRois = roiList.filter { it.id !in sessionRoiMap }
        if (missingRois.isNotEmpty()) {
            // 缺少任一 ROI 的投影坐标 → 整体拒绝，绝不保留原模板 normalizedRect
            Log.w(TAG, "Projected ROIs missing for ${missingRois.size}/${roiList.size} ROIs: " +
                "${missingRois.take(5).map { it.id }} — rejecting batch, falling back to full-image mode")
            loadFullImageFallback(geometry)
            return
        }

        // 使用投影坐标裁剪 ROI 子图，并缓存像素坐标供 saveRoiConfirms() 复用
        val loadedBitmaps = withContext(Dispatchers.IO) {
            buildMap {
                for (roi in roiList) {
                    val sessionRoi = sessionRoiMap[roi.id]!!
                    val pixelRect = RoiCoordinateMapper.mapToImagePixels(sessionRoi.rect, geometry.width, geometry.height)
                    projectedPixelRects[roi.id] = pixelRect  // 缓存：与 NanoDet 输入一致
                    val bitmap = RoiCoordinateMapper.cropRoiBitmap(photoPath, pixelRect, inSampleSize = 2)
                    if (bitmap != null) put(roi.id, bitmap)
                }
            }
        }
        roiBitmaps.putAll(loadedBitmaps)

        // 构造投影坐标版本的 RoiDefinitionEntity 列表
        // normalizedRect 替换为投影坐标 JSON，templateExifOrientation 设为 NORMAL
        // 这样 mapTemplateRoiToPhotoPixels 内部的 transformNormalizedRect 成为单位变换
        val projectedRoiList = roiList.map { roi ->
            val sessionRoi = sessionRoiMap[roi.id]!!
            roi.copy(normalizedRect = sessionRoi.rect.toJsonString())
        }

        try {
            val detected = withContext(Dispatchers.IO) {
                inferenceService.inferSavedPhoto(
                    photoPath,
                    projectedRoiList,
                    android.media.ExifInterface.ORIENTATION_NORMAL,
                )
            }
            roiList.forEach { roi ->
                inferenceResults[roi.id] = detected[roi.id] ?: NanoDetRoiInferenceResult(
                    roiId = roi.id,
                    status = NanoDetInferenceStatus.INFERENCE_ERROR,
                    modelSuggestion = null,
                    matchingScore = null,
                    targetClassIndex = NanoDetDecisionPolicy.classIndex(RoiTargetType.fromName(roi.targetType)),
                    detail = "推理服务未返回该 ROI 结果"
                )
            }
            runSimilarityFallback(roiList)
        } catch (e: Exception) {
            errorMessage = "NanoDet 推理失败：${e.message ?: "未知错误"}"
            setInferenceFailure(NanoDetInferenceStatus.INFERENCE_ERROR, errorMessage!!)
        }
        applyDefaultSelections(roiList)
    }

    /** FALLBACK 路径：整图推理。 */
    private suspend fun loadFullImageFallback(geometry: RoiCoordinateMapper.PhotoGeometry) {
        isFullImageMode = true
        try {
            val result = withContext(Dispatchers.IO) {
                inferenceService.inferFullImage(photoPath)
            }
            fullImageInferResult = result

            // 为所有 ROI 设置推理失败状态（整图模式不按 ROI 分析）
            rois.forEach { roi ->
                inferenceResults[roi.id] = NanoDetRoiInferenceResult(
                    roiId = roi.id,
                    status = NanoDetInferenceStatus.NO_DETECTION,
                    modelSuggestion = result.aggregatedSuggestion,
                    matchingScore = result.highestScore,
                    targetClassIndex = NanoDetDecisionPolicy.classIndex(RoiTargetType.fromName(roi.targetType)),
                    threshold = result.threshold,
                    imageWidth = result.imageWidth,
                    imageHeight = result.imageHeight,
                    exifOrientation = result.exifOrientation,
                    detail = "整图检测模式（${result.detections.size} 个检出）",
                )
            }
        } catch (e: Exception) {
            errorMessage = "整图推理失败：${e.message ?: "未知错误"}"
            fullImageInferResult = FullImageInferResult(
                detections = emptyList(), aggregatedSuggestion = null, highestScore = null,
                elapsedMs = 0, imageWidth = geometry.width, imageHeight = geometry.height,
                exifOrientation = geometry.exifOrientation,
                status = NanoDetInferenceStatus.INFERENCE_ERROR,
                detail = e.message,
            )
        }
    }

    /** 默认路径：使用模板坐标裁剪和推理（原有逻辑）。 */
    private suspend fun loadWithTemplateRois(
        roiList: List<RoiDefinitionEntity>,
        geometry: RoiCoordinateMapper.PhotoGeometry,
    ) {
        val templateGeometry = withContext(Dispatchers.IO) {
            templateImagePath?.let(RoiCoordinateMapper::getImageGeometry)
        }
        templateExifOrientation = templateGeometry?.exifOrientation
        val displayTemplateOrientation = templateExifOrientation
            ?: android.media.ExifInterface.ORIENTATION_NORMAL

        val loadedBitmaps = withContext(Dispatchers.IO) {
            buildMap {
                for (roi in roiList) {
                    val normalizedRect = RoiCoordinateMapper.parseNormalizedRect(roi.normalizedRect)
                    if (normalizedRect != null) {
                        val pixelRect = RoiCoordinateMapper.mapTemplateRoiToPhotoPixels(
                            normalizedRect,
                            displayTemplateOrientation,
                            geometry,
                        )
                        projectedPixelRects[roi.id] = pixelRect
                        val bitmap = RoiCoordinateMapper.cropRoiBitmap(photoPath, pixelRect, inSampleSize = 2)
                        if (bitmap != null) put(roi.id, bitmap)
                    }
                }
            }
        }
        roiBitmaps.putAll(loadedBitmaps)

        try {
            val detected = withContext(Dispatchers.IO) {
                inferenceService.inferSavedPhoto(photoPath, roiList, templateExifOrientation)
            }
            roiList.forEach { roi ->
                inferenceResults[roi.id] = detected[roi.id] ?: NanoDetRoiInferenceResult(
                    roiId = roi.id,
                    status = NanoDetInferenceStatus.INFERENCE_ERROR,
                    modelSuggestion = null,
                    matchingScore = null,
                    targetClassIndex = NanoDetDecisionPolicy.classIndex(RoiTargetType.fromName(roi.targetType)),
                    detail = "推理服务未返回该 ROI 结果"
                )
            }
            runSimilarityFallback(roiList)
        } catch (e: Exception) {
            errorMessage = "NanoDet 推理失败：${e.message ?: "未知错误"}"
            setInferenceFailure(NanoDetInferenceStatus.INFERENCE_ERROR, errorMessage!!)
        }
        applyDefaultSelections(roiList)
    }

    /** Keep NanoDet's original status while attaching an independent widget similarity result. */
    private suspend fun runSimilarityFallback(currentRois: List<RoiDefinitionEntity>) {
        val targets = currentRois.filter { roi ->
            RoiSimilarityFallbackPolicy.shouldRun(
                inferenceResults[roi.id],
                RoiTargetType.fromName(roi.targetType),
            )
        }
        if (targets.isEmpty()) return
        for (roi in targets) {
            val result = inferenceResults[roi.id] ?: continue
            val bitmap = roiBitmaps[roi.id]
            val safeId = roi.id.filter(Char::isLetterOrDigit).take(12).ifBlank { "roi" }
            val fileName = "sim_${batchId.take(8)}_${photoId}_${viewIndex}_${safeId}_${UUID.randomUUID().toString().take(8)}.jpg"
            val execution = runRoiSimilarityAfterSavingEvidence(
                bitmapAvailable = bitmap != null,
                saveEvidence = {
                    withContext(Dispatchers.IO) {
                        bitmap?.let { imageStore.saveRoiEvidence(it, fileName) }
                    }
                },
                compare = { _ ->
                    withContext(Dispatchers.IO) {
                        similarityFallback.evaluate(partId, templateImagePath, photoPath, roi)
                    }
                },
            )
            val imagePath = execution.evidencePath
            if (imagePath != null) newSimilarityEvidencePaths += imagePath
            inferenceResults[roi.id] = result.copy(
                similarity = execution.result,
                similarityEvidencePath = imagePath,
                similarityEvidenceStatus = execution.evidenceStatus,
            )
        }
    }

    private fun restoreManualSelections(
        saved: List<ViewRoiConfirmEntity>,
        currentRois: List<RoiDefinitionEntity>
    ) {
        val matchingRows = saved.filter { row ->
            row.batchId == batchId &&
                row.photoId == photoId &&
                row.photoPath == photoPath &&
                row.viewIndex == viewIndex &&
                row.templateId == templateId &&
                currentRois.any { it.id == row.roiId }
        }
        matchingRows.forEach { row ->
            if (row.humanResult == "OK" || row.humanResult == "NG") {
                roiResults[row.roiId] = row.humanResult
            }
            if (!row.roiEvidencePath.isNullOrBlank() && row.overrideTime != null) {
                savedOverrideEvidence[row.roiId] = EvidenceRef(row.roiEvidencePath, row.overrideTime)
            }
            row.similarityRoiEvidencePath?.let { savedSimilarityEvidence[row.roiId] = it }
        }
        val overallValues = matchingRows.map { it.overallResult }.distinct()
        if (overallValues.size == 1 && overallValues.single() in setOf("OK", "NG")) {
            overallResult = overallValues.single()
        }
    }

    /**
     * 对没有已保存人工选择且模型有建议的 ROI，将模型值设为默认选中。
     * 已有人工选择时保留不覆盖。
     */
    private fun applyDefaultSelections(currentRois: List<RoiDefinitionEntity>) {
        currentRois.forEach { roi ->
            if (roiResults.containsKey(roi.id)) return@forEach
            val inf = inferenceResults[roi.id] ?: return@forEach
            val suggestion = inf.modelSuggestion ?: return@forEach
            roiResults[roi.id] = suggestion.name
        }
    }

    /**
     * 设置某个 ROI 的确认结果
     */
    fun setRoiResult(roiId: String, result: String) {
        roiResults[roiId] = result
    }

    private fun setInferenceFailure(status: NanoDetInferenceStatus, detail: String) {
        rois.forEach { roi ->
            inferenceResults[roi.id] = NanoDetRoiInferenceResult(
                roiId = roi.id,
                status = status,
                modelSuggestion = null,
                matchingScore = null,
                targetClassIndex = NanoDetDecisionPolicy.classIndex(RoiTargetType.fromName(roi.targetType)),
                detail = detail
            )
        }
    }

    /**
     * 所有 ROI 和总体结果是否已选择
     */
    fun isAllConfirmed(): Boolean {
        if (!isLoaded || !photoAssociationValid) return false
        if (overallResult == null) return false
        // 整图模式只需要总体结果
        if (isFullImageMode) return true
        return rois.all { roiResults.containsKey(it.id) }
    }

    /**
     * 保存确认结果到数据库
     */
    fun saveConfirmation() {
        if (isSaving || saveCompleted) return
        if (!isLoaded || !photoAssociationValid) {
            errorMessage = "照片关联无效，不能保存人工终审"
            return
        }
        if (!isAllConfirmed()) {
            errorMessage = "请完成所有选择"
            return
        }
        if (!isFullImageMode && rois.isEmpty()) {
            errorMessage = "当前视角无 ROI，无需人工确认"
            return
        }

        isSaving = true
        errorMessage = null

        viewModelScope.launch {
            val newEvidenceFiles = mutableListOf<String>()
            try {
                inferenceResults.values.mapNotNull { it.similarityEvidencePath }
                    .filter { it in newSimilarityEvidencePaths }
                    .distinct()
                    .forEach(newEvidenceFiles::add)
                val now = System.currentTimeMillis()
                val overall = overallResult!!
                val geometry = photoGeometry

                val confirms = if (isFullImageMode) {
                    // ── 整图模式：创建单条 __FULL_IMAGE__ 确认记录 ──
                    val fullResult = fullImageInferResult
                    val fullImageRoi = RoiDefinitionEntity(
                        id = FULL_IMAGE_ROI_ID,
                        templateId = templateId,
                        name = "整图检测",
                        order = 0,
                        normalizedRect = NORMALIZED_FULL_IMAGE_RECT,
                        inspectionType = "VISUAL",
                        enabled = true,
                        targetType = null,
                    )
                    val pixelRectJson = if (geometry != null) {
                        JSONObject().apply {
                            put("left", 0); put("top", 0)
                            put("right", geometry.width); put("bottom", geometry.height)
                        }.toString()
                    } else ContentRectBounds(0, 0, 0, 0).let {
                        JSONObject().apply {
                            put("left", it.left); put("top", it.top)
                            put("right", it.right); put("bottom", it.bottom)
                        }.toString()
                    }
                    // 构造一个模拟推理结果，包含整图检测的所有 detections
                    val syntheticInference = NanoDetRoiInferenceResult(
                        roiId = FULL_IMAGE_ROI_ID,
                        status = fullResult?.status ?: NanoDetInferenceStatus.INFERENCE_ERROR,
                        modelSuggestion = fullResult?.aggregatedSuggestion,
                        matchingScore = fullResult?.highestScore,
                        targetClassIndex = null,
                        threshold = fullResult?.threshold ?: NanoDetModelContract.STARTING_BUSINESS_THRESHOLD,
                        elapsedMs = fullResult?.elapsedMs ?: 0,
                        imageWidth = fullResult?.imageWidth,
                        imageHeight = fullResult?.imageHeight,
                        exifOrientation = fullResult?.exifOrientation,
                        detections = fullResult?.detections ?: emptyList(),
                        detail = fullResult?.detail,
                        modelVersion = fullResult?.modelVersion ?: NanoDetModelContract.VERSION,
                        modelParamSha256 = fullResult?.modelParamSha256 ?: NanoDetModelContract.PARAM_SHA256,
                        modelSha256 = fullResult?.modelSha256 ?: NanoDetModelContract.MODEL_SHA256,
                    )
                    listOf(
                        buildViewRoiConfirmEntity(
                            batchId = batchId,
                            photoId = photoId,
                            photoPath = photoPath,
                            viewIndex = viewIndex,
                            templateId = templateId,
                            templateName = templateName,
                            roi = fullImageRoi,
                            roiPixelRect = pixelRectJson,
                            inference = syntheticInference,
                            humanResult = overall,
                            overallResult = overall,
                            confirmedAt = now,
                        )
                    )
                } else {
                    // ── ROI 模式：按 ROI 逐条保存 ──
                    saveRoiConfirms(now, geometry, newEvidenceFiles)
                }

                // 保存数据库
                repository.replaceViewRoiConfirmsForPhoto(batchId, photoId, confirms)
                val persisted = repository.getViewRoiConfirmsByPhoto(batchId, photoId)
                check(persisted.size == confirms.size && confirms.all { expected ->
                    persisted.any { actual ->
                        actual.batchId == expected.batchId &&
                            actual.photoId == expected.photoId &&
                            actual.photoPath == expected.photoPath &&
                            actual.viewIndex == expected.viewIndex &&
                            actual.templateId == expected.templateId &&
                            actual.roiId == expected.roiId &&
                            actual.humanResult == expected.humanResult &&
                            actual.overallResult == expected.overallResult &&
                            actual.softwareResult == expected.softwareResult &&
                            actual.softwareStatus == expected.softwareStatus &&
                            actual.softwareDetectionsJson == expected.softwareDetectionsJson &&
                            actual.humanChangedModel == expected.humanChangedModel &&
                            actual.overrideTime == expected.overrideTime &&
                            actual.roiEvidencePath == expected.roiEvidencePath &&
                            actual.similarityStatus == expected.similarityStatus &&
                            actual.similarityScore == expected.similarityScore &&
                            actual.similarityThreshold == expected.similarityThreshold &&
                            actual.similarityCandidate == expected.similarityCandidate &&
                            actual.similarityRoiEvidencePath == expected.similarityRoiEvidencePath
                    }
                }) { "确认记录保存校验失败" }

                if (!isFullImageMode) {
                    val retainedPaths = confirms.flatMap { listOfNotNull(it.roiEvidencePath, it.similarityRoiEvidencePath) }.toSet()
                    (savedOverrideEvidence.values.map { it.path } + savedSimilarityEvidence.values)
                        .distinct().filterNot(retainedPaths::contains).forEach(imageStore::deleteRoiEvidence)
                    savedOverrideEvidence.clear()
                    roiOverrideStates.filter { it.humanChangedModel && it.roiEvidencePath != null }.forEach {
                        savedOverrideEvidence[it.roiId] = EvidenceRef(it.roiEvidencePath!!, it.overrideTime!!)
                    }
                    savedSimilarityEvidence.clear()
                    confirms.mapNotNull { confirm -> confirm.similarityRoiEvidencePath?.let { confirm.roiId to it } }
                        .forEach { (roiId, path) -> savedSimilarityEvidence[roiId] = path }
                    newSimilarityEvidencePaths.clear()
                }

                saveCompleted = true
            } catch (e: Exception) {
                newEvidenceFiles.forEach { imageStore.deleteRoiEvidence(it) }
                errorMessage = "保存失败：${e.message}"
            } finally {
                isSaving = false
            }
        }
    }

    /** ROI 模式的 override 状态缓存 */
    private data class OverrideState(
        val roiId: String,
        val humanChangedModel: Boolean,
        val overrideTime: Long?,
        val roiEvidencePath: String?,
        val isNewEvidence: Boolean,
    )
    private var roiOverrideStates = listOf<OverrideState>()

    /** 按 ROI 逐条构建确认记录（ROI 模式）。 */
    private suspend fun saveRoiConfirms(
        now: Long,
        geometry: RoiCoordinateMapper.PhotoGeometry?,
        newEvidenceFiles: MutableList<String>,
    ): List<ViewRoiConfirmEntity> {
        val overall = overallResult!!
        val overrideStates = mutableListOf<OverrideState>()
        var evidenceSaveError: String? = null

        if (inferenceResults.values.any { it.similarity.wasRun && it.similarityEvidenceStatus != "SAVED" }) {
            throw IllegalStateException("相似度 ROI 图片未成功保存，不能确认结果")
        }

        for (roi in rois) {
            val human = roiResults.getValue(roi.id)
            val suggestion = inferenceResults[roi.id]?.modelSuggestion
            val changed = suggestion != null && suggestion.name != human
            val existingRef = savedOverrideEvidence[roi.id]
            val existingValid = existingRef != null && imageStore.roiEvidenceFileValid(existingRef.path)
            val similarityEvidence = inferenceResults[roi.id]?.similarityEvidencePath
                ?.takeIf(imageStore::roiEvidenceFileValid)

            if (!changed) {
                overrideStates += OverrideState(roi.id, false, null, null, false)
            } else if (similarityEvidence != null) {
                overrideStates += OverrideState(roi.id, true, now, similarityEvidence, false)
            } else {
                if (existingValid) {
                    overrideStates += OverrideState(roi.id, true, existingRef!!.overrideTime, existingRef.path, false)
                } else {
                    val roiBitmap = roiBitmaps[roi.id]
                    if (roiBitmap == null) {
                        evidenceSaveError = "ROI 裁剪图不可用，无法保存改判证据"
                        overrideStates += OverrideState(roi.id, true, null, null, true)
                        continue
                    }
                    val evidenceFileName = "roi_${batchId.take(8)}_${photoId}_${templateId.take(8)}_${viewIndex}_${roi.id.take(8)}_$now.jpg"
                    val savedPath = withContext(Dispatchers.IO) {
                        imageStore.saveRoiEvidence(roiBitmap, evidenceFileName)
                    }
                    if (savedPath == null) {
                        evidenceSaveError = "改判证据图写入失败"
                        overrideStates += OverrideState(roi.id, true, null, null, true)
                        continue
                    }
                    newEvidenceFiles += savedPath
                    overrideStates += OverrideState(roi.id, true, now, savedPath, true)
                }
            }
        }

        val hasNewEvidenceFailure = overrideStates.any { it.isNewEvidence && it.roiEvidencePath == null }
        if (hasNewEvidenceFailure) {
            newEvidenceFiles.forEach { imageStore.deleteRoiEvidence(it) }
            throw IllegalStateException(evidenceSaveError ?: "改判证据保存失败")
        }

        roiOverrideStates = overrideStates

        return rois.map { roi ->
            // 使用 projected 路径缓存的像素坐标（与 NanoDet 检测输入一致）
            // projected 坐标缺失时 fail-closed：整图 bounds 或中止保存
            val pixelRect = projectedPixelRects[roi.id]
                ?: if (geometry != null) {
                    ContentRectBounds(0, 0, geometry.width, geometry.height)
                } else {
                    throw IllegalStateException(
                        "saveRoiConfirms: projectedPixelRects missing for roiId=${roi.id} and geometry is null"
                    )
                }
            val pixelRectJson = JSONObject().apply {
                put("left", pixelRect.left)
                put("top", pixelRect.top)
                put("right", pixelRect.right)
                put("bottom", pixelRect.bottom)
            }.toString()

            val os = overrideStates.first { it.roiId == roi.id }
            buildViewRoiConfirmEntity(
                batchId = batchId,
                photoId = photoId,
                photoPath = photoPath,
                viewIndex = viewIndex,
                templateId = templateId,
                templateName = templateName,
                roi = roi,
                roiPixelRect = pixelRectJson,
                inference = inferenceResults[roi.id],
                humanResult = roiResults.getValue(roi.id),
                overallResult = overall,
                confirmedAt = now,
                overrideTime = os.overrideTime,
                roiEvidencePath = os.roiEvidencePath,
                similarityRoiEvidencePath = inferenceResults[roi.id]?.similarityEvidencePath,
            )
        }
    }

    /**
     * 是否是最后一个 View
     */
    fun isLastView(): Boolean = viewIndex >= totalViews - 1

    /**
     * 清理 Bitmap 资源
     */
    override fun onCleared() {
        super.onCleared()
        if (!saveCompleted) newSimilarityEvidencePaths.forEach(imageStore::deleteRoiEvidence)
        roiBitmaps.values.forEach { it.recycle() }
        roiBitmaps.clear()
        inferenceService.close()
    }

    companion object {
        private const val TAG = "ViewConfirmVM"
        /** 整图确认模式使用的虚拟 ROI ID */
        const val FULL_IMAGE_ROI_ID = "__FULL_IMAGE__"
        /** 整图确认模式使用的归一化矩形（覆盖全图） */
        private const val NORMALIZED_FULL_IMAGE_RECT = """{"left":0.0,"top":0.0,"right":1.0,"bottom":1.0}"""

        fun factory(
            repository: InspectionRepository,
            batchId: String,
            photoId: Long,
            photoPath: String,
            viewIndex: Int,
            templateId: String,
            templateName: String,
            partId: String,
            totalViews: Int,
            inferenceService: NanoDetRoiInferenceService,
            imageStore: MobileImageStore,
            isFullImageFallback: Boolean = false,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ViewConfirmationViewModel(
                    repository = repository,
                    batchId = batchId,
                    photoId = photoId,
                    photoPath = photoPath,
                    viewIndex = viewIndex,
                    templateId = templateId,
                    templateName = templateName,
                    partId = partId,
                    totalViews = totalViews,
                    inferenceService = inferenceService,
                    imageStore = imageStore,
                    isFullImageFallback = isFullImageFallback,
                ) as T
            }
        }
    }
}

/** 加载路径枚举，用于测试验证分支路由。 */
internal enum class LoadingPath { PROJECTED, FULL_IMAGE, TEMPLATE }

/**
 * 根据 registry 缓存状态和 fallback 标志决定加载路径。
 *
 * 规则：
 * - SUCCESS + 非空 sessionRois → PROJECTED
 * - isFullImageFallback 或 registry 存在但非 SUCCESS → FULL_IMAGE
 * - 缓存缺失 + 非 fallback → FULL_IMAGE（fail-closed，禁止静默回退到模板坐标）
 *
 * 禁止在 registry 缺失/不一致时调用 TEMPLATE 路径。
 */
internal fun resolveLoadingPath(
    cached: SessionRoiRegistry.Entry?,
    isFullImageFallback: Boolean,
): LoadingPath = when {
    // isFullImageFallback 是导航层的权威信号：显式标记时始终使用整图
    isFullImageFallback -> LoadingPath.FULL_IMAGE
    // SUCCESS + 有投影 ROI → 使用投影坐标
    cached != null &&
        cached.registrationStatus == RegistrationStatus.SUCCESS &&
        cached.sessionRois.isNotEmpty() -> LoadingPath.PROJECTED
    // registry 存在但状态不一致 → fail-closed 到整图
    cached != null -> LoadingPath.FULL_IMAGE
    // 缓存缺失 + 非 fallback → fail-closed 到整图（禁止回退到模板）
    else -> LoadingPath.FULL_IMAGE
}

/**
 * 验证所有可检测 ROI 是否都有对应的投影 SessionRoi。
 * 缺少任一 ROI 时返回 false（整体拒绝）。
 */
internal fun allRoisProjected(
    roiIds: List<String>,
    sessionRois: List<SessionRoi>,
): Boolean {
    val projectedIds = sessionRois.map { it.id }.toSet()
    return roiIds.all { it in projectedIds }
}

internal fun buildViewRoiConfirmEntity(
    batchId: String,
    photoId: Long,
    photoPath: String,
    viewIndex: Int,
    templateId: String,
    templateName: String,
    roi: RoiDefinitionEntity,
    roiPixelRect: String,
    inference: NanoDetRoiInferenceResult?,
    humanResult: String,
    overallResult: String,
    confirmedAt: Long,
    overrideTime: Long? = null,
    roiEvidencePath: String? = null,
    similarityRoiEvidencePath: String? = inference?.similarityEvidencePath
): ViewRoiConfirmEntity {
    require(humanResult == "OK" || humanResult == "NG")
    require(overallResult == "OK" || overallResult == "NG")
    val detectionsJson = inference?.detections?.let { detections ->
        JSONArray().apply {
            detections.forEach { detection ->
                put(JSONObject().apply {
                    put("classIndex", detection.classIndex)
                    put("className", detection.className)
                    put("score", detection.score.toDouble())
                    put("point", detection.point)
                    put("roiBox", boxJson(detection.roiBox))
                    put("imageBox", boxJson(detection.imageBox))
                })
            }
        }.toString()
    }
    val modelSummary = inference?.let { result ->
        JSONObject().apply {
            put("modelVersion", result.modelVersion)
            put("modelParamSha256", result.modelParamSha256)
            put("modelSha256", result.modelSha256)
            put("inputShape", result.inputShape)
            put("outputBlob", result.outputBlob)
            put("outputShape", result.outputShape)
            put("candidateThreshold", result.candidateThreshold?.toDouble() ?: JSONObject.NULL)
            put("imageWidth", result.imageWidth ?: JSONObject.NULL)
            put("imageHeight", result.imageHeight ?: JSONObject.NULL)
            put("exifOrientation", result.exifOrientation ?: JSONObject.NULL)
            put("roiBounds", result.roiBounds?.let { bounds -> JSONArray(bounds) } ?: JSONObject.NULL)
            put("detail", result.detail ?: JSONObject.NULL)
            put("similarityStatus", result.similarity.status.name)
            put("similarityScore", result.similarity.score?.toDouble() ?: JSONObject.NULL)
            put("similarityThreshold", result.similarity.threshold?.toDouble() ?: JSONObject.NULL)
            put("similarityCandidate", result.similarity.candidate?.name ?: JSONObject.NULL)
            put("similarityEvidenceStatus", result.similarityEvidenceStatus ?: JSONObject.NULL)
        }.toString()
    }
    val targetClass = inference?.targetClassIndex?.let { index ->
        when (index) {
            0 -> "NUT"
            1 -> "THREAD"
            2 -> "BOLT"
            3 -> "NUTSERT"
            else -> "CLASS_$index"
        }
    }
    val decisionWasApplied = inference?.status in setOf(
        NanoDetInferenceStatus.DETECTED,
        NanoDetInferenceStatus.DETECTED_BELOW_THRESHOLD,
        NanoDetInferenceStatus.NO_DETECTION
    )

    return ViewRoiConfirmEntity(
        batchId = batchId,
        photoId = photoId,
        photoPath = photoPath,
        viewIndex = viewIndex,
        templateId = templateId,
        templateName = templateName,
        roiId = roi.id,
        roiName = roi.name,
        roiTargetType = roi.targetType,
        roiNormalizedRect = roi.normalizedRect,
        roiPixelRect = roiPixelRect,
        softwareResult = inference?.modelSuggestion?.name,
        humanResult = humanResult,
        confirmTime = confirmedAt,
        overallResult = overallResult,
        overallConfirmTime = confirmedAt,
        softwareTargetClass = targetClass,
        softwareScore = inference?.matchingScore,
        softwareThreshold = if (decisionWasApplied) inference?.threshold else null,
        softwareDetectionsJson = detectionsJson,
        softwareStatus = inference?.status?.name,
        softwareModelVersion = inference?.modelVersion,
        softwareModelSummary = modelSummary,
        softwareElapsedMs = inference?.elapsedMs,
        humanChangedModel = inference?.modelSuggestion != null &&
            inference.modelSuggestion != NanoDetSuggestion.valueOf(humanResult),
        overrideTime = overrideTime,
        roiEvidencePath = roiEvidencePath,
        similarityStatus = inference?.similarity?.status?.name,
        similarityScore = inference?.similarity?.score,
        similarityThreshold = inference?.similarity?.threshold,
        similarityCandidate = inference?.similarity?.candidate?.name,
        similarityRoiEvidencePath = similarityRoiEvidencePath
    )
}

private fun boxJson(box: com.wearable.inspection.mobile.detection.NanoDetBox) = JSONObject().apply {
    put("left", box.left)
    put("top", box.top)
    put("right", box.right)
    put("bottom", box.bottom)
}
