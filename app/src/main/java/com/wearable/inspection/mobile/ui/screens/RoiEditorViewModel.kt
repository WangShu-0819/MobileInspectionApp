package com.wearable.inspection.mobile.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.data.repository.InspectionRepository
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ROI 编辑器 ViewModel
 *
 * 管理指定模板的所有 ROI 定义，支持添加、删除、更新。
 * 坐标使用 normalizedRect（0-1 范围）。
 */
class RoiEditorViewModel(
    private val repository: InspectionRepository,
    private val templateId: String,
) : ViewModel() {

    /** 当前模板的所有 ROI */
    private val _rois = mutableStateListOf<RoiDefinitionEntity>()
    val rois: List<RoiDefinitionEntity> get() = _rois

    /** 正在绘制的新 ROI（normalizedRect） */
    var drawingRect by mutableStateOf<NormalizedRect?>(null)
        private set

    /** 选中的 ROI ID（用于高亮和删除） */
    var selectedRoiId by mutableStateOf<String?>(null)
        private set

    /** 删除错误信息，UI 消费后调用 clearDeleteError() 清除 */
    var deleteError by mutableStateOf<String?>(null)
        private set

    /** 是否处于绘制模式 */
    var isDrawingMode by mutableStateOf(false)
        private set

    init {
        loadRois()
    }

    private fun loadRois() {
        viewModelScope.launch {
            val list = repository.getRois(templateId)
            _rois.clear()
            _rois.addAll(list)
        }
    }

    /** 重新加载 ROI 列表（删除后刷新等场景） */
    fun refreshRois() {
        loadRois()
    }

    /** 切换绘制模式 */
    fun toggleDrawingMode() {
        isDrawingMode = !isDrawingMode
        if (!isDrawingMode) {
            drawingRect = null
        }
        selectedRoiId = null
    }

    /** 选中一个已有 ROI */
    fun selectRoi(roiId: String?) {
        selectedRoiId = roiId
    }

    /** 更新正在绘制的矩形 */
    fun updateDrawingRect(rect: NormalizedRect?) {
        drawingRect = rect
    }

    /** 保存正在绘制的矩形为新 ROI */
    fun saveDrawingRect(name: String = "ROI ${_rois.size + 1}") {
        val rect = drawingRect ?: return
        val roi = RoiDefinitionEntity(
            id = UUID.randomUUID().toString(),
            templateId = templateId,
            name = name,
            order = _rois.size,
            shapeType = "RECT",
            normalizedRect = rect.toJsonString(),
            inspectionType = "VISUAL",
        )
        viewModelScope.launch {
            repository.insertRoi(roi)
            _rois.add(roi)
            drawingRect = null
            isDrawingMode = false
        }
    }

    /** 取消当前绘制 */
    fun cancelDrawing() {
        drawingRect = null
        isDrawingMode = false
    }

    /** 清除删除错误 */
    fun clearDeleteError() {
        deleteError = null
    }

    /** 删除选中的 ROI；失败时保留 UI 状态并设置 deleteError */
    fun deleteSelectedRoi() {
        val id = selectedRoiId ?: return
        viewModelScope.launch {
            try {
                repository.deleteRoi(id)
                _rois.removeAll { it.id == id }
                selectedRoiId = null
                deleteError = null
            } catch (e: Exception) {
                deleteError = "删除失败：${e.message ?: "未知错误"}"
            }
        }
    }

    /** 删除指定 ROI */
    fun deleteRoi(roiId: String) {
        viewModelScope.launch {
            repository.deleteRoi(roiId)
            _rois.removeAll { it.id == roiId }
            if (selectedRoiId == roiId) selectedRoiId = null
        }
    }

    /** 移动选中 ROI（delta 为 normalized 偏移量） */
    fun moveRoi(roiId: String, deltaNormX: Float, deltaNormY: Float) {
        val index = _rois.indexOfFirst { it.id == roiId }
        if (index < 0) return
        val old = NormalizedRect.fromJsonString(_rois[index].normalizedRect) ?: return
        val clamped = old.move(deltaNormX, deltaNormY)
        viewModelScope.launch {
            val updated = _rois[index].copy(normalizedRect = clamped.toJsonString())
            repository.updateRoi(updated)
            _rois[index] = updated
        }
    }

    /**
     * 缩放选中 ROI
     *
     * @param cornerIndex 0=topLeft, 1=topRight, 2=bottomLeft, 3=bottomRight
     * @param newCornerNorm 新角点的 normalized 坐标
     */
    fun resizeRoi(roiId: String, cornerIndex: Int, newCornerNormX: Float, newCornerNormY: Float) {
        val index = _rois.indexOfFirst { it.id == roiId }
        if (index < 0) return
        val old = NormalizedRect.fromJsonString(_rois[index].normalizedRect) ?: return
        val clamped = old.resize(cornerIndex, newCornerNormX, newCornerNormY)
        viewModelScope.launch {
            val updated = _rois[index].copy(normalizedRect = clamped.toJsonString())
            repository.updateRoi(updated)
            _rois[index] = updated
        }
    }

    companion object {
        fun factory(repository: InspectionRepository, templateId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return RoiEditorViewModel(repository, templateId) as T
            }
        }
    }
}

