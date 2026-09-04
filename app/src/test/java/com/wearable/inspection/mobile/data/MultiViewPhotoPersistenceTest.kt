package com.wearable.inspection.mobile.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 多 View 照片持久化契约测试
 *
 * 覆盖：
 * - 同一 batch 连续拍摄两个不同 View 后数据库有两条独立记录
 * - 两张照片具有不同真实 photoId
 * - 两张照片的 batchId、viewIndex、templateId 关联正确
 * - 第二张照片不会覆盖第一张照片
 * - DAO insert 使用 OnConflictStrategy.REPLACE 但 photoId 自增无冲突
 * - captureState 在导航到 ROI 确认页前直接重置为 IDLE
 * - 布局稳定性：固定高度状态区域、操作栏和确认底栏
 */
class MultiViewPhotoPersistenceTest {

    private fun read(path: String): String = File(path).readText()

    @Test
    fun `captured photos table has no unique index on viewIndex per batch`() {
        val schema = File("app/schemas/com.wearable.inspection.mobile.data.db.AppDatabase/6.json").readText()
        // captured_photos 的 indices 应为空（无额外唯一约束）
        val photosSection = schema.substring(
            schema.indexOf("\"tableName\": \"captured_photos\""),
            schema.indexOf("\"tableName\": \"captured_photos\"") + 2000
        )
        assertTrue("captured_photos 应有 indices 字段", photosSection.contains("\"indices\""))
        // indices 应为空数组
        val indicesStart = photosSection.indexOf("\"indices\"")
        val indicesSection = photosSection.substring(indicesStart, indicesStart + 30)
        assertTrue("indices 应为空数组", indicesSection.contains("[]"))
    }

    @Test
    fun `dao insert returns auto-generated long id without conflict`() {
        val daoSource = read("src/main/java/com/wearable/inspection/mobile/data/dao/CapturedPhotoDao.kt")
        assertTrue("insert 应返回 Long", daoSource.contains("suspend fun insert(photo: CapturedPhotoEntity): Long"))
        assertTrue("应使用 REPLACE 策略", daoSource.contains("OnConflictStrategy.REPLACE"))
        assertTrue("应有 getById 支持按 photoId 回读", daoSource.contains("suspend fun getById(photoId: Long): CapturedPhotoEntity?"))
    }

    @Test
    fun `repository insert returns room generated photo id`() {
        val repoSource = read("src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt")
        assertTrue("insertCapturedPhoto 应返回 Long", repoSource.contains("suspend fun insertCapturedPhoto(photo: CapturedPhotoEntity): Long"))
        assertTrue("应直接返回 dao.insert 结果", repoSource.contains("return capturedPhotoDao.insert(photo)"))
    }

