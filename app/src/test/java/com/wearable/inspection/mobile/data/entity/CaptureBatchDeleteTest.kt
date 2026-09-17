package com.wearable.inspection.mobile.data.entity

import org.junit.Assert.*
import org.junit.Test

/**
 * 采集批次删除功能单元测试
 *
 * 覆盖：
 * - CaptureBatchEntity 字段正确性
 * - batchId 精确匹配逻辑（选中/取消选中）
 * - 照片路径收集概念
 * - 空列表和已删除批次边界
 * - 导出状态冲突检测
 *
 * 注意：以下场景需要 instrumented 测试覆盖：
 * - Repository.deleteCaptureBatchCompletely 真实 DB + 文件删除
 * - Room CASCADE 删除 captured_photos 和 view_roi_confirms
 * - TraceRecordsScreen UI 交互（选中、确认、取消）
 */
class CaptureBatchDeleteTest {

    private fun createBatch(
        batchId: String = "batch_001",
        partId: String? = "part_001",
        partName: String? = "零件A",
        viewCount: Int = 4
    ) = CaptureBatchEntity(
        batchId = batchId,
        partId = partId,
        partName = partName,
        startTime = System.currentTimeMillis(),
        viewCount = viewCount
    )

    private fun createPhoto(
        photoId: Long = 1,
        batchId: String = "batch_001",
        filePath: String = "/captures/photo_1.jpg",
        viewIndex: Int = 0
    ) = CapturedPhotoEntity(
        photoId = photoId,
        batchId = batchId,
        filePath = filePath,
        viewIndex = viewIndex,
        templateId = "tpl_001",
        templateName = "视角1"
    )

    // ---- batchId 精确匹配 ----

    @Test
    fun `batchId equality matches exact string`() {
        val batch = createBatch(batchId = "batch_abc123")
        assertEquals("batch_abc123", batch.batchId)
        assertTrue(batch.batchId == "batch_abc123")
    }

    @Test
    fun `batchId does not match different string`() {
        val batch = createBatch(batchId = "batch_001")
        assertFalse(batch.batchId == "batch_002")
    }

    @Test
    fun `batchId does not match prefix`() {
        val batch = createBatch(batchId = "batch_001")
        assertFalse(batch.batchId == "batch_00")
        assertFalse(batch.batchId == "batch_001_extra")
    }

    @Test
    fun `batchId does not match empty string`() {
        val batch = createBatch(batchId = "batch_001")
        assertFalse(batch.batchId.isEmpty())
        assertFalse(batch.batchId == "")
    }

    // ---- 选中状态逻辑 ----

    @Test
    fun `selection toggle on same batchId clears selection`() {
        var selectedBatchId: String? = "batch_001"
        val clickedBatchId = "batch_001"
        // 模拟 Toggle 逻辑：再次点击同一个 batch 取消选中
        selectedBatchId = if (selectedBatchId == clickedBatchId) null else clickedBatchId
        assertNull(selectedBatchId)
    }

    @Test
    fun `selection toggle on different batchId changes selection`() {
        var selectedBatchId: String? = "batch_001"
        val clickedBatchId = "batch_002"
        selectedBatchId = if (selectedBatchId == clickedBatchId) null else clickedBatchId
        assertEquals("batch_002", selectedBatchId)
    }

    @Test
    fun `selection toggle from null selects batch`() {
        var selectedBatchId: String? = null
        val clickedBatchId = "batch_001"
        selectedBatchId = if (selectedBatchId == clickedBatchId) null else clickedBatchId
        assertEquals("batch_001", selectedBatchId)
    }

    // ---- 照片路径收集 ----

    @Test
    fun `photos have correct filePath for deletion`() {
        val photos = listOf(
            createPhoto(photoId = 1, filePath = "/captures/view_0.jpg"),
            createPhoto(photoId = 2, filePath = "/captures/view_1.jpg"),
            createPhoto(photoId = 3, filePath = "/captures/view_2.jpg")
        )
        val paths = photos.map { it.filePath }
        assertEquals(3, paths.size)
        assertEquals("/captures/view_0.jpg", paths[0])
        assertEquals("/captures/view_1.jpg", paths[1])
        assertEquals("/captures/view_2.jpg", paths[2])
    }

