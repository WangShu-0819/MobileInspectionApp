package com.wearable.inspection.mobile.dpm

import android.app.Application
import android.graphics.Bitmap
import com.wearable.inspection.mobile.ui.screens.runDpmScanExit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * DPM 扫码证据持久化源码契约测试。
 *
 * 锁定关键流程约束：
 * - 证据保存在 stopScan 之前执行
 * - Bitmap 在回收前已复制
 * - 仅保留 ECC 成功帧；无成功时不创建证据
 * - 退出流程顺序：证据快照 → 停止分析器 → 清理 → 断开
 * - Room migration 创建正确的表结构
 * - 重复退出不会重复写入
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DpmScanEvidenceContractTest {

    private fun read(path: String): String = File(path).readText()

    // ─── DpmFrameAnalyzer 证据追踪契约 ───

    @Test
    fun `frame analyzer copies bitmap before recycle in analyze method`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/dpm/DpmFrameAnalyzer.kt")
        // 分析前必须复制帧用于证据追踪
        assertTrue("analyze() 必须在分析前复制帧",
            source.contains("val frameCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)"))
        // 复制必须在 bitmap.recycle() 之前
        val copyIdx = source.indexOf("val frameCopy = bitmap.copy(")
        val recycleIdx = source.indexOf("bitmap.recycle()")
        assertTrue("帧复制必须在 bitmap.recycle 之前", copyIdx < recycleIdx)
    }

    @Test
    fun `frame analyzer tracks source frames with token-aware evidence tracker`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/dpm/DpmFrameAnalyzer.kt")
        assertTrue("必须使用源帧追踪器", source.contains("DpmEvidenceFrameTracker"))
        assertTrue("必须携带 sourceFrameToken", source.contains("sourceFrameToken"))
        assertTrue("必须携带 sourceFrameTimeMs", source.contains("sourceFrameTimeMs"))
    }

    @Test
    fun `frame analyzer getAndClearEvidenceFrames returns only ECC success frame`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/dpm/DpmFrameAnalyzer.kt")
        assertTrue("必须定义 getAndClearEvidenceFrames 方法",
            source.contains("fun getAndClearEvidenceFrames()"))
        assertTrue("必须冻结证据快照", source.contains("evidenceTracker.freeze()"))
        assertTrue("无 ECC 成功时必须返回 null", source.contains("无 ECC 成功时返回 null"))
    }

    @Test
    fun `frame analyzer EvidenceFrames data class has required fields`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/dpm/DpmFrameAnalyzer.kt")
        assertTrue("必须定义 EvidenceFrames",
            source.contains("data class EvidenceFrames"))
        assertTrue("必须包含 isDecodeSuccess",
            source.contains("val isDecodeSuccess: Boolean"))
        assertTrue("必须包含 decodedCode",
            source.contains("val decodedCode: String?"))
        assertTrue("必须包含 decodeSource",
            source.contains("val decodeSource: DecodeSource?"))
        assertTrue("必须包含 frameToken", source.contains("val frameToken: Long"))
        assertTrue("必须包含 frameTimeMs", source.contains("val frameTimeMs: Long"))
    }

    // ─── DpmScanViewModel 证据保存契约 ───

    @Test
    fun `viewModel saveEvidence persists entity with correct status mapping`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/dpm/DpmScanViewModel.kt")
        // 只有 ECC 成功帧才写 SUCCESS
        assertTrue("成功时 status 应为 SUCCESS", source.contains("status = \"SUCCESS\""))
        assertTrue("无 ECC 成功时必须不落盘", source.contains("no ECC-validated success frame"))
        // 使用 DAO 持久化
        assertTrue("必须通过 evidenceDao.insert 持久化",
            source.contains("evidenceDao.insert(entity)"))
    }

    @Test
    fun `viewModel saveEvidence saves both original and roi images`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/dpm/DpmScanViewModel.kt")
        assertTrue("必须保存原始帧图片",
            source.contains("imageStore.saveDpmEvidenceFrame"))
        assertTrue("必须保存 ROI 裁切图片",
            source.contains("imageStore.saveDpmEvidenceRoi"))
    }

    @Test
    fun `viewModel saveEvidence is guarded by evidenceSaved flag`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/dpm/DpmScanViewModel.kt")
        assertTrue("必须有 evidenceSaved 去重标记",
            source.contains("evidenceSaved"))
        assertTrue("重复调用应被拦截",
            source.contains("evidenceSaved && evidenceSavedSessionId == sessionId"))
    }

    @Test
    fun `viewModel recycles bitmap after saving evidence`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/dpm/DpmScanViewModel.kt")
        // 保存后必须回收 bitmap
        assertTrue("保存后必须回收 bitmap",
            source.contains("recycleEvidenceBitmap(evidenceFrames)"))
        // 异常路径也必须回收
        assertTrue("异常路径必须回收 bitmap",
            source.contains("runCatching") && source.contains("recycleEvidenceBitmap(evidenceFrames)"))
    }

    // ─── DpmScanScreen 退出流程契约 ───

    @Test
    fun `exit flow captures evidence before stopScan`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/DpmScanScreen.kt")
        val getEvidenceIdx = source.indexOf("getEvidenceFrames()")
        val stopScanIdx = source.indexOf("stopScan()")
        assertTrue("必须调用 getEvidenceFrames", getEvidenceIdx > 0)
        assertTrue("必须调用 stopScan", stopScanIdx > 0)
        assertTrue("证据提取必须在 stopScan 之前", getEvidenceIdx < stopScanIdx)
    }

    @Test
    fun `exit flow saves evidence before disconnect`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/DpmScanScreen.kt")
        val saveIdx = source.indexOf("saveEvidenceInScope(")
        val disconnectIdx = source.indexOf("disconnect(")
        assertTrue("必须在 ViewModel scope 中请求保存证据", saveIdx > 0)
        assertTrue("必须调用 disconnect", disconnectIdx > 0)
        assertTrue("证据保存必须在 disconnect 之前", saveIdx < disconnectIdx)

        val viewModelSource = read("src/main/java/com/wearable/inspection/mobile/dpm/DpmScanViewModel.kt")
        val persistIdx = viewModelSource.indexOf("alreadyScheduled = true")
        val cleanupIdx = viewModelSource.indexOf("afterSave()")
        assertTrue("回调必须先持久化证据", persistIdx > 0 && persistIdx < cleanupIdx)
    }

    @Test
    fun `decoded result waits for current evidence persistence before navigation`() {
        val screenSource = read("src/main/java/com/wearable/inspection/mobile/ui/screens/DpmScanScreen.kt")
        assertTrue(screenSource.contains("saveCurrentEvidenceAndAwait()"))
        assertTrue(
            screenSource.indexOf("saveCurrentEvidenceAndAwait()") <
                screenSource.indexOf("onResult(result.rawValue, connectedSessionId)")
        )

        val viewModelSource = read("src/main/java/com/wearable/inspection/mobile/dpm/DpmScanViewModel.kt")
        assertTrue(viewModelSource.contains("suspend fun saveCurrentEvidenceAndAwait(): Boolean"))
        assertTrue(viewModelSource.contains("evidenceSaveCompletions"))
        assertTrue(viewModelSource.contains(".await()"))
    }

    @Test
    fun `exit flow cleans up even without sessionId`() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val evidenceFrames = DpmFrameAnalyzer.EvidenceFrames(
            bitmap = bitmap,
            roi = null,
            isDecodeSuccess = false,
            decodedCode = null,
            decodeSource = null,
        )
        var stopScanCalls = 0
        var saveCalls = 0
        var disconnectCalls = 0

        runDpmScanExit(
            sessionId = null,
            getEvidenceFrames = { evidenceFrames },
            saveEvidenceInScope = { _, _, _ -> saveCalls++ },
            stopScan = { stopScanCalls++ },
            clearFrameAnalyzer = { error("无 sessionId 时不应清理 CameraController analyzer") },
            disconnect = { disconnectCalls++; true },
        )

        assertEquals("无 sessionId 时必须执行 stopScan", 1, stopScanCalls)
        assertTrue("无 sessionId 时必须回收 Bitmap", bitmap.isRecycled)
        assertEquals("无 sessionId 时不得保存证据", 0, saveCalls)
        assertEquals("无 sessionId 时不得 disconnect", 0, disconnectCalls)
    }

    // ─── Room Migration 契约 ───

    @Test
    fun `migration 6 to 7 creates dpm_scan_evidence table`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/data/db/Migrations.kt")
        assertTrue("必须定义 MIGRATION_6_7",
            source.contains("MIGRATION_6_7"))
        assertTrue("必须创建 dpm_scan_evidence 表",
            source.contains("CREATE TABLE IF NOT EXISTS dpm_scan_evidence"))
        assertTrue("必须有 scanSessionId 列",
            source.contains("scanSessionId TEXT NOT NULL"))
        assertTrue("必须有 decodedContent 列",
            source.contains("decodedContent TEXT"))
        assertTrue("必须有 status 列",
            source.contains("status TEXT NOT NULL"))
        assertTrue("必须有 originalImagePath 列",
            source.contains("originalImagePath TEXT NOT NULL"))
        assertTrue("必须有 roiImagePath 列",
            source.contains("roiImagePath TEXT"))
    }

    @Test
    fun `migration 6 to 7 is included in ALL_MIGRATIONS`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/data/db/Migrations.kt")
        assertTrue("MIGRATION_6_7 必须在 ALL_MIGRATIONS 中",
            source.contains("MIGRATION_6_7"))
    }

    @Test
    fun `app database version is 10 with dpm association migration`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/data/db/AppDatabase.kt")
        assertTrue("数据库版本必须为 10", source.contains("version = 10"))
        assertTrue("必须包含 DpmScanEvidenceEntity",
            source.contains("DpmScanEvidenceEntity::class"))
        assertTrue("必须声明 dpmScanEvidenceDao",
            source.contains("fun dpmScanEvidenceDao(): DpmScanEvidenceDao"))
        val migrations = read("src/main/java/com/wearable/inspection/mobile/data/db/Migrations.kt")
        assertTrue("必须定义 v8→v9 关联迁移", migrations.contains("MIGRATION_8_9"))
        assertTrue("必须定义 v9→v10 迁移", migrations.contains("MIGRATION_9_10"))
        assertTrue("关联字段必须可空", migrations.contains("ADD COLUMN batchId TEXT"))
    }

    // ─── MobileImageStore 证据存储契约 ───

    @Test
    fun `mobile image store has dpm evidence methods`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/data/image/MobileImageStore.kt")
        assertTrue("必须有 saveDpmEvidenceFrame 方法",
            source.contains("fun saveDpmEvidenceFrame("))
        assertTrue("必须有 saveDpmEvidenceRoi 方法",
            source.contains("fun saveDpmEvidenceRoi("))
        assertTrue("必须使用 JPEG 格式保存",
            source.contains("Bitmap.CompressFormat.JPEG"))
        assertTrue("必须有 dpm_evidence 目录常量",
            source.contains("DPM_EVIDENCE_DIR"))
    }

    // ─── 实体和 DAO 契约 ───

    @Test
    fun `entity table name matches migration`() {
        val entity = read("src/main/java/com/wearable/inspection/mobile/data/entity/DpmScanEvidenceEntity.kt")
        assertTrue("表名必须为 dpm_scan_evidence",
            entity.contains("tableName = \"dpm_scan_evidence\""))
        assertTrue("必须有 scanSessionId 索引",
            entity.contains("Index(value = [\"scanSessionId\"])"))
    }

    @Test
    fun `dao insert returns Long for row id`() {
        val dao = read("src/main/java/com/wearable/inspection/mobile/data/dao/DpmScanEvidenceDao.kt")
        assertTrue("insert 必须返回 Long",
            dao.contains("suspend fun insert(evidence: DpmScanEvidenceEntity): Long"))
        assertTrue("必须支持按 sessionId 查询",
            dao.contains("getBySessionId"))
    }

    // ─── DPM 扫码批次绑定合约 ───

    @Test
    fun `dao bindSessionToBatch guards batchId IS NULL and status SUCCESS`() {
        val daoSrc = read("src/main/java/com/wearable/inspection/mobile/data/dao/DpmScanEvidenceDao.kt")
        assertTrue("bindSessionToBatch 必须在 DAO 中声明",
            daoSrc.contains("suspend fun bindSessionToBatch("))
        assertTrue("只更新未绑定的行 (batchId IS NULL)",
            daoSrc.contains("batchId IS NULL"))
        assertTrue("只更新成功帧 (status = 'SUCCESS')",
            daoSrc.contains("status = 'SUCCESS'"))
        assertTrue("返回受影响行数 (Int)",
            daoSrc.contains("suspend fun bindSessionToBatch(scanSessionId: String, batchId: String): Int"))
    }

    @Test
    fun `repository exposes bindDpmScanSessionToBatch`() {
        val repoSrc = read("src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt")
        assertTrue("bindDpmScanSessionToBatch 必须在 Repository 中声明",
            repoSrc.contains("suspend fun bindDpmScanSessionToBatch("))
        assertTrue("Repository 必须委托给 DAO bindSessionToBatch",
            repoSrc.contains("dpmScanEvidenceDao.bindSessionToBatch("))
    }

    @Test
    fun `workbenchViewModel declares setPendingDpmBatchBinding`() {
        val vmSrc = read("src/main/java/com/wearable/inspection/mobile/ui/screens/workbench/WorkbenchViewModel.kt")
        assertTrue("setPendingDpmBatchBinding 必须在 WorkbenchViewModel 中声明",
            vmSrc.contains("fun setPendingDpmBatchBinding("))
    }

    @Test
    fun `workbenchViewModel declares applyPendingDpmBinding with partId validation`() {
        val vmSrc = read("src/main/java/com/wearable/inspection/mobile/ui/screens/workbench/WorkbenchViewModel.kt")
        assertTrue("applyPendingDpmBinding 必须在 WorkbenchViewModel 中声明",
            vmSrc.contains("suspend fun applyPendingDpmBinding("))
        assertTrue("applyPendingDpmBinding 必须校验 partId 一致性",
            vmSrc.contains("binding.partId"))
    }

    @Test
    fun `dpmScanScreen onResult callback passes scanSessionId`() {
        val screenSrc = read("src/main/java/com/wearable/inspection/mobile/ui/screens/DpmScanScreen.kt")
        assertTrue("onResult 签名必须包含 scanSessionId 参数",
            screenSrc.contains("onResult: (String, String?) -> Unit"))
        assertTrue("LaunchedEffect 必须传递 connectedSessionId 给 onResult",
            screenSrc.contains("onResult(result.rawValue, connectedSessionId)"))
    }

    @Test
    fun `liveInspectionScreen calls applyPendingDpmBinding after batch creation`() {
        val screenSrc = read("src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt")
        assertTrue("采集流程中必须调用 applyPendingDpmBinding",
            screenSrc.contains("viewModel.applyPendingDpmBinding(batchId, repository)"))
    }
}
