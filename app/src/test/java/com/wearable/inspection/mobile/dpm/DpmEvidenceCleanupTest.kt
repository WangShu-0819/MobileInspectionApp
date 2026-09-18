package com.wearable.inspection.mobile.dpm

import com.wearable.inspection.mobile.data.dao.DpmEvidencePathProjection
import com.wearable.inspection.mobile.data.repository.DpmCleanupResult
import com.wearable.inspection.mobile.data.repository.DpmEvidenceStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DPM 原始证据清理功能的 JVM 契约测试。
 *
 * 覆盖范围：
 * - DpmEvidenceStats 数据类语义（含新字段 outOfBoundsFiles、sharedPathCount）
 * - DpmCleanupResult 数据类语义
 * - DpmEvidencePathProjection 数据类语义
 * - DpmOperationGuard 单例门禁语义
 * - 源码契约：DAO 新增方法存在性
 * - 源码契约：Repository 清理流程关键节点
 * - 源码契约：MobileImageStore 路径安全和孤立文件检测
 * - 源码契约：TraceRecordsScreen 清理 UI 入口
 */
class DpmEvidenceCleanupTest {

    // ─── DpmEvidenceStats ───

    @Test
    fun `stats hasData is true when rowCount greater than zero`() {
        val stats = DpmEvidenceStats(rowCount = 5, fileCount = 5, totalBytes = 1024)
        assertTrue("有数据时 hasData 应为 true", stats.hasData)
    }

    @Test
    fun `stats hasData is true when orphanFiles greater than zero`() {
        val stats = DpmEvidenceStats(rowCount = 0, fileCount = 0, totalBytes = 0, orphanFiles = 2)
        assertTrue("有孤立文件时 hasData 应为 true", stats.hasData)
    }

    @Test
    fun `stats hasData is false when both zero`() {
        val stats = DpmEvidenceStats(rowCount = 0, fileCount = 0, totalBytes = 0)
        assertFalse("无数据时 hasData 应为 false", stats.hasData)
    }

    @Test
    fun `stats displayTotalBytes includes orphanBytes`() {
        val stats = DpmEvidenceStats(
            rowCount = 3, fileCount = 3, totalBytes = 1000,
            orphanFiles = 1, orphanBytes = 500
        )
        assertEquals("displayTotalBytes 应包含孤立字节", 1500L, stats.displayTotalBytes)
    }

    @Test
    fun `stats defaults missing and orphan to zero`() {
        val stats = DpmEvidenceStats(rowCount = 1, fileCount = 1, totalBytes = 100)
        assertEquals(0, stats.missingFiles)
        assertEquals(0, stats.orphanFiles)
        assertEquals(0L, stats.orphanBytes)
    }

    @Test
    fun `stats includes outOfBoundsFiles and sharedPathCount`() {
        val stats = DpmEvidenceStats(
            rowCount = 2, fileCount = 2, totalBytes = 100,
            outOfBoundsFiles = 1, sharedPathCount = 1
        )
        assertEquals(1, stats.outOfBoundsFiles)
        assertEquals(1, stats.sharedPathCount)
    }

    // ─── DpmCleanupResult ───

    @Test
    fun `cleanup result success has no error`() {
        val result = DpmCleanupResult(
            success = true, deletedDbRows = 10, deletedFiles = 15,
            missingFiles = 2, failedFiles = 0, releasedBytes = 5000
        )
        assertTrue(result.success)
        assertNull(result.error)
        assertNull(result.failedPaths)
    }

    @Test
    fun `cleanup result partial failure reports error`() {
        val result = DpmCleanupResult(
            success = false, deletedDbRows = 5, deletedFiles = 8,
            failedFiles = 2, failedPaths = listOf("path1", "path2"),
            error = "部分文件删除失败"
        )
        assertFalse(result.success)
        assertEquals(2, result.failedFiles)
        assertEquals(2, result.failedPaths!!.size)
    }

    @Test
    fun `cleanup result blocked by gate has zero counts`() {
        val result = DpmCleanupResult(success = false, error = "有其他 DPM 操作正在进行中")
        assertEquals(0, result.deletedDbRows)
        assertEquals(0, result.deletedFiles)
        assertEquals(0L, result.releasedBytes)
    }

