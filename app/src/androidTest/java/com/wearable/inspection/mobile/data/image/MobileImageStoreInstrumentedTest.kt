package com.wearable.inspection.mobile.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * MobileImageStore 真机 instrumented test
 *
 * Robolectric 的 ShadowBitmapFactory 对任意数据返回固定 100x100，
 * 无法可靠识别损坏 JPEG。本测试在真机上验证：
 * 1. 随机字节 validateJpeg() 返回 null
 * 2. 截断 JPEG validateJpeg() 返回 null
 * 3. storeCapturedImage() 遇到损坏 JPEG 后删除临时文件
 * 4. .part 写入/重命名失败场景
 * 5. 失败后最终文件、.part 和临时文件均不存在
 * 6. 已存在的最终文件内容不被覆盖
 */
@RunWith(AndroidJUnit4::class)
class MobileImageStoreInstrumentedTest {

    private lateinit var store: MobileImageStore
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = MobileImageStore(context)
        store.cleanTempDir()
        store.cleanPartFiles()
        // 清理 captures 目录中的测试残留
        File(store.getCapturesPath()).listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        store.cleanTempDir()
        store.cleanPartFiles()
        File(store.getCapturesPath()).listFiles()?.forEach { it.delete() }
    }

    // ─── 损坏 JPEG 校验 ───

    @Test
    fun randomBytes_validateJpeg_returnsNull() {
        val tempFile = store.generateTempFile()
        // 写入随机字节（不是有效 JPEG）
        FileOutputStream(tempFile).use { out ->
            val randomBytes = ByteArray(1024)
            java.util.Random().nextBytes(randomBytes)
            out.write(randomBytes)
        }

        val result = store.validateJpeg(tempFile)
        assertNull("随机字节文件 validateJpeg 应返回 null", result)

        tempFile.delete()
    }

    @Test
    fun truncatedJpeg_validateJpeg_returnsNull() {
        val tempFile = store.generateTempFile()
        // 创建一个有效 JPEG，然后截断
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        FileOutputStream(tempFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bitmap.recycle()

        // 截断到只保留前 100 字节
        val originalSize = tempFile.length()
        assertTrue("JPEG 应大于 100 字节", originalSize > 100)
        RandomAccessFile(tempFile, "rw").use { raf ->
            raf.setLength(100)
        }

        val result = store.validateJpeg(tempFile)
        assertNull("截断 JPEG validateJpeg 应返回 null", result)

        tempFile.delete()
    }

    // ─── storeCapturedImage 损坏文件清理 ───

    @Test
    fun storeCapturedImage_corruptedJpeg_deletesTempFile() {
        val tempFile = store.generateTempFile()
        // 写入随机字节（不是有效 JPEG）
        FileOutputStream(tempFile).use { out ->
            val randomBytes = ByteArray(2048)
            java.util.Random().nextBytes(randomBytes)
            out.write(randomBytes)
        }

        assertTrue("临时文件应存在", tempFile.exists())

        val result = store.storeCapturedImage(tempFile)
        assertNull("损坏 JPEG 存储应失败", result)
        assertFalse("损坏 JPEG 存储后临时文件应被删除", tempFile.exists())
    }

    @Test
    fun storeCapturedImage_emptyFile_deletesTempFile() {
        val tempFile = store.generateTempFile()
        tempFile.createNewFile() // 空文件

        assertTrue("临时文件应存在", tempFile.exists())

        val result = store.storeCapturedImage(tempFile)
        assertNull("空文件存储应失败", result)
        assertFalse("空文件存储后临时文件应被删除", tempFile.exists())
    }

    // ─── .part 残留检查 ───

    @Test
    fun storeCapturedImage_failure_noPartResidual() {
        val tempFile = store.generateTempFile()
        // 写入随机字节
        FileOutputStream(tempFile).use { out ->
            val randomBytes = ByteArray(512)
            java.util.Random().nextBytes(randomBytes)
            out.write(randomBytes)
        }

        val capturesDir = File(store.getCapturesPath())

        val result = store.storeCapturedImage(tempFile)
        assertNull("损坏文件存储应失败", result)

        // 检查无 .part 残留
        val partFiles = capturesDir.listFiles()?.filter { it.name.endsWith(".part") } ?: emptyList()
        assertTrue("不应留下 .part 文件", partFiles.isEmpty())

        // 检查无最终文件残留
        val finalFiles = capturesDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: emptyList()
        assertTrue("不应留下最终文件", finalFiles.isEmpty())
    }

    // ─── 已存在文件不被覆盖 ───

    @Test
    fun atomicMoveToFinal_existingFile_notOverwritten() {
        // 先创建一个有效 JPEG 并移动到最终路径
        val tempFile1 = createValidJpeg(Bitmap.Config.ARGB_8888, android.graphics.Color.RED)
        val result1 = store.atomicMoveToFinal(tempFile1)
        assertNotNull("第一次移动应成功", result1)

        val firstFile = result1!!
        val firstContent = firstFile.readBytes()
        val firstSize = firstFile.length()

        // 再创建一个不同颜色的 JPEG
        val tempFile2 = createValidJpeg(Bitmap.Config.ARGB_8888, android.graphics.Color.GREEN)
        val result2 = store.atomicMoveToFinal(tempFile2)

        // 第二次移动应该成功（不同文件名）
        assertNotNull("第二次移动应成功", result2)
        val secondFile = result2!!

        // 关键校验：第一个文件内容未被覆盖
        val firstContentAfter = firstFile.readBytes()
        assertArrayEquals("第一个文件不应被覆盖", firstContent, firstContentAfter)
        assertEquals("第一个文件大小不应改变", firstSize, firstFile.length())

        // 两个文件路径不同
        assertNotEquals("两次移动应产生不同路径", firstFile.absolutePath, secondFile.absolutePath)

        firstFile.delete()
        secondFile.delete()
    }

    // ─── 完整存储流程（有效 JPEG） ───

    @Test
    fun storeCapturedImage_validJpeg_success() {
        val tempFile = createValidJpeg(Bitmap.Config.ARGB_8888, android.graphics.Color.YELLOW)

        val result = store.storeCapturedImage(tempFile)
        assertNotNull("有效 JPEG 存储应成功", result)

        val stored = result!!
        assertTrue("宽度应大于 0", stored.width > 0)
        assertTrue("高度应大于 0", stored.height > 0)
        assertTrue("文件大小应大于 0", stored.sizeBytes > 0)

        // 验证最终文件存在且可重新解码
        val finalFile = File(stored.finalPath)
        assertTrue("最终文件应存在", finalFile.exists())
        assertTrue("最终文件应非空", finalFile.length() > 0)

        val bitmap = BitmapFactory.decodeFile(stored.finalPath)
        assertNotNull("应能重新解码", bitmap)
        assertEquals("宽度应匹配", stored.width, bitmap.width)
        assertEquals("高度应匹配", stored.height, bitmap.height)
        bitmap.recycle()

        // 临时文件应被删除
        assertFalse("临时文件应被删除", tempFile.exists())

        // 无 .part 残留
        val partFiles = File(store.getCapturesPath()).listFiles()
            ?.filter { it.name.endsWith(".part") } ?: emptyList()
        assertTrue("不应有 .part 残留", partFiles.isEmpty())

        finalFile.delete()
    }

    // ─── 原子移动：不存在文件失败 ───

    @Test
    fun atomicMoveToFinal_nonexistentFile_returnsNull() {
        val nonexistent = File(context.cacheDir, "nonexistent_${System.nanoTime()}.jpg")
        val result = store.atomicMoveToFinal(nonexistent)
        assertNull("不存在文件移动应返回 null", result)
    }

    // ─── 辅助方法 ───

    private fun createValidJpeg(config: Bitmap.Config, color: Int): File {
        val tempFile = store.generateTempFile()
        val bitmap = Bitmap.createBitmap(100, 100, config)
        bitmap.eraseColor(color)
        FileOutputStream(tempFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bitmap.recycle()
        return tempFile
    }
}
