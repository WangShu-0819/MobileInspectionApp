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
 * 解码顺序：ZXing 主解码 → ML Kit 兜底。
 * 覆盖：
 * 1. ZXing 成功时不调用 ML Kit
 * 2. ZXing 无结果时调用 ML Kit
 * 3. ZXing 异常时调用 ML Kit
 * 4. ZXing 空白结果时调用 ML Kit
 * 5. ML Kit 成功返回 ML_KIT 来源
 * 6. 双失败返回 null
 * 7. ML Kit 异常不导致管线崩溃
 * 8. 非 DATA_MATRIX 结果不能通过
 */
class DpmDecodePipelineTest {

    private lateinit var zxing: StubZxingDecoder
    private lateinit var mlKit: StubMlKitDecoder
    private lateinit var pipeline: DpmDecodePipeline
    private lateinit var dummyBitmap: Bitmap

    @Before
    fun setUp() {
        zxing = StubZxingDecoder()
        mlKit = StubMlKitDecoder()
        pipeline = DpmDecodePipeline(zxing, mlKit)
        dummyBitmap = Mockito.mock(Bitmap::class.java)
    }

    @Test
    fun `ZXing 成功时不调用 ML Kit`() = runBlocking {
        zxing.result = makeResult("CODE_001", DecodeSource.ZXING)

        val result = pipeline.decode(dummyBitmap)

        assertNotNull("应返回结果", result)
        assertEquals("CODE_001", result!!.rawValue)
        assertEquals(DecodeSource.ZXING, result.source)
        assertFalse("ZXing 成功时不应调用 ML Kit", mlKit.wasCalled)
    }

    @Test
    fun `ZXing 无结果时调用 ML Kit`() = runBlocking {
        zxing.result = null
        mlKit.result = makeResult("CODE_002", DecodeSource.ML_KIT)

        val result = pipeline.decode(dummyBitmap)

        assertNotNull("应返回 ML Kit 结果", result)
        assertEquals("CODE_002", result!!.rawValue)
        assertEquals(DecodeSource.ML_KIT, result.source)
        assertTrue("ZXing 无结果时应调用 ML Kit", mlKit.wasCalled)
    }

    @Test
    fun `ZXing 异常时调用 ML Kit`() = runBlocking {
        zxing.shouldThrow = true
        mlKit.result = makeResult("CODE_003", DecodeSource.ML_KIT)

        val result = pipeline.decode(dummyBitmap)

        assertNotNull("ZXing 异常时应降级到 ML Kit", result)
        assertEquals("CODE_003", result!!.rawValue)
        assertEquals(DecodeSource.ML_KIT, result.source)
        assertTrue("ZXing 异常时应调用 ML Kit", mlKit.wasCalled)
    }

    @Test
    fun `ZXing 空白结果时调用 ML Kit`() = runBlocking {
        zxing.result = DpmScanResult(
            rawValue = "   ",
            format = BarcodeFormat.DATA_MATRIX,
            timestampMs = 1000L,
            source = DecodeSource.ZXING
        )
        mlKit.result = makeResult("CODE_004", DecodeSource.ML_KIT)

        val result = pipeline.decode(dummyBitmap)

        assertNotNull("ZXing 空白时应降级到 ML Kit", result)
        assertEquals("CODE_004", result!!.rawValue)
        assertTrue("ZXing 空白时应调用 ML Kit", mlKit.wasCalled)
    }

    @Test
    fun `ML Kit 成功返回 ML_KIT 来源`() = runBlocking {
        zxing.result = null
        mlKit.result = makeResult("CODE_005", DecodeSource.ML_KIT)

        val result = pipeline.decode(dummyBitmap)

        assertNotNull("应返回结果", result)
        assertEquals(DecodeSource.ML_KIT, result!!.source)
    }

    @Test
    fun `双失败返回 null`() = runBlocking {
        zxing.result = null
        mlKit.result = null

        val result = pipeline.decode(dummyBitmap)

        assertNull("双失败应返回 null", result)
    }

    @Test
    fun `ML Kit 异常不导致管线崩溃`() = runBlocking {
        zxing.result = null
        mlKit.shouldThrow = true

        val result = pipeline.decode(dummyBitmap)

        assertNull("双异常应返回 null 且不崩溃", result)
    }

    @Test
    fun `非 DATA_MATRIX 结果不能通过`() = runBlocking {
        // ZXing 和 ML Kit 都返回空白
        zxing.result = DpmScanResult(
            rawValue = "",
            format = BarcodeFormat.DATA_MATRIX,
            timestampMs = 1000L,
            source = DecodeSource.ZXING
        )
        mlKit.result = DpmScanResult(
            rawValue = "",
            format = BarcodeFormat.DATA_MATRIX,
            timestampMs = 1000L,
            source = DecodeSource.ML_KIT
        )

        val result = pipeline.decode(dummyBitmap)

        assertNull("空白结果应被拒绝", result)
    }

    // ─── 辅助 ───

    private fun makeResult(value: String, source: DecodeSource) = DpmScanResult(
        rawValue = value,
        format = BarcodeFormat.DATA_MATRIX,
        timestampMs = System.currentTimeMillis(),
        source = source
    )

    // ─── 测试替身 ───

    private class StubZxingDecoder : DpmZxingDecoder {
        var result: DpmScanResult? = null
        var shouldThrow: Boolean = false

        override suspend fun decode(bitmap: Bitmap): DpmScanResult? {
            if (shouldThrow) throw RuntimeException("ZXing 测试异常")
            return result
        }
    }

    private class StubMlKitDecoder : DpmMlKitDecoder {
        var result: DpmScanResult? = null
        var shouldThrow: Boolean = false
        var wasCalled: Boolean = false

        override suspend fun decode(bitmap: Bitmap): DpmScanResult? {
            wasCalled = true
            if (shouldThrow) throw RuntimeException("ML Kit 测试异常")
            return result
        }
    }
}