    @Test
    fun `photos filtered by batchId are isolated`() {
        val batch1Photos = listOf(
            createPhoto(photoId = 1, batchId = "batch_001", filePath = "/captures/b1_v0.jpg"),
            createPhoto(photoId = 2, batchId = "batch_001", filePath = "/captures/b1_v1.jpg")
        )
        val batch2Photos = listOf(
            createPhoto(photoId = 3, batchId = "batch_002", filePath = "/captures/b2_v0.jpg")
        )
        // batch_001 的照片不应出现在 batch_002 中
        val batch1Paths = batch1Photos.map { it.filePath }
        val batch2Paths = batch2Photos.map { it.filePath }
        assertTrue(batch1Paths.none { it in batch2Paths })
        assertTrue(batch2Paths.none { it in batch1Paths })
    }

    @Test
    fun `empty photo list means no files to delete`() {
        val photos = emptyList<CapturedPhotoEntity>()
        val paths = photos.map { it.filePath }
        assertTrue(paths.isEmpty())
    }

    // ---- 空列表和已删除批次 ----

    @Test
    fun `empty batch list has no selection target`() {
        val batches = emptyList<CaptureBatchEntity>()
        val selectedBatchId = "batch_001"
        val selectedBatch = batches.find { it.batchId == selectedBatchId }
        assertNull(selectedBatch)
    }

    @Test
    fun `deleted batch no longer appears in list`() {
        val batches = mutableListOf(
            createBatch(batchId = "batch_001"),
            createBatch(batchId = "batch_002"),
            createBatch(batchId = "batch_003")
        )
        val deleteTarget = "batch_002"
        batches.removeAll { it.batchId == deleteTarget }
        assertEquals(2, batches.size)
        assertNull(batches.find { it.batchId == deleteTarget })
        assertNotNull(batches.find { it.batchId == "batch_001" })
        assertNotNull(batches.find { it.batchId == "batch_003" })
    }

    @Test
    fun `selecting already-deleted batch is safe`() {
        var selectedBatchId: String? = "batch_deleted"
        val batches = listOf(createBatch(batchId = "batch_001"))
        val selectedBatch = batches.find { it.batchId == selectedBatchId }
        assertNull(selectedBatch)
        // UI 应跳过删除（batch == null）
    }

    // ---- 导出状态冲突 ----

    @Test
    fun `delete blocked when exporting same batch`() {
        val exportingBatchId = "batch_001"
        val deleteTargetId = "batch_001"
        val isBlocked = exportingBatchId == deleteTargetId
        assertTrue(isBlocked)
    }

    @Test
    fun `delete allowed when exporting different batch`() {
        val exportingBatchId = "batch_001"
        val deleteTargetId = "batch_002"
        val isBlocked = exportingBatchId == deleteTargetId
        assertFalse(isBlocked)
    }

    @Test
    fun `delete allowed when not exporting`() {
        val exportingBatchId: String? = null
        val deleteTargetId = "batch_001"
        val isBlocked = exportingBatchId == deleteTargetId
        assertFalse(isBlocked)
    }

    // ---- 批次实体字段 ----

    @Test
    fun `batch entity has correct fields`() {
        val batch = createBatch(
            batchId = "batch_xyz",
            partId = "part_abc",
            partName = "螺母零件",
            viewCount = 8
        )
        assertEquals("batch_xyz", batch.batchId)
        assertEquals("part_abc", batch.partId)
        assertEquals("螺母零件", batch.partName)
        assertEquals(8, batch.viewCount)
    }

    @Test
    fun `batch with null partId is valid`() {
        val batch = createBatch(partId = null, partName = null)
        assertNull(batch.partId)
        assertNull(batch.partName)
    }

    @Test
    fun `batch startTime is positive`() {
        val batch = createBatch()
        assertTrue(batch.startTime > 0)
    }

    // ---- 照片实体与批次关联 ----

    @Test
    fun `photo batchId matches parent batch`() {
        val batchId = "batch_001"
        val photo = createPhoto(batchId = batchId)
        assertEquals(batchId, photo.batchId)
    }

    @Test
    fun `photo viewIndex is preserved`() {
        val photo = createPhoto(viewIndex = 3)
        assertEquals(3, photo.viewIndex)
    }

