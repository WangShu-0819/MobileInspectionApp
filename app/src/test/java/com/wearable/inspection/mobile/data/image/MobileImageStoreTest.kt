package com.wearable.inspection.mobile.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

/**
 * MobileImageStore 测试
 *
 * 测试重点：
 * 1. 连续生成文件名不重复
 * 2. 空文件校验失败
 * 3. 不存在文件校验失败
 * 4. 最终文件已存在时不得覆盖（校验原内容不变）
 * 5. 成功移动后不留 .part 文件、临时文件被清理
 * 6. 不存在的临时文件移动失败
 * 7. 成功 JPEG 可以重新解码，宽高有效
 * 8. 校验失败时清理临时文件
 * 9. 存储失败后不留残留
 *
 * 注意：Robolectric 的 ShadowBitmapFactory 对任意数据返回固定的 100x100，
 * 因此"损坏 JPEG"校验（assertTrue assertNull）只能在 instrumented test 中验证。
 * 本测试使用 guaranteed-failure 路径（空文件、不存在文件）验证 null 返回。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MobileImageStoreTest {

    private lateinit var store: MobileImageStore
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = MobileImageStore(context)
        store.cleanTempDir()
        store.cleanPartFiles()
    }

    @After
    fun tearDown() {
        store.cleanTempDir()
        store.cleanPartFiles()
    }

    // ─── 文件名唯一性测试 ───

    @Test
    fun `连续生成文件名不重复`() {
        val files = mutableSetOf<String>()
        repeat(100) {
            val file = store.generateTempFile()
            assertFalse("文件名重复: ${file.name}", files.contains(file.name))
            files.add(file.name)
        }
    }

    // ─── 校验测试 ───

    @Test
    fun `空文件校验失败`() {
        val tempFile = store.generateTempFile()
        tempFile.createNewFile()

        val result = store.validateJpeg(tempFile)
        assertNull("空文件校验应该失败", result)
    }

    @Test
    fun `不存在文件校验失败`() {
        val tempFile = store.generateTempFile()
        val result = store.validateJpeg(tempFile)
        assertNull("不存在文件校验应该失败", result)
    }

    @Test
    fun `有效 JPEG 校验成功且宽高有效`() {
        val tempFile = createValidJpegTempFile()

        val result = store.validateJpeg(tempFile)
        assertNotNull("有效 JPEG 校验应该成功", result)

        val stored = result!!
        assertTrue("宽度应该大于 0", stored.width > 0)
        assertTrue("高度应该大于 0", stored.height > 0)
        assertTrue("文件大小应该大于 0", stored.sizeBytes > 0)

        tempFile.delete()
    }

    // ─── 原子移动测试 ───

    @Test
    fun `最终文件已存在时不得覆盖`() {
        val tempFile = createValidJpegTempFile()

        val result1 = store.atomicMoveToFinal(tempFile)
        assertNotNull("第一次移动应该成功", result1)

        val firstFile = result1!!
        val firstContent = firstFile.readBytes()

        val tempFile2 = createValidJpegTempFile()

        val result2 = store.atomicMoveToFinal(tempFile2)
        assertNotNull("第二次移动应该成功", result2)

        // 关键校验：第一个文件内容未被覆盖
        val firstContentAfter = firstFile.readBytes()
        assertArrayEquals("第一个文件不应该被覆盖", firstContent, firstContentAfter)

        result2?.delete()
        firstFile.delete()
    }

    @Test
    fun `成功移动后不留 part 文件`() {
        val tempFile = createValidJpegTempFile()

        val capturesDir = File(context.filesDir, "captures")

        val partFilesBefore = capturesDir.listFiles()?.filter { it.name.endsWith(".part") } ?: emptyList()
        assertTrue("移动前不应该有 .part 文件", partFilesBefore.isEmpty())

        val result = store.atomicMoveToFinal(tempFile)
        assertNotNull("移动应该成功", result)

        val partFilesAfter = capturesDir.listFiles()?.filter { it.name.endsWith(".part") } ?: emptyList()
        assertTrue("移动后不应该有 .part 文件", partFilesAfter.isEmpty())

        result?.delete()
    }

    @Test
    fun `移动后临时文件被删除`() {
        val tempFile = createValidJpegTempFile()
        assertTrue("临时文件应该存在", tempFile.exists())

        val result = store.atomicMoveToFinal(tempFile)
        assertNotNull("移动应该成功", result)

        assertFalse("临时文件应该被删除", tempFile.exists())

        result?.delete()
    }

    @Test
    fun `不存在的临时文件移动失败`() {
        val tempFile = File(context.cacheDir, "nonexistent.jpg")

        val result = store.atomicMoveToFinal(tempFile)
        assertNull("不存在的文件移动应该失败", result)
    }

    // ─── 完整存储流程测试 ───

    @Test
    fun `完整存储流程成功时返回有效结果`() {
        val tempFile = createValidJpegTempFile()

        val result = store.storeCapturedImage(tempFile)
        assertNotNull("存储应该成功", result)

        val stored = result!!
        assertTrue("宽度应该大于 0", stored.width > 0)
        assertTrue("高度应该大于 0", stored.height > 0)
        assertTrue("文件大小应该大于 0", stored.sizeBytes > 0)
        assertTrue("文件应该存在", File(stored.finalPath).exists())

        // 重新解码验证
        val bitmap = BitmapFactory.decodeFile(stored.finalPath)
        assertNotNull("应该能重新解码", bitmap)
        assertEquals("宽度应该匹配", stored.width, bitmap.width)
        assertEquals("高度应该匹配", stored.height, bitmap.height)

        bitmap.recycle()
        File(stored.finalPath).delete()
    }

    @Test
    fun `校验失败时清理临时文件`() {
        val tempFile = store.generateTempFile()
        tempFile.createNewFile() // 空文件

        val result = store.storeCapturedImage(tempFile)
        assertNull("空文件存储应该失败", result)

        assertFalse("临时文件应该被删除", tempFile.exists())
    }

    @Test
    fun `存储失败后不留 part 残留`() {
        val tempFile = store.generateTempFile()
        tempFile.createNewFile() // 空文件 → validateJpeg 返回 null → storeCapturedImage 删除临时文件

        val capturesDir = File(context.filesDir, "captures")

        val result = store.storeCapturedImage(tempFile)
        assertNull("空文件存储应该失败", result)

        assertFalse("临时文件应该被删除", tempFile.exists())

        val partFiles = capturesDir.listFiles()?.filter { it.name.endsWith(".part") } ?: emptyList()
        assertTrue("不应该留下 .part 文件", partFiles.isEmpty())
    }

    // ─── 模板图片存储测试 ───

    @Test
    fun `模板图片存储成功`() {
        val tempFile = createValidJpegTempFile()

        val result = store.storeTemplateImage(tempFile)
        assertNotNull("模板图片存储应该成功", result)

        val stored = result!!
        assertTrue("文件应该存在", File(stored.finalPath).exists())
        assertTrue("路径应该在 template_images 目录", stored.finalPath.contains("template_images"))
        assertTrue("宽度应该大于 0", stored.width > 0)
        assertTrue("高度应该大于 0", stored.height > 0)

        File(stored.finalPath).delete()
    }

    @Test
    fun `模板图片存储后临时文件被清理`() {
        val tempFile = createValidJpegTempFile()

        val result = store.storeTemplateImage(tempFile)
        assertNotNull("存储应该成功", result)
        assertFalse("临时文件应该被删除", tempFile.exists())

        result?.let { File(it.finalPath).delete() }
    }

    @Test
    fun `空文件模板图片存储失败`() {
        val tempFile = store.generateTempFile()
        tempFile.createNewFile()

        val result = store.storeTemplateImage(tempFile)
        assertNull("空文件存储应该失败", result)
        assertFalse("临时文件应该被删除", tempFile.exists())
    }

    @Test
    fun `模板图片存储失败后不留 part 残留`() {
        val tempFile = store.generateTempFile()
        tempFile.createNewFile() // 空文件

        val templateDir = File(context.filesDir, "template_images")

        val result = store.storeTemplateImage(tempFile)
        assertNull("空文件存储应该失败", result)

        val partFiles = templateDir.listFiles()?.filter { it.name.endsWith(".part") } ?: emptyList()
        assertTrue("不应该留下 .part 文件", partFiles.isEmpty())
    }

    @Test
    fun `删除模板图片成功`() {
        val tempFile = createValidJpegTempFile()
        val stored = store.storeTemplateImage(tempFile)
        assertNotNull("存储应该成功", stored)

        val path = stored!!.finalPath
        assertTrue("文件应该存在", File(path).exists())

        store.deleteTemplateImage(path)
        assertFalse("文件应该被删除", File(path).exists())
    }

    @Test
    fun `删除模板图片安全检查拒绝非 template_images 路径`() {
        // 尝试删除不在 template_images/ 目录的文件
        val externalFile = File(context.filesDir, "captures/test.jpg")
        externalFile.parentFile?.mkdirs()
        externalFile.createNewFile()
        assertTrue("外部文件应该存在", externalFile.exists())

        store.deleteTemplateImage(externalFile.absolutePath)
        // 安全检查应该拒绝删除非 template_images 路径
        assertTrue("非 template_images 路径不应该被删除", externalFile.exists())

        externalFile.delete()
    }

    @Test
    fun `模板图片目录路径有效`() {
        val path = store.getTemplateImagesPath()
        assertNotNull("路径不应该为 null", path)
        assertTrue("路径应该包含 template_images", path.contains("template_images"))
    }

    // ─── 辅助方法 ───

    private fun createValidJpegTempFile(): File {
        val tempFile = store.generateTempFile()
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)

        FileOutputStream(tempFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }

        bitmap.recycle()
        return tempFile
    }
}