/**
 * 归一化矩形（0-1 范围）
 *
 * left/top/right/bottom 均在 [0, 1] 区间内。
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    companion object {
        /** 最小 normalized 尺寸（约 2% 的图片宽/高） */
        private const val MIN_SIZE = 0.02f

        /** 从 JSON 字符串解析 */
        fun fromJsonString(json: String): NormalizedRect? = runCatching {
            val obj = org.json.JSONObject(json)
            NormalizedRect(
                left = obj.getDouble("left").toFloat(),
                top = obj.getDouble("top").toFloat(),
                right = obj.getDouble("right").toFloat(),
                bottom = obj.getDouble("bottom").toFloat(),
            )
        }.getOrNull()
    }

    /** 转换为 JSON 字符串，用于存储到 RoiDefinitionEntity.normalizedRect */
    fun toJsonString(): String {
        return org.json.JSONObject().apply {
            put("left", left.toDouble())
            put("top", top.toDouble())
            put("right", right.toDouble())
            put("bottom", bottom.toDouble())
        }.toString()
    }

    /**
     * 移动矩形并约束在 [0,1] 范围内
     *
     * @param deltaNormX 水平偏移量（normalized）
     * @param deltaNormY 垂直偏移量（normalized）
     * @return 约束后的新矩形
     */
    fun move(deltaNormX: Float, deltaNormY: Float): NormalizedRect {
        val w = right - left
        val h = bottom - top
        var newLeft = left + deltaNormX
        var newTop = top + deltaNormY
        // 约束在 [0,1] 内
        newLeft = newLeft.coerceIn(0f, 1f - w)
        newTop = newTop.coerceIn(0f, 1f - h)
        return NormalizedRect(
            left = newLeft,
            top = newTop,
            right = newLeft + w,
            bottom = newTop + h,
        )
    }

    /**
     * 缩放矩形（拖拽指定角点到新位置），保持对角点不变，约束并保证最小尺寸
     *
     * @param cornerIndex 0=topLeft, 1=topRight, 2=bottomLeft, 3=bottomRight
     * @param newCornerNormX 新角点 X（normalized）
     * @param newCornerNormY 新角点 Y（normalized）
     * @return 约束后的新矩形
     */
    fun resize(cornerIndex: Int, newCornerNormX: Float, newCornerNormY: Float): NormalizedRect {
        // 确定对角点（不变）和新角点（被拖拽）
        val (fixedX, fixedY) = when (cornerIndex) {
            0 -> right to bottom   // 左上拖拽 → 右下不变
            1 -> left to bottom    // 右上拖拽 → 左下不变
            2 -> right to top      // 左下拖拽 → 右上不变
            3 -> left to top       // 右下拖拽 → 左上不变
            else -> right to bottom
        }

        // 约束新角点到 [0,1]
        val clampedX = newCornerNormX.coerceIn(0f, 1f)
        val clampedY = newCornerNormY.coerceIn(0f, 1f)

        // 计算新矩形（确保 left<right, top<bottom）
        var newLeft = minOf(fixedX, clampedX)
        var newRight = maxOf(fixedX, clampedX)
        var newTop = minOf(fixedY, clampedY)
        var newBottom = maxOf(fixedY, clampedY)

        // 强制最小尺寸（从对角点方向扩展）
        if (newRight - newLeft < MIN_SIZE) {
            if (cornerIndex == 0 || cornerIndex == 2) {
                newLeft = newRight - MIN_SIZE
            } else {
                newRight = newLeft + MIN_SIZE
            }
        }
        if (newBottom - newTop < MIN_SIZE) {
            if (cornerIndex == 0 || cornerIndex == 1) {
                newTop = newBottom - MIN_SIZE
            } else {
                newBottom = newTop + MIN_SIZE
            }
        }

        return NormalizedRect(
            left = newLeft.coerceIn(0f, 1f),
            top = newTop.coerceIn(0f, 1f),
            right = newRight.coerceIn(0f, 1f),
            bottom = newBottom.coerceIn(0f, 1f),
        )
    }
}
