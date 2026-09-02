package com.wearable.inspection.mobile.ui.screens.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wearable.inspection.mobile.data.entity.InspectionTemplateEntity
import com.wearable.inspection.mobile.data.entity.PartEntity
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.data.repository.InspectionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class TodayStats(
    val templateCount: Int = 0,
    val roiCount: Int = 0,
    val passCount: Int = 0,
    val failCount: Int = 0
)

data class InspectionState(
    val part: PartEntity? = null,
    val templates: List<InspectionTemplateEntity> = emptyList(),
    val selectedTemplate: InspectionTemplateEntity? = null,
    val rois: List<RoiDefinitionEntity> = emptyList(),
    val isTemplateReady: Boolean = false,
    val stats: TodayStats = TodayStats(),
    val currentViewIndex: Int = 0,
    val totalViews: Int = 0,
    val allViewsCaptured: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchViewModel(
    private val repository: InspectionRepository,
    private val settings: com.wearable.inspection.mobile.data.settings.SettingsStore
) : ViewModel() {

    val parts: StateFlow<List<PartEntity>> = repository.observeParts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedPartId = MutableStateFlow<String?>(settings.selectedPartId)
    val selectedPart: StateFlow<PartEntity?> = _selectedPartId
        .filterNotNull()
        .distinctUntilChanged()
        .flatMapLatest { partId ->
            repository.observeParts()
                .map { list -> list.find { it.id == partId } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // 当前零件的所有模板
    val templates: StateFlow<List<InspectionTemplateEntity>> = _selectedPartId
        .filterNotNull()
        .distinctUntilChanged()
        .flatMapLatest { partId ->
            repository.observeTemplates(partId)
                .map { list -> list.filter { it.enabled } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 视角采集进度（声明在 selectedTemplate 之前，供 combine 引用）
    private val _currentViewIndex = MutableStateFlow(0)
    val currentViewIndex: StateFlow<Int> = _currentViewIndex.asStateFlow()

    private val _allViewsCaptured = MutableStateFlow(false)
    val allViewsCaptured: StateFlow<Boolean> = _allViewsCaptured.asStateFlow()

    // 选中的模板（由 viewIndex 或手动选择驱动）
    private val _selectedTemplateId = MutableStateFlow<String?>(null)
    val selectedTemplate: StateFlow<InspectionTemplateEntity?> = combine(
        templates,
        _selectedTemplateId,
        _currentViewIndex
    ) { templateList, selectedId, viewIndex ->
        if (selectedId != null) {
            templateList.find { it.id == selectedId }
        } else {
            templateList.getOrNull(viewIndex) ?: templateList.firstOrNull()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // 选中模板的 ROI 列表
    val rois: StateFlow<List<RoiDefinitionEntity>> = _selectedTemplateId
        .filterNotNull()
        .distinctUntilChanged()
        .flatMapLatest { templateId ->
            repository.observeRois(templateId)
                .map { list -> list.filter { it.enabled } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 检测就绪状态（有模板即可拍摄；ROI 和 outlineData 为可选增强）
    val isTemplateReady: StateFlow<Boolean> = selectedTemplate
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // 今日统计
    val todayStats: StateFlow<TodayStats> = combine(
        parts,
        selectedPart,
        templates,
        rois
    ) { partsList, part, templateList, roiList ->
        TodayStats(
            templateCount = templateList.size,
            roiCount = roiList.size,
            passCount = 0, // TODO: 统计今日通过数
            failCount = 0  // TODO: 统计今日不通过数
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TodayStats())

    // 相机就绪状态（CameraX 实际状态）
    private val _cameraReady = MutableStateFlow(false)
    val cameraReady: StateFlow<Boolean> = _cameraReady.asStateFlow()

    // TODO: 实际接入 CameraController 后更新此状态
    // 暂时返回 false（阶段 B1 完成后再接通）
    fun updateCameraReady(ready: Boolean) {
        _cameraReady.value = ready
    }

    // 综合状态
    val inspectionState: StateFlow<InspectionState> = combine(
        selectedPart,
        templates,
        selectedTemplate,
        rois,
        isTemplateReady,
        todayStats,
        _currentViewIndex,
        _allViewsCaptured
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val part = args[0] as PartEntity?
        @Suppress("UNCHECKED_CAST")
        val templateList = args[1] as List<InspectionTemplateEntity>
        @Suppress("UNCHECKED_CAST")
        val template = args[2] as InspectionTemplateEntity?
        @Suppress("UNCHECKED_CAST")
        val roiList = args[3] as List<RoiDefinitionEntity>
        @Suppress("UNCHECKED_CAST")
        val ready = args[4] as Boolean
        @Suppress("UNCHECKED_CAST")
        val stats = args[5] as TodayStats
        @Suppress("UNCHECKED_CAST")
        val viewIndex = args[6] as Int
        @Suppress("UNCHECKED_CAST")
        val captured = args[7] as Boolean

        InspectionState(
            part = part,
            templates = templateList,
            selectedTemplate = template,
            rois = roiList,
            isTemplateReady = ready,
            stats = stats,
            currentViewIndex = viewIndex,
            totalViews = templateList.size,
            allViewsCaptured = captured
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InspectionState())

    fun selectPart(partId: String) {
        settings.selectedPartId = partId
        _selectedPartId.value = partId
        // 切换零件时清空模板选择和视角进度
        _selectedTemplateId.value = null
        _currentViewIndex.value = 0
        _allViewsCaptured.value = false
    }

    fun selectTemplate(templateId: String) {
        _selectedTemplateId.value = templateId
    }

    /**
     * 拍照成功后推进到下一视角
     * @return true 如果还有下一视角，false 如果所有视角已完成
     */
    fun advanceToNextView(): Boolean {
        val templateList = templates.value
        val nextIndex = _currentViewIndex.value + 1
        return if (nextIndex < templateList.size) {
            _currentViewIndex.value = nextIndex
            _selectedTemplateId.value = null // 让 viewIndex 驱动选择
            _allViewsCaptured.value = false
            true
        } else {
            _allViewsCaptured.value = true
            false
        }
    }

    /**
     * 重置视角采集进度
     */
    fun resetViewIndex() {
        _currentViewIndex.value = 0
        _selectedTemplateId.value = null
        _allViewsCaptured.value = false
    }
}