    // ─── DpmEvidencePathProjection ───

    @Test
    fun `path projection holds id and paths`() {
        val proj = DpmEvidencePathProjection(
            id = 42,
            originalImagePath = "/data/data/pkg/files/dpm_evidence/frame.jpg",
            roiImagePath = "/data/data/pkg/files/dpm_evidence/roi.jpg"
        )
        assertEquals(42L, proj.id)
        assertTrue(proj.originalImagePath.endsWith("frame.jpg"))
        assertTrue(proj.roiImagePath!!.endsWith("roi.jpg"))
    }

    @Test
    fun `path projection roiPath can be null`() {
        val proj = DpmEvidencePathProjection(
            id = 1,
            originalImagePath = "/data/data/pkg/files/dpm_evidence/frame.jpg",
            roiImagePath = null
        )
        assertNull(proj.roiImagePath)
    }

    // ─── DpmOperationGuard 门禁语义 ───

    @Test
    fun `DpmOperationGuard is singleton object`() {
        // 确认是 object 而非 class
        val clazz = DpmOperationGuard::class.java
        assertTrue("DpmOperationGuard 必须是 object 单例",
            java.lang.reflect.Modifier.isStatic(clazz.modifiers) ||
            clazz.declaredFields.any { it.name == "INSTANCE" })
    }

    @Test
    fun `DpmOperationGuard has activeOperations counter`() {
        val field = DpmOperationGuard::class.java.getDeclaredField("activeOperations")
        assertTrue("activeOperations 必须是 volatile",
            java.lang.reflect.Modifier.isVolatile(field.modifiers))
    }

    @Test
    fun `DpmOperationGuard has cleanupInProgress flag`() {
        val field = DpmOperationGuard::class.java.getDeclaredField("cleanupInProgress")
        assertTrue("cleanupInProgress 必须是 volatile",
            java.lang.reflect.Modifier.isVolatile(field.modifiers))
    }

    @Test
    fun `DpmOperationGuard has begin and end suspend methods`() {
        val methods = DpmOperationGuard::class.java.declaredMethods
        val begin = methods.firstOrNull { it.name == "begin" }
        val end = methods.firstOrNull { it.name == "end" }
        assertNotNull("begin 方法必须存在", begin)
        assertNotNull("end 方法必须存在", end)
        // suspend 函数在 JVM 中有额外的 Continuation 参数
        assertTrue("begin 必须是 suspend",
            begin!!.parameterTypes.any { it.name.contains("Continuation") })
        assertTrue("end 必须是 suspend",
            end!!.parameterTypes.any { it.name.contains("Continuation") })
    }

    @Test
    fun `DpmOperationGuard has cleanupExclusive suspend method`() {
        val methods = DpmOperationGuard::class.java.declaredMethods
        val method = methods.firstOrNull { it.name == "cleanupExclusive" }
        assertNotNull("cleanupExclusive 方法必须存在", method)
        assertTrue("cleanupExclusive 必须是 suspend",
            method!!.parameterTypes.any { it.name.contains("Continuation") })
    }

    @Test
    fun `DpmOperationGuard has isAnyActive property`() {
        val method = DpmOperationGuard::class.java.getDeclaredMethod("isAnyActive")
        assertEquals(Boolean::class.java, method.returnType)
    }

    // ─── 源码契约：DAO 新增方法 ───

