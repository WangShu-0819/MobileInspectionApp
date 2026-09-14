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
    private val inferenceService: NanoDetRoiInferenceService
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

    /** 加载完成 */
    var isLoaded by mutableStateOf(false)
        private set

    private var photoGeometry: RoiCoordinateMapper.PhotoGeometry? = null
    private var templateExifOrientation: Int? = null

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
                    errorMessage = "照片记录与当前视角不一致"
                    setInferenceFailure(NanoDetInferenceStatus.PHOTO_ASSOCIATION_ERROR, errorMessage!!)
                    isLoaded = true
                    return@launch
                }

                // 加载模板的 ROI 列表
                val roiList = repository.getRois(templateId).filter { it.enabled }
                rois = roiList

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

                isLoaded = true
                val detected = withContext(Dispatchers.IO) {
                    inferenceService.inferSavedPhoto(photoPath, roiList, templateExifOrientation)
                }
                inferenceResults.putAll(detected)
            } catch (e: Exception) {
                if (!isLoaded) {
                    errorMessage = "加载失败：${e.message}"
                    setInferenceFailure(NanoDetInferenceStatus.IMAGE_UNREADABLE, errorMessage!!)
                }
                else {
                    rois.forEach { roi ->
                        inferenceResults[roi.id] = NanoDetRoiInferenceResult(
                            roiId = roi.id,
                            status = NanoDetInferenceStatus.INFERENCE_ERROR,
                            modelSuggestion = null,
                            matchingScore = null,
                            targetClassIndex = NanoDetDecisionPolicy.classIndex(RoiTargetType.fromName(roi.targetType)),
                            detail = e.message ?: "NanoDet 推理失败"
                        )
                    }
                }
                isLoaded = true
            }
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
        if (overallResult == null) return false
        return rois.all { roiResults.containsKey(it.id) }
    }

    /**
     * 保存确认结果到数据库
     */
    fun saveConfirmation() {
        if (isSaving || saveCompleted) return
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
            try {
                val now = System.currentTimeMillis()
                val overall = overallResult!!
                val geometry = photoGeometry

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

                    ViewRoiConfirmEntity(
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
                        roiPixelRect = pixelRectJson,
                        softwareResult = null,
                        humanResult = roiResults.getValue(roi.id),
                        confirmTime = now,
                        overallResult = overall,
                        overallConfirmTime = now
                    )
                }

                repository.insertViewRoiConfirms(confirms)
                val persisted = repository.getViewRoiConfirmsByView(batchId, viewIndex)
                check(confirms.all { expected ->
                    persisted.any { actual ->
                        actual.photoId == photoId &&
                            actual.templateId == templateId &&
                            actual.roiId == expected.roiId &&
                            actual.humanResult == expected.humanResult &&
                            actual.overallResult == expected.overallResult
                    }
                }) { "确认记录保存校验失败" }
                saveCompleted = true
            } catch (e: Exception) {
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
            inferenceService: NanoDetRoiInferenceService
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
                    inferenceService = inferenceService
                ) as T
            }
        }
    }
}
