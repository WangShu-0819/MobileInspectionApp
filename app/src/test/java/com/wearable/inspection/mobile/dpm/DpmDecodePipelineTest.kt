package com.wearable.inspection.mobile.dpm

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * DpmDecodePipeline 单元测试
 *
 * 覆盖：
 * 1. 主解码成功不调用兜底
 * 2. 主解码失败调用兜底
 * 3. 双失败返回 null
 * 4. 异常隔离（主解码异常 → 兜底仍被调用）
 * 5. 异常隔离（兜底异常 → 返回 null 不崩溃）
 * 6. 空白结果拒绝（rawValue.isBlank → 视为失败）
 */
class DpmDecodePipelineTest {

    private lateinit var primary: StubPrimaryDecoder
    private lateinit var fallback: StubFallbackDecoder
    private lateinit var pipeline: DpmDecodePipeline
    private lateinit var dummyBitmap: Bitmap

    @Before
    fun setUp() {
        primary = StubPrimaryDecoder()
        fallback = StubFallbackDecoder()
        pipeline = DpmDecodePipeline(primary, fallback)
        dummyBitmap = Mockito.mock(Bitmap::class.java)
    }

    @Test
    fun `主解码成功不调用兜底`() = runBlocking {
        primary.result = makeResult("CODE_001")

        val result = pipeline.decode(dummyBitmap)

        assertNotNull("应返回结果", result)
        assertEquals("CODE_001", result!!.rawValue)
        assertEquals(DecodeSource.ML_KIT, result.source)
        assertFalse("主解码成功时不应调用兜底", fallback.wasCalled)
    }

    @Test
    fun `主解码失败调用兜底`() = runBlocking {
        primary.result = null
        fallback.result = makeResult("CODE_002", DecodeSource.ZXING)

        val result = pipeline.decode(dummyBitmap)

        assertNotNull("应返回兜底结果", result)
        assertEquals("CODE_002", result!!.rawValue)
        assertEquals(DecodeSource.ZXING, result.source)
        assertTrue("主解码失败时应调用兜底", fallback.wasCalled)
    }

    @Test
    fun `双失败返回 null`() = runBlocking {
        primary.result = null
        fallback.result = null

        val result = pipeline.decode(dummyBitmap)

        assertNull("双失败应返回 null", result)
    }

    @Test
    fun `主解码异常不崩溃且调用兜底`() = runBlocking {
        primary.shouldThrow = true
        fallback.result = makeResult("CODE_003", DecodeSource.ZXING)

        val result = pipeline.decode(dummyBitmap)

        assertNotNull("主解码异常时应降级到兜底", result)
        assertEquals("CODE_003", result!!.rawValue)
        assertTrue("主解码异常时应调用兜底", fallback.wasCalled)
    }

    @Test
    fun `兜底异常不崩溃`() = runBlocking {
        primary.result = null
        fallback.shouldThrow = true

        val result = pipeline.decode(dummyBitmap)

        assertNull("双异常应返回 null", result)
    }

    @Test
    fun `空白结果被拒绝`() = runBlocking {
        // 主解码返回空白 rawValue
        primary.result = DpmScanResult(
            rawValue = "   ",
            format = BarcodeFormat.DATA_MATRIX,
            timestampMs = 1000L,
            source = DecodeSource.ML_KIT
        )
        fallback.result = null

        val result = pipeline.decode(dummyBitmap)

        assertNull("空白 rawValue 应视为失败", result)
    }

    @Test
    fun `主解码空白结果时调用兜底`() = runBlocking {
        primary.result = DpmScanResult(
            rawValue = "",
            format = BarcodeFormat.DATA_MATRIX,
            timestampMs = 1000L,
            source = DecodeSource.ML_KIT
        )
        fallback.result = makeResult("CODE_004", DecodeSource.ZXING)

        val result = pipeline.decode(dummyBitmap)

        assertNotNull("主解码空白时应降级到兜底", result)
        assertEquals("CODE_004", result!!.rawValue)
        assertTrue("主解码空白时应调用兜底", fallback.wasCalled)
    }

    // ─── 辅助 ───

    private fun makeResult(value: String, source: DecodeSource = DecodeSource.ML_KIT) = DpmScanResult(
        rawValue = value,
        format = BarcodeFormat.DATA_MATRIX,
        timestampMs = System.currentTimeMillis(),
        source = source
    )

    // ─── 测试替身 ───

    private class StubPrimaryDecoder : DpmPrimaryDecoder {
        var result: DpmScanResult? = null
        var shouldThrow: Boolean = false

        override suspend fun decode(bitmap: Bitmap): DpmScanResult? {
            if (shouldThrow) throw RuntimeException("主解码测试异常")
            return result
        }
    }

    private class StubFallbackDecoder : DpmFallbackDecoder {
        var result: DpmScanResult? = null
        var shouldThrow: Boolean = false
        var wasCalled: Boolean = false

        override suspend fun decode(bitmap: Bitmap): DpmScanResult? {
            wasCalled = true
            if (shouldThrow) throw RuntimeException("兜底解码测试异常")
            return result
        }
    }
}