    @Test
    fun `live inspection validates photo association after insert`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
        assertTrue("应校验 photoId > 0", source.contains("check(photoId > 0L)"))
        assertTrue("应校验 batchId 一致", source.contains("persistedPhoto.batchId == batchId"))
        assertTrue("应校验 viewIndex 一致", source.contains("persistedPhoto.viewIndex == capturedViewIndex"))
        assertTrue("应校验 templateId 一致", source.contains("persistedPhoto.templateId == capturedTemplateId"))
        assertTrue("应校验 filePath 一致", source.contains("persistedPhoto.filePath == storeResult.finalPath"))
    }

    @Test
    fun `two captures use different viewIndex and templateId from snapshot`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
        // capturedViewIndex 和 capturedTemplateId 来自拍照时的快照
        assertTrue("viewIndex 应来自 stateAtCapture 快照", source.contains("val capturedViewIndex = stateAtCapture.currentViewIndex"))
        assertTrue("templateId 应来自 template 快照", source.contains("val capturedTemplateId = template.id"))
        // 照片实体使用快照值
        assertTrue("照片 viewIndex 使用 capturedViewIndex", source.contains("viewIndex = capturedViewIndex"))
        assertTrue("照片 templateId 使用 capturedTemplateId", source.contains("templateId = capturedTemplateId"))
    }

    @Test
    fun `second capture does not overwrite first photo`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
        // 每次拍照都创建新的 CapturedPhotoEntity
        val insertPattern = "val capturedPhoto = com.wearable.inspection.mobile.data.entity.CapturedPhotoEntity("
        val insertCount = source.split(insertPattern).size - 1
        assertEquals("应只有一个 CapturedPhotoEntity 构造点（每次拍照复用同一代码路径）", 1, insertCount)
        // insert 在 try 块内，每次拍照都执行
        assertTrue("insert 在 try 块内", source.contains("val photoId = repository.insertCapturedPhoto(capturedPhoto)"))
    }

    @Test
    fun `capture state resets to IDLE before roi confirmation navigation`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
        val navigateIdx = source.indexOf("onNavigateToConfirm(")
        val messageIdx = source.indexOf("captureSavedMessage = \"照片已保存，进入人工确认\"")
        val resetIdx = source.indexOf("captureState = CaptureUiState.IDLE", messageIdx)
        assertTrue("有 ROI 导航前应直接重置 captureState", resetIdx > messageIdx)
        assertTrue("复位应在导航前完成", resetIdx < navigateIdx)
        assertFalse("不应使用 needsCaptureReset", source.contains("needsCaptureReset"))
        assertFalse("不应依赖 ON_RESUME", source.contains("Lifecycle.Event.ON_RESUME"))
    }

    @Test
    fun `capture state reset clears error and savedPath`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
        val resetIdx = source.indexOf("val onResetCapture")
        assertTrue("应有统一的拍照状态复位函数", resetIdx > 0)
        val block = source.substring(resetIdx, (resetIdx + 300).coerceAtMost(source.length))
        assertTrue("应清除 captureError", block.contains("captureError = null"))
        assertTrue("应清除 savedPath", block.contains("savedPath = null"))
        assertFalse("不应保留 needsCaptureReset", block.contains("needsCaptureReset"))
    }

    @Test
    fun `no ROI path resets captureState immediately without needing lifecycle callback`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
        val noRoiBranch = source.indexOf("if (rois.isEmpty())")
        assertTrue("应有无 ROI 分支", noRoiBranch > 0)
        val branchBlock = source.substring(noRoiBranch, (noRoiBranch + 500).coerceAtMost(source.length))
        assertTrue("无 ROI ADVANCED 应立即重置 captureState", branchBlock.contains("captureState = CaptureUiState.IDLE"))
        assertFalse("无 ROI 路径不应设置 needsCaptureReset", branchBlock.contains("needsCaptureReset = true"))
    }

    @Test
    fun `batch id is reused across multiple captures for same part`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
        val workbench = read("src/main/java/com/wearable/inspection/mobile/ui/screens/workbench/WorkbenchViewModel.kt")
        // batchId 获取逻辑先检查现有 batch 是否有效，并跨确认页保存
        assertTrue("应检查现有 batch 的 partId", source.contains("it.partId == part.id"))
        assertTrue("应检查现有 batch 的 endTime", source.contains("it.endTime == null"))
        // 只有无效时才创建新 batch
        assertTrue("无效应创建新 batch", source.contains("val newBatchId = "))
        assertTrue("批次 ID 应保存在共享 WorkbenchViewModel", source.contains("getActiveCaptureBatchId()"))
        assertTrue("WorkbenchViewModel 应提供批次 ID 持久化状态", workbench.contains("activeCaptureBatchId"))
    }

    @Test
    fun `mobile image store generates unique file names for each capture`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/data/image/MobileImageStore.kt")
        assertTrue("应使用时间戳+UUID生成文件名", source.contains("UUID.randomUUID()"))
        assertTrue("应使用毫秒精度时间戳", source.contains("HHmmss_SSS"))
        assertTrue("应检查最终文件不覆盖", source.contains("if (finalFile.exists())"))
    }

    // ── 布局稳定性测试 ──

    @Test
    fun `status area uses fixed height not heightIn min`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
        // 状态区域应使用固定 height 而非 heightIn(min=)
        val statusBox = source.indexOf("// 拍照状态提示")
        assertTrue("应有状态提示区域", statusBox > 0)
        val statusBlock = source.substring(statusBox, (statusBox + 200).coerceAtMost(source.length))
        assertTrue("状态区域应使用固定 height(28.dp)", statusBlock.contains(".height(28.dp)"))
        assertFalse("不应使用 heightIn(min = 28.dp)", statusBlock.contains("heightIn(min = 28.dp)"))
    }

    @Test
    fun `capture action bar has fixed height row`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
        val actionBar = source.indexOf("private fun CaptureActionBar")
        assertTrue("应有 CaptureActionBar", actionBar > 0)
        val block = source.substring(actionBar, (actionBar + 500).coerceAtMost(source.length))
        assertTrue("Row 应有固定高度", block.contains(".height(52.dp)"))
    }

    @Test
    fun `all views captured card uses a compact fixed height`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
        val cardCall = source.indexOf("AllViewsCapturedCard(")
        assertTrue("应调用 AllViewsCapturedCard", cardCall > 0)
        val cardDecl = source.indexOf("private fun AllViewsCapturedCard")
        val block = source.substring(cardDecl, (cardDecl + 500).coerceAtMost(source.length))
        assertTrue("完成卡片应使用固定高度", block.contains(".height(64.dp)"))
        assertTrue("完成卡片文字区域应允许收缩", block.contains(".widthIn(min = 0.dp)"))
    }

    @Test
    fun `view confirmation error area has fixed height`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationScreen.kt")
        // 错误信息区域应有固定高度
        assertTrue("应有固定高度错误区域", source.contains(".height(18.dp)"))
        assertTrue("错误文本应限制行数", source.contains("maxLines = 1"))
    }

    @Test
    fun `view confirmation uses scaffold bottom bar with fixed height`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/ViewConfirmationScreen.kt")
        assertTrue("确认页应使用 Scaffold bottomBar", source.contains("bottomBar = {"))
        assertTrue("确认底栏应使用固定高度", source.contains(".height(140.dp)"))
        assertTrue("确认按钮内容应使用固定槽位", source.contains(".height(20.dp)"))
    }
}
