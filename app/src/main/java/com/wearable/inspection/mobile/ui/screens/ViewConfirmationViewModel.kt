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
import com.wearable.inspection.mobile.detection.NanoDetDecisionPolicy
import com.wearable.inspection.mobile.detection.NanoDetInferenceStatus
import com.wearable.inspection.mobile.detection.NanoDetRoiInferenceResult
import com.wearable.inspection.mobile.detection.NanoDetRoiInferenceService
import com.wearable.inspection.mobile.detection.NanoDetSuggestion
import com.wearable.inspection.mobile.data.image.MobileImageStore
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
    private val photoPath: String,
    private val viewIndex: Int,
    private val templateId: String,
    private val templateName: String,
    private val partId: String,
    private val totalViews: Int,
    private val inferenceService: NanoDetRoiInferenceService,
    private val imageStore: MobileImageStore
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

    /** 加载完成 */
    var isLoaded by mutableStateOf(false)
        private set

    private var photoGeometry: RoiCoordinateMapper.PhotoGeometry? = null
    private var templateExifOrientation: Int? = null
    private var photoAssociationValid = false

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

                // 图片尺寸读取和 ROI 裁剪都可能访问/解码大 JPEG，必须离开主线程。
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
                val templateGeometry = withContext(Dispatchers.IO) {
                    repository.getTemplate(templateId)?.mainImagePath?.let(RoiCoordinateMapper::getImageGeometry)
                }
                templateExifOrientation = templateGeometry?.exifOrientation
                val displayTemplateOrientation = templateExifOrientation
                    ?: android.media.ExifInterface.ORIENTATION_NORMAL

                val loadedBitmaps = withContext(Dispatchers.IO) {
                    buildMap {
                        // 为每个 ROI 裁剪子图
                        for (roi in roiList) {
                            val normalizedRect = RoiCoordinateMapper.parseNormalizedRect(roi.normalizedRect)
                            if (normalizedRect != null) {
                                val pixelRect = RoiCoordinateMapper.mapTemplateRoiToPhotoPixels(
                                    normalizedRect,
                                    displayTemplateOrientation,
                                    geometry
                                )
                                val bitmap = RoiCoordinateMapper.cropRoiBitmap(photoPath, pixelRect, inSampleSize = 2)
                                if (bitmap != null) {
                                    put(roi.id, bitmap)
                                }
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
                } catch (e: Exception) {
                    errorMessage = "NanoDet 推理失败：${e.message ?: "未知错误"}"
                    setInferenceFailure(NanoDetInferenceStatus.INFERENCE_ERROR, errorMessage!!)
                }
                applyDefaultSelections(roiList)
                isLoaded = true
            } catch (e: Exception) {
                errorMessage = "加载失败：${e.message ?: "未知错误"}"
                setInferenceFailure(NanoDetInferenceStatus.INFERENCE_ERROR, errorMessage!!)
                isLoaded = true
            }
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
        if (rois.isEmpty()) {
            errorMessage = "当前视角无 ROI，无需人工确认"
            return
        }
        if (!isAllConfirmed()) {
            errorMessage = "请完成所有选择"
            return
        }

        isSaving = true
        errorMessage = null

        viewModelScope.launch {
            // 本轮新建的证据文件路径，DB 失败时需清理
            val newEvidenceFiles = mutableListOf<String>()
            try {
                val now = System.currentTimeMillis()
                val overall = overallResult!!
                val geometry = photoGeometry

                // 预计算每个 ROI 的改判证据状态
                data class OverrideState(
                    val roiId: String,
                    val humanChangedModel: Boolean,
                    val overrideTime: Long?,
                    val roiEvidencePath: String?,
                    val isNewEvidence: Boolean
                )
                val overrideStates = mutableListOf<OverrideState>()
                var evidenceSaveError: String? = null

                for (roi in rois) {
                    val human = roiResults.getValue(roi.id)
                    val suggestion = inferenceResults[roi.id]?.modelSuggestion
                    val changed = suggestion != null && suggestion.name != human
                    val existingRef = savedOverrideEvidence[roi.id]
                    val existingValid = existingRef != null && imageStore.roiEvidenceFileValid(existingRef.path)

                    if (!changed) {
                        // 未改判：旧证据保留到 DB 成功后再删除，此处只记录状态
                        overrideStates += OverrideState(roi.id, false, null, null, false)
                    } else {
                        if (existingValid) {
                            // 改判未变：保留已有证据和原始改判时间
                            overrideStates += OverrideState(roi.id, true, existingRef!!.overrideTime, existingRef.path, false)
                        } else {
                            // 新改判：生成并保存 ROI 证据图
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

                // 新改判证据写入失败时中止：清理本轮新建文件
                val hasNewEvidenceFailure = overrideStates.any { it.isNewEvidence && it.roiEvidencePath == null }
                if (hasNewEvidenceFailure) {
                    newEvidenceFiles.forEach { imageStore.deleteRoiEvidence(it) }
                    errorMessage = evidenceSaveError ?: "改判证据保存失败"
                    isSaving = false
                    return@launch
                }

                val confirms = rois.map { roi ->
                    val normalizedRect = RoiCoordinateMapper.parseNormalizedRect(roi.normalizedRect)
                    val pixelRect = if (normalizedRect != null && geometry != null) {
                        RoiCoordinateMapper.mapTemplateRoiToPhotoPixels(
                            normalizedRect,
                            templateExifOrientation ?: android.media.ExifInterface.ORIENTATION_NORMAL,
                            geometry
                        )
                    } else {
                        ContentRectBounds(0, 0, 0, 0)
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
                        roiEvidencePath = os.roiEvidencePath
                    )
                }

                // 先保存数据库
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
                            actual.roiEvidencePath == expected.roiEvidencePath
                    }
                }) { "确认记录保存校验失败" }

                // DB 成功后：删除不再需要的旧证据文件
                for (roi in rois) {
                    val os = overrideStates.first { it.roiId == roi.id }
                    if (!os.humanChangedModel) {
                        val oldRef = savedOverrideEvidence[roi.id]
                        if (oldRef != null) imageStore.deleteRoiEvidence(oldRef.path)
                    }
                }

                // DB 成功后：更新证据缓存
                savedOverrideEvidence.clear()
                overrideStates.filter { it.humanChangedModel && it.roiEvidencePath != null }.forEach {
                    savedOverrideEvidence[it.roiId] = EvidenceRef(it.roiEvidencePath!!, it.overrideTime!!)
                }

                saveCompleted = true
            } catch (e: Exception) {
                // DB 保存失败：清理本轮新建证据，保留旧证据和缓存不变
                newEvidenceFiles.forEach { imageStore.deleteRoiEvidence(it) }
                errorMessage = "保存失败：${e.message}"
            } finally {
                isSaving = false
            }
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
        roiBitmaps.values.forEach { it.recycle() }
        roiBitmaps.clear()
        inferenceService.close()
    }

    companion object {
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
            imageStore: MobileImageStore
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
                    imageStore = imageStore
                ) as T
            }
        }
    }
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
    roiEvidencePath: String? = null
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
            put("candidateThreshold", result.candidateThreshold.toDouble())
            put("imageWidth", result.imageWidth ?: JSONObject.NULL)
            put("imageHeight", result.imageHeight ?: JSONObject.NULL)
            put("exifOrientation", result.exifOrientation ?: JSONObject.NULL)
            put("roiBounds", result.roiBounds?.let { bounds -> JSONArray(bounds) } ?: JSONObject.NULL)
            put("detail", result.detail ?: JSONObject.NULL)
        }.toString()
    }
    val targetClass = inference?.targetClassIndex?.let { index ->
        when (index) {
            0 -> "NUT"
            1 -> "THREAD"
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
        roiEvidencePath = roiEvidencePath
    )
}

private fun boxJson(box: com.wearable.inspection.mobile.detection.NanoDetBox) = JSONObject().apply {
    put("left", box.left)
    put("top", box.top)
    put("right", box.right)
    put("bottom", box.bottom)
}
