package com.wearable.inspection.mobile.ui.screens

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.data.repository.InspectionRepository
import com.wearable.inspection.mobile.registration.PhotoRegistrationEngine
import com.wearable.inspection.mobile.registration.ProjectedPoint
import com.wearable.inspection.mobile.registration.RegistrationQualityGates
import com.wearable.inspection.mobile.registration.RegistrationResult
import com.wearable.inspection.mobile.registration.RegistrationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** 单次拍后比对页中的 ROI；不写回模板或结果数据库。 */
data class SessionRoi(
    val id: String,
    val name: String,
    val rect: NormalizedRect,
)

/**
 * V1-3 静态拍后比对 ViewModel。
 *
 * 这里消费 V4 的单张 [RegistrationResult]，不启动实时配准，也不参与 NanoDet/确认结果保存。
 */
class CaptureComparisonViewModel(
    private val repository: InspectionRepository,
    private val batchId: String,
    private val photoId: Long,
    private val photoPath: String,
    private val viewIndex: Int,
    private val templateId: String,
    private val templateName: String,
    private val partId: String,
    private val totalViews: Int,
) : ViewModel() {

    var templateBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var photoBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var alignedTemplateBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var sessionRois by mutableStateOf<List<SessionRoi>>(emptyList())
        private set
    /**
     * 配准引擎产生的投影 ROI 不可变快照。
     * 与 [sessionRois] 不同，此字段不受 UI 手动拖拽/缩放影响，
     * 用于 NanoDet 检测和确认结果保存。
     */
    var projectedRoisSnapshot by mutableStateOf<List<SessionRoi>>(emptyList())
        private set
    var registrationResult by mutableStateOf<RegistrationResult?>(null)
        private set
    var isLoaded by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** 配准已尝试即可进入确认页 */
    val canProceed: Boolean
        get() = canProceedToConfirmation(registrationResult, projectedRoisSnapshot)

    /** 是否需要整图检测回退（非 SUCCESS 或无投影 ROI） */
    val isFullImageFallback: Boolean
        get() = registrationResult == null ||
            registrationResult?.status != RegistrationStatus.SUCCESS ||
            projectedRoisSnapshot.isEmpty()

    init {
        loadComparison()
    }

    private fun loadComparison() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val loaded = loadFromStorage()
                templateBitmap = loaded.templateBitmap
                photoBitmap = loaded.photoBitmap
                alignedTemplateBitmap = loaded.alignedTemplateBitmap
                sessionRois = loaded.sessionRois
                projectedRoisSnapshot = loaded.projectedRoisSnapshot
                registrationResult = loaded.registrationResult
            } catch (error: Exception) {
                errorMessage = error.message ?: "比对图片加载失败"
            } finally {
                isLoaded = true
            }
        }
    }

    private suspend fun loadFromStorage(): LoadedComparison = withContext(Dispatchers.IO) {
        val capturedPhoto = repository.getCapturedPhoto(photoId)
            ?: error("照片记录不存在")
        check(
            capturedPhoto.batchId == batchId &&
                capturedPhoto.filePath == photoPath &&
                capturedPhoto.viewIndex == viewIndex &&
                capturedPhoto.templateId == templateId
        ) { "照片关联校验失败" }

        val template = repository.getTemplate(templateId)
            ?: error("模板不存在")
        val templateGeometry = RoiCoordinateMapper.getImageGeometry(template.mainImagePath)
            ?: error("模板图不可读取")
        val photoGeometry = RoiCoordinateMapper.getImageGeometry(photoPath)
            ?: error("现场照片不可读取")

        val templateImage = decodeUprightFullImage(template.mainImagePath, templateGeometry)
            ?: error("模板图不可解码")
        val photoImage = decodeUprightFullImage(photoPath, photoGeometry)
            ?: error("现场照片不可解码")
        val roiDefinitions = repository.getRois(templateId).filter { it.enabled }
        val validRois = roiDefinitions.mapNotNull { roi ->
            RoiCoordinateMapper.parseNormalizedRect(roi.normalizedRect)?.let { rect -> roi to rect }
        }

        if (validRois.isEmpty()) {
            return@withContext LoadedComparison(
                templateBitmap = templateImage,
                photoBitmap = photoImage,
                alignedTemplateBitmap = null,
                sessionRois = emptyList(),
                projectedRoisSnapshot = emptyList(),
                registrationResult = null,
            )
        }

        val registration = runRegistration(
            templateImage = templateImage,
            photoImage = photoImage,
            templateRoiCorners = templateCorners(validRois.first().second, templateGeometry.exifOrientation, templateImage),
        )
        val sessionRects = buildSessionRois(
            validRois = validRois,
            registration = registration,
            project = { normalizedRect ->
                if (registration.isSuccess && registration.homography != null) {
                    projectToScene(
                        normalizedRect = normalizedRect,
                        templateExifOrientation = templateGeometry.exifOrientation,
                        templateImage = templateImage,
                        sceneImage = photoImage,
                        homography = registration.homography,
                    )
                } else {
                    null
                }
            },
        )

        LoadedComparison(
            templateBitmap = templateImage,
            photoBitmap = photoImage,
            alignedTemplateBitmap = if (registration.isSuccess) {
                warpTemplate(templateImage, photoImage.width, photoImage.height, registration)
            } else {
                null
            },
            sessionRois = sessionRects,
            projectedRoisSnapshot = sessionRects,  // 同一份投影数据，不可变快照
            registrationResult = registration,
        )
    }

    private fun runRegistration(
        templateImage: Bitmap,
        photoImage: Bitmap,
        templateRoiCorners: List<ProjectedPoint>,
    ): RegistrationResult {
        val templateMat = Mat()
        val photoMat = Mat()
        val engine = PhotoRegistrationEngine()
        return try {
            Utils.bitmapToMat(templateImage, templateMat)
            Utils.bitmapToMat(photoImage, photoMat)
            engine.register(
                templateImage = templateMat,
                sceneImage = photoMat,
                templateRoiCorners = templateRoiCorners,
                sceneWidth = photoImage.width,
                sceneHeight = photoImage.height,
            )
        } finally {
            templateMat.release()
            photoMat.release()
            engine.clear()
        }
    }

    private fun templateCorners(
        normalizedRect: NormalizedRect,
        templateExifOrientation: Int,
        templateImage: Bitmap,
    ): List<ProjectedPoint> {
        val uprightRect = RoiCoordinateMapper.transformNormalizedRect(
            normalizedRect,
            templateExifOrientation,
        )
        val pixels = RoiCoordinateMapper.mapToImagePixels(
            uprightRect,
            templateImage.width,
            templateImage.height,
        )
        return listOf(
            ProjectedPoint(pixels.left.toDouble(), pixels.top.toDouble()),
            ProjectedPoint(pixels.right.toDouble(), pixels.top.toDouble()),
            ProjectedPoint(pixels.right.toDouble(), pixels.bottom.toDouble()),
            ProjectedPoint(pixels.left.toDouble(), pixels.bottom.toDouble()),
        )
    }

    private fun projectToScene(
        normalizedRect: NormalizedRect,
        templateExifOrientation: Int,
        templateImage: Bitmap,
        sceneImage: Bitmap,
        homography: DoubleArray,
    ): NormalizedRect? {
        val corners = templateCorners(normalizedRect, templateExifOrientation, templateImage)
        val projected = RegistrationQualityGates.transformPoints(homography, corners)
        if (projected.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        return RoiCoordinateMapper.computeProjectedRect(
            projectedCorners = projected,
            sceneWidth = sceneImage.width,
            sceneHeight = sceneImage.height,
            marginRatio = ROI_SAFETY_MARGIN_RATIO,
            minSize = MIN_SESSION_ROI_SIZE.toFloat(),
        )
    }

    private fun warpTemplate(
        templateImage: Bitmap,
        sceneWidth: Int,
        sceneHeight: Int,
        registration: RegistrationResult,
    ): Bitmap? {
        val homography = registration.homography ?: return null
        if (homography.size != 9) return null
        val source = Mat()
        val destination = Mat()
        val transform = Mat(3, 3, CvType.CV_64F)
        return try {
            transform.put(0, 0, *homography)
            Utils.bitmapToMat(templateImage, source)
            Imgproc.warpPerspective(
                source,
                destination,
                transform,
                Size(sceneWidth.toDouble(), sceneHeight.toDouble()),
            )
            Bitmap.createBitmap(sceneWidth, sceneHeight, Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(destination, it)
            }
        } catch (_: Exception) {
            null
        } finally {
            source.release()
            destination.release()
            transform.release()
        }
    }

    private fun decodeUprightFullImage(
        path: String,
        geometry: RoiCoordinateMapper.PhotoGeometry,
    ): Bitmap? = RoiCoordinateMapper.cropRoiBitmap(
        path,
        ContentRectBounds(0, 0, geometry.width, geometry.height),
        inSampleSize = 2,
    )

    override fun onCleared() {
        super.onCleared()
        listOf(templateBitmap, photoBitmap, alignedTemplateBitmap)
            .filterNotNull()
            .distinctBy { System.identityHashCode(it) }
            .forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
    }

    private data class LoadedComparison(
        val templateBitmap: Bitmap,
        val photoBitmap: Bitmap,
        val alignedTemplateBitmap: Bitmap?,
        val sessionRois: List<SessionRoi>,
        val projectedRoisSnapshot: List<SessionRoi>,
        val registrationResult: RegistrationResult?,
    )

    companion object {
        private const val MIN_SESSION_ROI_SIZE = 0.02
        /** Safety margin ratio applied to projected ROI AABB (10% of width/height total). */
        internal const val ROI_SAFETY_MARGIN_RATIO = 0.10f

        /**
         * 纯门禁函数：判断是否可以进入 ROI 确认页。
         * 配准已尝试（status 非 null）即可进入：
         * - SUCCESS → 使用投影 Session ROI 进行 per-ROI 检测
         * - FAILED / FALLBACK_FULL_IMAGE → 整图检测 + 人工审阅
         * - registration 为 null（配准未执行）→ 不可进入
         */
        fun canProceedToConfirmation(
            registration: RegistrationResult?,
            sessionRois: List<SessionRoi>,
        ): Boolean =
            registration != null

        /**
         * 纯函数：将模板 ROI 投影到现场坐标系（全量一致性策略）。
         *
         * 配准失败或 FALLBACK_FULL_IMAGE 时返回空列表。
         * 任一有效 ROI 投影失败（返回 null）时**整体丢弃**——
         * 不回退原始模板坐标、不保留部分 ROI。
         * 此函数不写入数据库，不修改 RegistrationResult。
         */
        internal fun buildSessionRois(
            validRois: List<Pair<RoiDefinitionEntity, NormalizedRect>>,
            registration: RegistrationResult,
            project: (NormalizedRect) -> NormalizedRect?,
        ): List<SessionRoi> {
            if (registration.status == RegistrationStatus.FAILED ||
                registration.status == RegistrationStatus.FALLBACK_FULL_IMAGE
            ) {
                return emptyList()
            }
            val projected = validRois.map { (roi, normalizedRect) ->
                val rect = project(normalizedRect) ?: return emptyList()
                SessionRoi(id = roi.id, name = roi.name, rect = rect)
            }
            return projected
        }

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
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CaptureComparisonViewModel(
                    repository = repository,
                    batchId = batchId,
                    photoId = photoId,
                    photoPath = photoPath,
                    viewIndex = viewIndex,
                    templateId = templateId,
                    templateName = templateName,
                    partId = partId,
                    totalViews = totalViews,
                ) as T
        }
    }
}
