package com.wearable.inspection.mobile.template

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wearable.inspection.mobile.camera.CameraController
import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import com.wearable.inspection.mobile.data.image.StoredImageResult
import com.wearable.inspection.mobile.data.repository.InspectionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * 模板拍摄 ViewModel
 *
 * 职责：
 * 1. 管理拍摄状态（IDLE / CAPTURING / SAVED / ERROR）
 * 2. 调用 CameraController.takePhoto()
 * 3. 存储模板图片到 template_images/
 * 4. 新增 View 或替换已有 View 的图片
 * 5. 失败回滚：新图写入失败时保留旧图和 DB 记录
 *
 * @param partId 零件 ID
 * @param templateId 要重拍的 View ID；null 表示新增 View
 */
class TemplateCaptureViewModel(
    private val repository: InspectionRepository,
    private val partId: String,
    private val templateId: String?,
) : ViewModel() {

    /**
     * 拍摄状态
     */
    sealed class CaptureState {
        /** 空闲，可拍摄 */
        object Idle : CaptureState()
        /** 拍摄中，禁用按钮 */
        object Capturing : CaptureState()
        /** 保存成功 */
        data class Saved(val templateId: String) : CaptureState()
        /** 错误 */
        data class Error(val message: String) : CaptureState()
    }

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    /** 是否为重拍模式 */
    val isRecapture: Boolean = templateId != null

    /** 已有模板（重拍时加载） */
    private var existingTemplate: InspectionTemplateEntity? = null

    init {
        if (templateId != null) {
            viewModelScope.launch {
                existingTemplate = repository.getTemplate(templateId)
            }
        }
    }

    /**
     * 执行拍摄
     *
     * 1. CameraController.takePhoto() 拍到临时文件
     * 2. MobileImageStore.storeTemplateImage() 校验并原子存储
     * 3. 写入 DB（新增或更新）
     * 4. 成功后删除旧图（仅重拍模式）
     *
     * 失败时清理临时文件，不产生 DB 脏记录。
     */
    fun onCapture(sessionId: String, cameraController: CameraController) {
        val current = _state.value
        if (current is CaptureState.Capturing) return

        // 先锁定状态再启动协程，避免连续点击同时新增两个 View。
        _state.value = CaptureState.Capturing
        viewModelScope.launch {
            val tempFile = repository.generateTempFile()
            val captureResult = cameraController.takePhoto(sessionId, tempFile)

            captureResult.fold(
                onSuccess = { file ->
                    val stored = repository.storeTemplateImage(file)
                    if (stored != null) {
                        val saveResult = saveToDatabase(stored)
                        if (saveResult) {
                            _state.value = CaptureState.Saved(templateId ?: "new")
                        } else {
                            // DB 写入失败，清理已存储的图片
                            repository.deleteTemplateImage(stored.finalPath)
                            _state.value = CaptureState.Error("数据库写入失败")
                        }
                    } else {
                        repository.deleteTempFile(file)
                        _state.value = CaptureState.Error("图片校验失败")
                    }
                },
                onFailure = { error ->
                    repository.deleteTempFile(tempFile)
                    _state.value = CaptureState.Error("拍照失败: ${error.message ?: "未知错误"}")
                }
            )
        }
    }

    /**
     * 保存到数据库
     *
     * 重拍：更新已有 View 的 mainImagePath，成功后删除旧图。
     * 新增：创建新 View，displayOrder = 现有数量。
     *
     * @return true 保存成功
     */
    private suspend fun saveToDatabase(stored: StoredImageResult): Boolean {
        return try {
            if (templateId != null) {
                // 重拍模式：替换已有 View 的图片
                val old = existingTemplate ?: repository.getTemplate(templateId)
                if (old == null) {
                    // 原 View 不存在，降级为新增
                    return insertNewView(stored.finalPath)
                }

                val oldPath = old.mainImagePath
                val now = System.currentTimeMillis()
                repository.updateTemplate(
                    old.copy(
                        mainImagePath = stored.finalPath,
                        updatedAt = now,
                    )
                )
                // 新图 DB 写入成功后删除旧图（旧图路径与新图不同才删除）
                if (oldPath != stored.finalPath && oldPath.isNotBlank()) {
                    repository.deleteTemplateImage(oldPath)
                }
                existingTemplate = old.copy(mainImagePath = stored.finalPath, updatedAt = now)
                true
            } else {
                // 新增模式
                insertNewView(stored.finalPath)
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 新增 View 到数据库
     */
    private suspend fun insertNewView(imagePath: String): Boolean {
        return try {
            val existing = repository.getTemplatesByPart(partId)
            val newOrder = existing.size
            val newId = "${partId}_capture_${UUID.randomUUID()}"
            val now = System.currentTimeMillis()
            repository.insertTemplate(
                InspectionTemplateEntity(
                    id = newId,
                    partId = partId,
                    name = "视角 ${newOrder + 1}",
                    mainImagePath = imagePath,
                    displayOrder = newOrder,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 重置状态为 IDLE（允许重试）
     */
    fun resetState() {
        _state.value = CaptureState.Idle
    }

    companion object {
        /**
         * 创建 Factory
         */
        fun factory(
            repository: InspectionRepository,
            partId: String,
            templateId: String?,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return TemplateCaptureViewModel(repository, partId, templateId) as T
            }
        }
    }
}
