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
import com.wearable.inspection.mobile.data.settings.PartSelectionBus

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

/** 当前 View 完成后的推进结果。 */
enum class ViewCompletionResult {
    ADVANCED,
    COMPLETED,
    IGNORED,
}

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchViewModel(
    private val repository: InspectionRepository,
    private val settings: com.wearable.inspection.mobile.data.settings.SettingsStore
) : ViewModel() {

    val parts: StateFlow<List<PartEntity>> = repository.observeParts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedPartId = MutableStateFlow<String?>(settings.selectedPartId)

    init {
        // 持久化的零件可能已被删除或来自旧数据；启动时直接校验并回退。
        viewModelScope.launch {
            val availableParts = repository.getParts()
            val configuredPartIds = repository.getAllTemplates()
                .asSequence()
                .filter { it.enabled }
                .map { it.partId }
                .toSet()
            val selectedPart = availableParts.firstOrNull {
                it.id == settings.selectedPartId && it.id in configuredPartIds
            } ?: availableParts.firstOrNull { it.id in configuredPartIds }
                ?: availableParts.firstOrNull { it.id == settings.selectedPartId }
                ?: availableParts.firstOrNull()
            if (selectedPart != null && selectedPart.id != _selectedPartId.value) {
                _selectedPartId.value = selectedPart.id
                settings.selectedPartId = selectedPart.id
            }
        }

        // DPM 扫码页位于当前采集页之上，扫码返回后用事件立即切换已有 WorkbenchViewModel。
        viewModelScope.launch {
            PartSelectionBus.flow.collect { partId ->
                if (repository.getPartById(partId) != null) {
                    selectPart(partId)
                }
            }
        }
    }
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
            // 切换零件时先清空上一零件的模板，避免旧模板与新零件短暂混合。
            flow {
                emit(emptyList())
                emitAll(
                    repository.observeTemplates(partId)
                        .map { list ->
                            list.filter { it.enabled && it.partId == partId }
                        }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 视角采集进度（声明在 selectedTemplate 之前，供 combine 引用）
    private val _currentViewIndex = MutableStateFlow(0)
    val currentViewIndex: StateFlow<Int> = _currentViewIndex.asStateFlow()

    private val _allViewsCaptured = MutableStateFlow(false)
    val allViewsCaptured: StateFlow<Boolean> = _allViewsCaptured.asStateFlow()

    // 采集批次跨 LiveInspectionScreen 与确认页导航保存，避免页面重建后新建批次。
    private var activeCaptureBatchId: String? = null

    fun getActiveCaptureBatchId(): String? = activeCaptureBatchId

    fun setActiveCaptureBatchId(batchId: String) {
        activeCaptureBatchId = batchId
    }

    fun clearActiveCaptureBatch() {
        activeCaptureBatchId = null
    }

    // 选中的模板（由 viewIndex 或手动选择驱动）
    private val _selectedTemplateId = MutableStateFlow<String?>(null)
    val selectedTemplate: StateFlow<InspectionTemplateEntity?> = combine(
        _selectedPartId,
        templates,
        _selectedTemplateId,
        _currentViewIndex
    ) { partId, templateList, selectedId, viewIndex ->
        // 防止切换期间旧模板流晚到，模板必须属于当前零件才能进入 UI。
        if (partId == null || templateList.any { it.partId != partId }) {
            null
        } else if (selectedId != null) {
            templateList.find { it.id == selectedId && it.partId == partId }
        } else {
            templateList.getOrNull(viewIndex) ?: templateList.firstOrNull()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // 选中模板的 ROI 列表；默认按视角选中的模板也必须加载对应 ROI。
    val rois: StateFlow<List<RoiDefinitionEntity>> = selectedTemplate
        .flatMapLatest { template ->
            template?.let { repository.observeRois(it.id) } ?: flowOf(emptyList())
        }
        .map { list -> list.filter { it.enabled } }
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
        // 先清掉依赖模板的状态，再切换零件 ID，避免一次重组中拿到旧模板/旧 ROI。
        _selectedTemplateId.value = null
        _currentViewIndex.value = 0
        _allViewsCaptured.value = false
        settings.selectedPartId = partId
        _selectedPartId.value = partId
        clearActiveCaptureBatch()
    }

    fun selectTemplate(templateId: String) {
        val viewIndex = templates.value.indexOfFirst { it.id == templateId }
        if (viewIndex >= 0) {
            _currentViewIndex.value = viewIndex
            _allViewsCaptured.value = false
        }
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
     * 以拍摄时的 View 索引完成一次推进。
     *
     * 只有当前索引仍与拍摄索引一致时才允许推进，避免确认回调重复或过期时跳过 View。
     */
    fun completeView(viewIndex: Int): ViewCompletionResult {
        if (_allViewsCaptured.value || _currentViewIndex.value != viewIndex) {
            return ViewCompletionResult.IGNORED
        }
        return if (advanceToNextView()) {
            ViewCompletionResult.ADVANCED
        } else {
            clearActiveCaptureBatch()
            ViewCompletionResult.COMPLETED
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
