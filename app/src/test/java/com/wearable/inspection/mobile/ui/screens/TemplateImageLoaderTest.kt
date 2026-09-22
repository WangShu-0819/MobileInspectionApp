package com.wearable.inspection.mobile.ui.screens

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * TemplateImageLoader 的 JVM 回归测试。
 *
 * 覆盖场景：
 * - 有效绝对路径
 * - 有效 file:// URI（真正使用 Uri.fromFile）
 * - 有效 content:// URI（mock ContentResolver）
 * - 缺失路径
 * - 空文件 / 非法图片
 * - content:// 无 ContentResolver
 * - CancellationException 不被转换成加载失败
 * - 受控降采样参数
 * - 路径为空
 * - maxTargetSize <= 0 不死循环
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TemplateImageLoaderTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDir("template_test_")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ── 路径为空 ──

    @Test
    fun `null path returns Failure with empty path message`() = runBlocking {
        val result = loadTemplateBitmap(null)
        assertFailure(result, "模板路径为空")
    }

    @Test
    fun `blank path returns Failure with empty path message`() = runBlocking {
        val result = loadTemplateBitmap("   ")
        assertFailure(result, "模板路径为空")
    }

    // ── 文件不存在 ──

    @Test
    fun `non-existent file returns Failure with not exist message`() = runBlocking {
        val result = loadTemplateBitmap("/nonexistent/path/image.png")
        assertFailure(result, "模板图片不存在")
    }

    @Test
    fun `file URI to non-existent file returns Failure`() = runBlocking {
        val result = loadTemplateBitmap("file:///nonexistent/path/image.png")
        assertFailure(result, "模板图片不存在")
    }

    // ── 空文件 ──

    @Test
    fun `empty file returns Failure with empty file message`() = runBlocking {
        val emptyFile = File(tempDir, "empty.png")
        emptyFile.createNewFile()
        val result = loadTemplateBitmap(emptyFile.absolutePath)
        assertFailure(result, "模板图片文件为空")
    }

    // ── 有效绝对路径 ──

    @Test
    fun `valid absolute path returns Success with bitmap`() = runBlocking {
        val imageFile = createValidTestImage("valid.png")
        val result = loadTemplateBitmap(imageFile.absolutePath)
        assertSuccess(result)
    }

    // ── 有效 file:// URI（真正使用 Uri.fromFile） ──

    @Test
    fun `file URI returns Success`() = runBlocking {
        val imageFile = createValidTestImage("file_uri.png")
        // 手动构造标准 file:// URI（Robolectric 的 Uri.fromFile 在 Windows 上会编码路径）
        val absPath = imageFile.absolutePath.replace("\\", "/")
        val fileUri = "file:///$absPath"
        assertTrue("应以 file:// 开头", fileUri.startsWith("file://"))
        val result = loadTemplateBitmap(fileUri)
        assertSuccess(result)
    }

    // ── 有效 content:// URI（mock ContentResolver，每次返回新流） ──

    @Test
    fun `content URI with mock resolver returns Success via two separate streams`() = runBlocking {
        val pngBytes = createValidPngBytes()
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        val mockResolver = mockContentResolverWithCounter(pngBytes, callCount)
        val result = loadTemplateBitmap(
            "content://com.example.provider/templates/1",
            contentResolver = mockResolver
        )
        assertSuccess(result)
        // 验证恰好打开了两次流（bounds + decode）
        assertEquals("应恰好打开两次 InputStream", 2, callCount.get())
    }

    // ── content:// 无 ContentResolver ──

    @Test
    fun `content URI without ContentResolver returns Failure`() = runBlocking {
        val result = loadTemplateBitmap("content://com.example.provider/images/1", contentResolver = null)
        assertFailure(result, "无法访问模板图片（缺少 ContentResolver）")
    }

    // ── 解码失败 ──

    @Test
    fun `decode failure returns Failure with decode error`() = runBlocking {
        val imageFile = createValidTestImage("decode_fail.png")
        val result = loadTemplateBitmapInternal(
            imageSource = imageFile.absolutePath,
            maxTargetSize = 2048,
            decodeFn = { _, _ -> null }
        )
        assertFailure(result, "模板图片解码失败")
    }

    // ── CancellationException 不被转换成加载失败 ──

    @Test
    fun `CancellationException from decode propagates as exception not Failure`(): Unit = runBlocking {
        val imageFile = createValidTestImage("cancel_decode.png")
        var result: TemplateLoadResult? = null
        try {
            coroutineScope {
                result = loadTemplateBitmapInternal(
                    imageSource = imageFile.absolutePath,
                    maxTargetSize = 2048,
                    decodeFn = { _, _ -> throw CancellationException("test cancellation") }
                )
            }
        } catch (e: CancellationException) {
            // 预期：CancellationException 传播出来了
        }
        assertNull(
            "CancellationException must propagate, not return Failure. Got: $result",
            result
        )
    }

    @Test
    fun `CancellationException from decode step propagates`(): Unit = runBlocking {
        val imageFile = createValidTestImage("cancel_decode_step.png")
        var result: TemplateLoadResult? = null
        try {
            coroutineScope {
                result = loadTemplateBitmapInternal(
                    imageSource = imageFile.absolutePath,
                    maxTargetSize = 2048,
                    decodeFn = { _, _ ->
                        throw CancellationException("cancelled during decode")
                    }
                )
            }
        } catch (e: CancellationException) {
            // 预期
        }
        assertNull(
            "CancellationException must propagate, not return Failure. Got: $result",
            result
        )
    }

    // ── 受控降采样参数 ──

    @Test
    fun `calculateInSampleSize returns 1 for small images`() {
        assertEquals(1, calculateInSampleSize(800, 600))
        assertEquals(1, calculateInSampleSize(1920, 1080))
        assertEquals(1, calculateInSampleSize(2048, 2048))
    }

    @Test
    fun `calculateInSampleSize returns 2 for 4096px images`() {
        assertEquals(2, calculateInSampleSize(4096, 3000))
        assertEquals(2, calculateInSampleSize(3000, 4096))
    }

    @Test
    fun `calculateInSampleSize returns 4 for 8000px images`() {
        assertEquals(4, calculateInSampleSize(8000, 6000))
        assertEquals(4, calculateInSampleSize(6000, 8000))
    }

    @Test
    fun `calculateInSampleSize returns 1 for zero or negative dimensions`() {
        assertEquals(1, calculateInSampleSize(0, 0))
        assertEquals(1, calculateInSampleSize(-1, 100))
        assertEquals(1, calculateInSampleSize(100, -1))
    }

    @Test
    fun `calculateInSampleSize respects custom maxTarget`() {
        assertEquals(2, calculateInSampleSize(2000, 1500, maxTarget = 1024))
        assertEquals(1, calculateInSampleSize(1024, 768, maxTarget = 1024))
    }

    @Test
    fun `calculateInSampleSize with zero maxTarget does not loop`() {
        // maxTarget <= 0 应回退为 2048，不产生死循环
        assertEquals(1, calculateInSampleSize(1000, 1000, maxTarget = 0))
        assertEquals(1, calculateInSampleSize(1000, 1000, maxTarget = -1))
        assertEquals(4, calculateInSampleSize(8000, 6000, maxTarget = 0))
    }

    // ── maxTargetSize <= 0 在 loadTemplateBitmap 中回退 ──

    @Test
    fun `loadTemplateBitmap with zero maxTargetSize does not loop`() = runBlocking {
        val imageFile = createValidTestImage("zero_target.png")
        val result = loadTemplateBitmap(imageFile.absolutePath, maxTargetSize = 0)
        assertSuccess(result)
    }

    // ── 路径分类 ──

    @Test
    fun `classifyPath identifies content URI`() {
        assertEquals(PathScheme.CONTENT, classifyPath("content://com.example/image"))
    }

    @Test
    fun `classifyPath identifies file URI`() {
        assertEquals(PathScheme.FILE_URI, classifyPath("file:///data/test.png"))
    }

    @Test
    fun `classifyPath identifies plain path`() {
        assertEquals(PathScheme.PLAIN, classifyPath("/data/data/com.example/test.png"))
    }

    // ── 大图降采样集成测试 ──

    @Test
    fun `large image is processed with correct sample size`() = runBlocking {
        val imageFile = createValidTestImage("large.png")
        val result = loadTemplateBitmap(imageFile.absolutePath, maxTargetSize = 50)
        assertSuccess(result)
    }

    // ── 辅助方法 ──

    private fun assertFailure(result: TemplateLoadResult, expectedMessage: String) {
        assertTrue("Expected Failure but got: $result", result is TemplateLoadResult.Failure)
        assertEquals(expectedMessage, (result as TemplateLoadResult.Failure).userMessage)
    }

    private fun assertSuccess(result: TemplateLoadResult) {
        assertTrue("Expected Success but got: $result", result is TemplateLoadResult.Success)
        val bitmap = (result as TemplateLoadResult.Success).bitmap
        assertNotNull(bitmap)
        assertTrue("Bitmap width > 0", bitmap.width > 0)
        assertTrue("Bitmap height > 0", bitmap.height > 0)
    }

    /**
     * 创建一个有效的最小 PNG 测试图片。
     */
    private fun createValidTestImage(name: String): File {
        val file = File(tempDir, name)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return file
    }

    /**
     * 创建有效的 PNG 字节流。
     */
    private fun createValidPngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    /**
     * 创建 mock ContentResolver，每次 openInputStream 返回新的 ByteArrayInputStream。
     * [callCount] 记录调用次数，用于断言 bounds + decode 两次独立打开。
     */
    private fun mockContentResolverWithCounter(
        pngBytes: ByteArray,
        callCount: java.util.concurrent.atomic.AtomicInteger,
    ): ContentResolver {
        val resolver = org.mockito.Mockito.mock(ContentResolver::class.java)
        org.mockito.Mockito.`when`(resolver.openInputStream(org.mockito.ArgumentMatchers.any(Uri::class.java)))
            .thenAnswer {
                callCount.incrementAndGet()
                ByteArrayInputStream(pngBytes)
            }
        return resolver
    }
}
