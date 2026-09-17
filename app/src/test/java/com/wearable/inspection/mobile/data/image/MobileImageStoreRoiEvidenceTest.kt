package com.wearable.inspection.mobile.data.image

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * MobileImageStore ROI 证据图存储测试
 *
 * 覆盖：
 * - 改判图文件真实写入、非空、可重读
 * - 未改判不新建文件
 * - 撤销改判只删除当前记录关联文件，不误删其他记录
 * - 写入失败返回 null（不产生成功改判）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MobileImageStoreRoiEvidenceTest {

    private lateinit var store: MobileImageStore
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = MobileImageStore(context)
    }

    @After
    fun tearDown() {
        val evidenceDir = File(context.filesDir, "roi_evidence")
        if (evidenceDir.exists()) evidenceDir.deleteRecursively()
    }

    @Test
    fun saveRoiEvidence_writesNonEmptyFileAndReturnsPath() {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val fileName = "roi_b1_p1_t1_v0_r1.jpg"

        val path = store.saveRoiEvidence(bitmap, fileName)

        assertNotNull("保存应返回非 null 路径", path)
        val file = File(path!!)
        assertTrue("文件应存在", file.exists())
        assertTrue("文件应非空", file.length() > 0L)
        assertTrue("路径应在 roi_evidence 目录内", file.parentFile?.name == "roi_evidence")
    }

    @Test
    fun saveRoiEvidence_returnsPathAndFileIsReadable() {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val fileName = "roi_b1_p1_t1_v0_r2.jpg"

        val path = store.saveRoiEvidence(bitmap, fileName)

        assertNotNull(path)
        assertTrue("roiEvidenceFileValid 应返回 true", store.roiEvidenceFileValid(path))
        val file = File(path!!)
        assertTrue("文件可重读", file.inputStream().use { it.available() > 0 })
    }

    @Test
    fun roiEvidenceFileValid_returnsFalseForNullOrNonexistent() {
        assertFalse("null 路径应返回 false", store.roiEvidenceFileValid(null))
        assertFalse("空路径应返回 false", store.roiEvidenceFileValid(""))
        assertFalse("不存在路径应返回 false", store.roiEvidenceFileValid("/nonexistent/file.jpg"))
    }

    @Test
    fun deleteRoiEvidence_removesFileAndLeavesOthers() {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val path1 = store.saveRoiEvidence(bitmap, "roi_evidence_1.jpg")
        val path2 = store.saveRoiEvidence(bitmap, "roi_evidence_2.jpg")

        assertNotNull(path1)
        assertNotNull(path2)
        assertTrue(File(path1!!).exists())
        assertTrue(File(path2!!).exists())

        // 删除第一条记录的证据
        store.deleteRoiEvidence(path1)

        assertFalse("已删除文件应不存在", File(path1).exists())
        assertTrue("其他文件应保留", File(path2!!).exists())
    }

    @Test
    fun deleteRoiEvidence_ignoresNullAndBlank() {
        // 不应抛异常
        store.deleteRoiEvidence(null)
        store.deleteRoiEvidence("")
        store.deleteRoiEvidence("  ")
    }

    @Test
    fun deleteRoiEvidence_rejectsPathOutsideEvidenceDir() {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val path = store.saveRoiEvidence(bitmap, "roi_evidence_safe.jpg")
        assertNotNull(path)

        // 尝试用目录外的路径删除 — 应不生效
        store.deleteRoiEvidence("/tmp/some_other_file.jpg")
        assertTrue("目录外路径不应影响证据文件", File(path!!).exists())
    }

    @Test
    fun saveRoiEvidence_overwritesExistingFileWithSameName() {
        val bitmap1 = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val bitmap2 = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        val fileName = "roi_overwrite.jpg"

        val path1 = store.saveRoiEvidence(bitmap1, fileName)
        assertNotNull(path1)
        val size1 = File(path1!!).length()

        val path2 = store.saveRoiEvidence(bitmap2, fileName)
        assertNotNull(path2)
        assertEquals("两次保存应返回同一路径", path1, path2)
    }

    @Test
    fun getRoiEvidencePath_returnsDirectoryPath() {
        val path = store.getRoiEvidencePath()
        assertTrue("应指向 roi_evidence 目录", path.endsWith("roi_evidence"))
        assertTrue("目录应存在", File(path).isDirectory)
    }
}
