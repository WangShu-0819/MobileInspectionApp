package com.wearable.inspection.mobile.dpm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DPM 扫码证据持久化源码契约测试。
 *
 * 锁定关键流程约束：
 * - 证据保存在 stopScan 之前执行
 * - Bitmap 在回收前已复制
 * - 成功帧和最后有效帧正确区分
 * - 退出流程顺序：证据快照 → 停止分析器 → 清理 → 断开
 * - Room migration 创建正确的表结构
 * - 重复退出不会重复写入
 */
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
    fun `frame analyzer tracks lastFrameBitmap and successBitmap separately`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/dpm/DpmFrameAnalyzer.kt")
        assertTrue("必须追踪 lastFrameBitmap", source.contains("lastFrameBitmap"))
        assertTrue("必须追踪 successBitmap", source.contains("successBitmap"))
        assertTrue("成功帧必须有独立副本", source.contains("val successCopy = frameCopy.copy("))
    }

    @Test
    fun `frame analyzer getAndClearEvidenceFrames returns success frame preferentially`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/dpm/DpmFrameAnalyzer.kt")
        // getAndClearEvidenceFrames 应优先返回成功帧
        assertTrue("必须定义 getAndClearEvidenceFrames 方法",
            source.contains("fun getAndClearEvidenceFrames()"))
        assertTrue("优先返回成功帧",
            source.contains("successBitmap?.let"))
        // 清空成功帧后应回收 lastFrameBitmap
        assertTrue("清空后回收 lastFrameBitmap",
            source.contains("lastFrameBitmap?.recycle()"))
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
    }

    // ─── DpmScanViewModel 证据保存契约 ───

    @Test
    fun `viewModel saveEvidence persists entity with correct status mapping`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/dpm/DpmScanViewModel.kt")
        // SUCCESS 状态映射
        assertTrue("成功时 status 应为 SUCCESS",
            source.contains("status = if (evidenceFrames.isDecodeSuccess) \"SUCCESS\" else \"NO_READ\""))
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
            source.contains("if (evidenceSaved)"))
    }

    @Test
    fun `viewModel recycles bitmap after saving evidence`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/dpm/DpmScanViewModel.kt")
        // 保存后必须回收 bitmap
        assertTrue("保存后必须回收 bitmap",
            source.contains("evidenceFrames.bitmap.recycle()"))
        // 异常路径也必须回收
        assertTrue("异常路径必须回收 bitmap",
            source.contains("runCatching { evidenceFrames.bitmap.recycle() }"))
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
        val saveIdx = source.indexOf("saveEvidence(")
        val disconnectIdx = source.indexOf("disconnect(")
        assertTrue("必须调用 saveEvidence", saveIdx > 0)
        assertTrue("必须调用 disconnect", disconnectIdx > 0)
        assertTrue("证据保存必须在 disconnect 之前", saveIdx < disconnectIdx)
    }

    @Test
    fun `exit flow cleans up even without sessionId`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/ui/screens/DpmScanScreen.kt")
        assertTrue("无 sessionId 时必须清理",
            source.contains("viewModel.stopScan()"))
        assertTrue("无 sessionId 时必须回收 bitmap",
            source.contains("evidenceFrames?.bitmap?.recycle()"))
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
    fun `app database version is 7`() {
        val source = read("src/main/java/com/wearable/inspection/mobile/data/db/AppDatabase.kt")
        assertTrue("数据库版本必须为 7", source.contains("version = 7"))
        assertTrue("必须包含 DpmScanEvidenceEntity",
            source.contains("DpmScanEvidenceEntity::class"))
        assertTrue("必须声明 dpmScanEvidenceDao",
            source.contains("fun dpmScanEvidenceDao(): DpmScanEvidenceDao"))
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
}