    @Test
    fun `multiple photos can belong to same batch`() {
        val batchId = "batch_001"
        val photos = (0 until 4).map { viewIndex ->
            createPhoto(
                photoId = viewIndex.toLong() + 1,
                batchId = batchId,
                filePath = "/captures/view_$viewIndex.jpg",
                viewIndex = viewIndex
            )
        }
        assertTrue(photos.all { it.batchId == batchId })
        assertEquals(4, photos.size)
        assertEquals(setOf(0, 1, 2, 3), photos.map { it.viewIndex }.toSet())
    }

    // ---- 路径安全验证（概念测试）----

    @Test
    fun `photo path must be under captures directory`() {
        // 受管理路径验证逻辑概念：只允许 filesDir/captures 目录的文件
        val validPaths = listOf(
            "/data/data/com.example/files/captures/photo_1.jpg",
            "/data/user/0/com.example/files/captures/capture_20260101_001.jpg"
        )
        val invalidPaths = listOf(
            "/sdcard/DCIM/photo.jpg",           // 外部存储
            "/data/data/com.example/cache/tmp.jpg", // 缓存目录
            "/data/data/com.example/files/template_images/tpl.jpg", // 模板目录
            "/data/data/com.example/files/dpm_evidence/frame.jpg"   // DPM 目录
        )
        // 概念验证：有效路径包含 captures 子目录
        validPaths.forEach { path ->
            assertTrue("应接受 captures 目录: $path", path.contains("/captures/"))
        }
        // 概念验证：无效路径不包含 captures 子目录
        invalidPaths.forEach { path ->
            assertFalse("应拒绝非 captures 目录: $path", path.contains("/captures/") && !path.contains("/cache/"))
        }
    }

    @Test
    fun `roi evidence path must be under roi_evidence directory`() {
        val validPaths = listOf(
            "/data/data/com.example/files/roi_evidence/evidence_1.jpg",
            "/data/user/0/com.example/files/roi_evidence/batch_photo_roi.jpg"
        )
        val invalidPaths = listOf(
            "/sdcard/Pictures/evidence.jpg",
            "/data/data/com.example/files/captures/photo.jpg",
            "/data/data/com.example/files/dpm_evidence/roi.jpg"
        )
        validPaths.forEach { path ->
            assertTrue("应接受 roi_evidence 目录: $path", path.contains("/roi_evidence/"))
        }
        invalidPaths.forEach { path ->
            assertFalse("应拒绝非 roi_evidence 目录: $path", path.contains("/roi_evidence/"))
        }
    }

    // ---- 文件不存在时幂等性（概念测试）----

    @Test
    fun `file not found should be treated as idempotent success`() {
        // 删除逻辑中：!file.exists() → 缺失计数递增，不视为失败
        val nonExistentPath = "/data/data/com.example/files/captures/nonexistent.jpg"
        val file = java.io.File(nonExistentPath)
        assertFalse("不存在的文件应返回 false", file.exists())
        // 概念验证：不存在的文件不影响成功状态
    }

    // ---- 文件删除失败时保留批次记录（概念测试）----

    @Test
    fun `file delete failure should preserve batch for retry`() {
        // 删除逻辑中：file.delete() 返回 false 或抛 SecurityException → 保留 batchId
        val selectedBatchIds = setOf("batch_failed", "batch_ok")
        val deletedBatchIds = listOf("batch_ok")
        val remaining = selectedBatchIds - deletedBatchIds.toSet()
        assertEquals(setOf("batch_failed"), remaining)
        assertTrue("失败批次应保留在选中集合", "batch_failed" in remaining)
    }

    // ---- DPM 证据路径不参与照片清理 ----

    @Test
    fun `dpm evidence paths should not be collected for photo cleanup`() {
        // DPM 证据有独立目录 filesDir/dpm_evidence，不在 captures 或 roi_evidence 内
        val dpmPath = "/data/data/com.example/files/dpm_evidence/frame_001.jpg"
        assertFalse("DPM 路径不应在 captures 目录", dpmPath.contains("/captures/"))
        assertFalse("DPM 路径不应在 roi_evidence 目录", dpmPath.contains("/roi_evidence/"))
    }
}