    @Test
    fun `DpmScanEvidenceDao declares count method`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/dao/DpmScanEvidenceDao.kt")
            .readText()
        assertTrue("DAO 必须声明 count()", source.contains("suspend fun count(): Int"))
        assertTrue("count SQL 必须使用 COUNT(*)", source.contains("SELECT COUNT(*) FROM dpm_scan_evidence"))
    }

    @Test
    fun `DpmScanEvidenceDao declares getAllPathProjections method`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/dao/DpmScanEvidenceDao.kt")
            .readText()
        assertTrue("DAO 必须声明 getAllPathProjections()", source.contains("suspend fun getAllPathProjections()"))
        assertTrue("投影类必须存在", source.contains("data class DpmEvidencePathProjection"))
    }

    @Test
    fun `DpmScanEvidenceDao declares getByEvidenceId method`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/dao/DpmScanEvidenceDao.kt")
            .readText()
        assertTrue("DAO 必须声明 getByEvidenceId", source.contains("suspend fun getByEvidenceId"))
        assertTrue("getByEvidenceId SQL 必须按 id 精确查询",
            source.contains("WHERE id = :evidenceId"))
    }

    @Test
    fun `DpmScanEvidenceDao declares deleteByEvidenceId method`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/dao/DpmScanEvidenceDao.kt")
            .readText()
        assertTrue("DAO 必须声明 deleteByEvidenceId", source.contains("suspend fun deleteByEvidenceId"))
        assertTrue("deleteByEvidenceId SQL 必须按 id 精确删除",
            source.contains("DELETE FROM dpm_scan_evidence WHERE id = :evidenceId"))
    }

    @Test
    fun `DpmScanEvidenceDao declares getAllEvidenceIds method`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/dao/DpmScanEvidenceDao.kt")
            .readText()
        assertTrue("DAO 必须声明 getAllEvidenceIds", source.contains("suspend fun getAllEvidenceIds"))
        assertTrue("getAllEvidenceIds SQL 必须 SELECT id",
            source.contains("SELECT id FROM dpm_scan_evidence"))
    }

    @Test
    fun `DpmScanEvidenceDao declares getAllForCleanup method`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/dao/DpmScanEvidenceDao.kt")
            .readText()
        assertTrue("DAO 必须声明 getAllForCleanup()", source.contains("suspend fun getAllForCleanup()"))
    }

    // ─── 源码契约：Repository 清理流程 ───

    @Test
    fun `repository declares getDpmEvidenceStats method`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt")
            .readText()
        assertTrue("Repository 必须声明 getDpmEvidenceStats", source.contains("suspend fun getDpmEvidenceStats()"))
        assertTrue("DpmEvidenceStats 数据类必须存在", source.contains("data class DpmEvidenceStats"))
    }

    @Test
    fun `repository declares cleanupAllDpmEvidence method`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt")
            .readText()
        assertTrue("Repository 必须声明 cleanupAllDpmEvidence", source.contains("suspend fun cleanupAllDpmEvidence()"))
        assertTrue("DpmCleanupResult 数据类必须存在", source.contains("data class DpmCleanupResult"))
    }

    @Test
    fun `repository cleanup uses DpmOperationGuard instead of ViewModel static flags`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt")
            .readText()
        assertTrue("必须使用 DpmOperationGuard", source.contains("DpmOperationGuard.cleanupExclusive()"))
        assertFalse("不得依赖 DpmScanViewModel 静态标志",
            source.contains("DpmScanViewModel.isDpmScanActive") ||
            source.contains("DpmScanViewModel.isPendingDpmBinding"))
        assertFalse("不得 import DpmScanViewModel",
            source.contains("import com.wearable.inspection.mobile.dpm.DpmScanViewModel"))
    }

    @Test
    fun `repository cleanup does not use deleteAll`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt")
            .readText()
        val cleanupStart = source.indexOf("suspend fun cleanupAllDpmEvidence()")
        val cleanupEnd = source.indexOf("// ---- 已采集照片 ----", cleanupStart)
        val cleanupBody = source.substring(cleanupStart, cleanupEnd)
        assertFalse("清理不得调用 deleteAll()", cleanupBody.contains("deleteAll()"))
    }

    @Test
    fun `repository cleanup processes by evidenceId`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt")
            .readText()
        assertTrue("必须使用 getAllEvidenceIds 快照", source.contains("getAllEvidenceIds()"))
        assertTrue("必须使用 getByEvidenceId 逐条查询", source.contains("getByEvidenceId("))
        assertTrue("必须使用 deleteByEvidenceId 逐条删除", source.contains("deleteByEvidenceId("))
    }

    @Test
    fun `repository cleanup validates path safety before file deletion`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt")
            .readText()
        assertTrue("必须验证路径安全性", source.contains("imageStore.isDpmEvidencePath("))
        assertTrue("越界路径必须保留 DB 行", source.contains("路径越界"))
    }

    @Test
    fun `repository cleanup handles missing files as idempotent`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt")
            .readText()
        assertTrue("文件缺失必须计数", source.contains("missingFiles++"))
        assertTrue("缺失视为幂等成功", source.contains("幂等"))
    }

    @Test
    fun `repository cleanup preserves DB row on file deletion failure`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt")
            .readText()
        assertTrue("文件删除失败时必须保留 DB 行",
            source.contains("文件删除失败，保留证据行") || source.contains("文件删除失败"))
    }

    @Test
    fun `repository cleanup preserves DB row on DB deletion failure`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt")
            .readText()
        assertTrue("DB 删除失败时必须保留记录",
            source.contains("DB 行删除失败，保留") || source.contains("DB删除失败"))
    }

    @Test
    fun `repository cleanup cleans orphan files referencing remaining DB rows`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt")
            .readText()
        assertTrue("必须清理孤立文件", source.contains("孤立"))
        assertTrue("孤立文件必须基于 remainingPaths 判断", source.contains("remainingPaths"))
    }

    @Test
    fun `repository getDpmEvidenceStats validates managed paths`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt")
            .readText()
        assertTrue("统计必须校验受管理路径", source.contains("isDpmEvidencePath(path)"))
        assertTrue("统计必须跟踪越界路径", source.contains("outOfBoundsFiles"))
        assertTrue("统计必须跟踪共享路径", source.contains("sharedPathCount") || source.contains("seenPaths"))
    }

    // ─── 源码契约：MobileImageStore ───

    @Test
    fun `MobileImageStore declares listDpmEvidenceFiles method`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/image/MobileImageStore.kt")
            .readText()
        assertTrue("必须声明 listDpmEvidenceFiles", source.contains("fun listDpmEvidenceFiles()"))
    }

    @Test
    fun `MobileImageStore declares isDpmEvidencePath method`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/image/MobileImageStore.kt")
            .readText()
        assertTrue("必须声明 isDpmEvidencePath", source.contains("fun isDpmEvidencePath(path: String): Boolean"))
        assertTrue("必须使用 canonicalPath 防止路径穿越", source.contains("canonicalFile"))
    }

    @Test
    fun `MobileImageStore listDpmEvidenceFiles returns empty when dir missing`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/image/MobileImageStore.kt")
            .readText()
        assertTrue("不存在时必须返回空列表", source.contains("listFiles()?.filter") || source.contains("?: emptyList()"))
    }

    // ─── 源码契约：DpmScanViewModel 使用 guard ───

    @Test
    fun `startScan uses DpmOperationGuard acquireLease`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/dpm/DpmScanViewModel.kt")
            .readText()
        assertTrue("startScan 必须调用 DpmOperationGuard.acquireLease()",
            source.contains("DpmOperationGuard.acquireLease()"))
    }

    @Test
    fun `stopScan releases scanLease via lease release`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/dpm/DpmScanViewModel.kt")
            .readText()
        assertTrue("stopScan 必须操作 scanLease", source.contains("scanLease"))
        assertTrue("saveEvidenceInScope 必须释放 lease", source.contains("lease?.release()"))
    }

    @Test
    fun `DpmScanViewModel no longer has static scan or binding flags`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/dpm/DpmScanViewModel.kt")
            .readText()
        assertFalse("不得有 isPendingDpmBinding 静态标志", source.contains("isPendingDpmBinding"))
        assertFalse("不得有 setPendingBindingActive", source.contains("setPendingBindingActive"))
        assertTrue("必须有 isDpmScanActive 实例属性", source.contains("var isDpmScanActive"))
    }

    // ─── 源码契约：WorkbenchViewModel 使用 guard ───

    @Test
    fun `WorkbenchViewModel uses DpmOperationGuard acquireLease on setPendingDpmBatchBinding`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/ui/screens/workbench/WorkbenchViewModel.kt")
            .readText()
        assertTrue("setPendingDpmBatchBinding 必须调用 DpmOperationGuard.acquireLease()",
            source.contains("DpmOperationGuard.acquireLease()"))
    }

    @Test
    fun `WorkbenchViewModel has TTL on pending binding`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/ui/screens/workbench/WorkbenchViewModel.kt")
            .readText()
        assertTrue("必须有 TTL 机制", source.contains("pendingBindingTtlMs") || source.contains("createdAtMs"))
        assertTrue("必须有 TTL 过期检查", source.contains("expired") || source.contains("TTL"))
    }

    @Test
    fun `WorkbenchViewModel has releasePendingBinding method`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/ui/screens/workbench/WorkbenchViewModel.kt")
            .readText()
        assertTrue("必须有 releasePendingBinding 方法", source.contains("fun releasePendingBinding()") || source.contains("releasePendingBinding"))
    }

    @Test
    fun `WorkbenchViewModel releases pending binding on onCleared`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/ui/screens/workbench/WorkbenchViewModel.kt")
            .readText()
        assertTrue("ViewModel 清理时必须释放 pending binding",
            source.contains("override fun onCleared()"))
    }

    // ─── 源码契约：TraceRecordsScreen 清理 UI ───

    @Test
    fun `TraceRecordsScreen displays DPM evidence stats`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt")
            .readText()
        assertTrue("必须显示原始证据统计", source.contains("原始证据:"))
        assertTrue("必须显示条数", source.contains("条"))
        assertTrue("必须显示图片数（原图+ROI）", source.contains("张（原图+ROI）"))
    }

    @Test
    fun `TraceRecordsScreen has cleanup button`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt")
            .readText()
        assertTrue("必须有清理按钮", source.contains("清理原始证据"))
        assertTrue("必须有清理确认对话框", source.contains("清理 DPM 原始证据"))
        assertTrue("必须显示 ZIP 不受影响提示", source.contains("已导出的 ZIP 不受影响"))
    }

    @Test
    fun `TraceRecordsScreen blocks cleanup when guard active`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt")
            .readText()
        assertTrue("必须检查 DpmOperationGuard.isAnyActive",
            source.contains("DpmOperationGuard.isAnyActive"))
    }

    @Test
    fun `TraceRecordsScreen wraps DPM export with guard lease`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt")
            .readText()
        assertTrue("DPM 导出必须调用 DpmOperationGuard.acquireLease()",
            source.contains("DpmOperationGuard.acquireLease()"))
        assertTrue("DPM 导出必须在 finally 中释放 lease",
            source.contains("lease.release()"))
    }

    @Test
    fun `TraceRecordsScreen has formatBytes helper`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt")
            .readText()
        assertTrue("必须有 formatBytes 辅助函数", source.contains("private fun formatBytes"))
    }

    @Test
    fun `TraceRecordsScreen shows cleanup result message`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/ui/screens/TraceRecordsScreen.kt")
            .readText()
        assertTrue("必须显示清理结果", source.contains("清理完成"))
        assertTrue("必须显示释放空间", source.contains("释放"))
    }

    // ─── 源码契约：独立 ZIP 不受影响 ───

    @Test
    fun `cleanup does not touch exported_packages table`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt")
            .readText()
        // cleanupAllDpmEvidenceInternal 方法内不应有删除 exported_packages 的操作
        val cleanupStart = source.indexOf("suspend fun cleanupAllDpmEvidence()")
        val cleanupEnd = source.indexOf("// ---- 已采集照片 ----", cleanupStart)
        val cleanupBody = source.substring(cleanupStart, cleanupEnd)
        assertFalse("清理不应操作导出包表",
            cleanupBody.contains("exportedPackageDao.delete") ||
            cleanupBody.contains("deleteExportedPackage"))
    }

    @Test
    fun `cleanup does not touch captures or roi_evidence directories`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt")
            .readText()
        val cleanupStart = source.indexOf("suspend fun cleanupAllDpmEvidence()")
        val cleanupEnd = source.indexOf("// ---- 已采集照片 ----", cleanupStart)
        val cleanupBody = source.substring(cleanupStart, cleanupEnd)
        assertFalse("清理不应操作 captures 目录", cleanupBody.contains("\"captures\""))
        assertFalse("清理不应操作 roi_evidence 目录", cleanupBody.contains("\"roi_evidence\""))
    }

    // ─── 源码契约：原有 deleteDpmEvidenceSafely 仍存在 ───

    @Test
    fun `original deleteDpmEvidenceSafely is preserved`() {
        val source = java.io.File("src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt")
            .readText()
        assertTrue("原有单行安全删除必须保留", source.contains("suspend fun deleteDpmEvidenceSafely(id: Long)"))
        assertTrue("安全规则：未绑定拒绝", source.contains("证据未绑定批次"))
        assertTrue("安全规则：共享引用拒绝", source.contains("共享会话"))
    }
}
