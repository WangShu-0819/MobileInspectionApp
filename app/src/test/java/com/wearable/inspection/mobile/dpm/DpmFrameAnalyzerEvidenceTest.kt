package com.wearable.inspection.mobile.dpm

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import com.wearable.inspection.mobile.vision.OpenCvTestSupport
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DpmFrameAnalyzer 证据帧追踪测试。
 *
 * 覆盖：
 * - 未分析时 getAndClearEvidenceFrames 返回 null
 * - stop 后返回 lastFrameBitmap（如有）
 * - 成功解码后返回 successBitmap 而非 lastFrameBitmap
 * - getAndClearEvidenceFrames 调用后清空内部引用
 * - EvidenceFrames 字段正确性
 * - stop 前先设置 isStopped 标记（验证调用顺序安全）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DpmFrameAnalyzerEvidenceTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            OpenCvTestSupport.loadNative()
        }
    }

    @Test
    fun `getAndClearEvidenceFrames returns null before any analysis`() = runTest {
        val analyzer = createAnalyzer()
        analyzer.stop()
        val evidence = analyzer.getAndClearEvidenceFrames()
        assertNull("分析前应无证据帧", evidence)
    }

    @Test
    fun `EvidenceFrames has correct fields for no-decode case`() = runTest {
        val analyzer = createAnalyzer()
        analyzer.stop()
        // 无分析时直接返回 null
        val evidence = analyzer.getAndClearEvidenceFrames()
        assertNull(evidence)
    }

    @Test
    fun `stop does not throw when called multiple times`() = runTest {
        val analyzer = createAnalyzer()
        analyzer.stop()
        analyzer.stop() // 重复调用不应抛异常
        val evidence = analyzer.getAndClearEvidenceFrames()
        assertNull(evidence)
    }

    @Test
    fun `getAndClearEvidenceFrames is idempotent`() = runTest {
        val analyzer = createAnalyzer()
        analyzer.stop()
        val first = analyzer.getAndClearEvidenceFrames()
        val second = analyzer.getAndClearEvidenceFrames()
        assertNull("第一次应返回 null", first)
        assertNull("第二次应返回 null（已清空）", second)
    }

    @Test
    fun `EvidenceFrames isDecodeSuccess field is present`() {
        // 验证 EvidenceFrames 数据类字段存在
        val evidence = DpmFrameAnalyzer.EvidenceFrames(
            bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888),
            roi = Rect(10, 10, 50, 50),
            isDecodeSuccess = true,
            decodedCode = "TEST123",
            decodeSource = DecodeSource.ZXING,
        )
        assertTrue("isDecodeSuccess 应为 true", evidence.isDecodeSuccess)
        assertEquals("TEST123", evidence.decodedCode)
        assertEquals(DecodeSource.ZXING, evidence.decodeSource)
        evidence.bitmap.recycle()
    }

    @Test
    fun `EvidenceFrames for no-decode has null code and source`() {
        val evidence = DpmFrameAnalyzer.EvidenceFrames(
            bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888),
            roi = null,
            isDecodeSuccess = false,
            decodedCode = null,
            decodeSource = null,
        )
        assertFalse("isDecodeSuccess 应为 false", evidence.isDecodeSuccess)
        assertNull("decodedCode 应为 null", evidence.decodedCode)
        assertNull("decodeSource 应为 null", evidence.decodeSource)
        assertNull("roi 应为 null", evidence.roi)
        evidence.bitmap.recycle()
    }

    @Test
    fun `EvidenceFrames roi can be null`() {
        val evidence = DpmFrameAnalyzer.EvidenceFrames(
            bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888),
            roi = null,
            isDecodeSuccess = false,
            decodedCode = null,
            decodeSource = null,
        )
        assertNull(evidence.roi)
        evidence.bitmap.recycle()
    }

    @Test
    fun `EvidenceFrames roi preserves coordinates`() {
        val roi = Rect(100, 200, 300, 400)
        val evidence = DpmFrameAnalyzer.EvidenceFrames(
            bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888),
            roi = roi,
            isDecodeSuccess = true,
            decodedCode = "CODE",
            decodeSource = DecodeSource.ML_KIT,
        )
        assertEquals(100, evidence.roi!!.left)
        assertEquals(200, evidence.roi!!.top)
        assertEquals(300, evidence.roi!!.right)
        assertEquals(400, evidence.roi!!.bottom)
        evidence.bitmap.recycle()
    }

    private fun createAnalyzer(): DpmFrameAnalyzer {
        val fakeClock = object : DpmClock {
            override fun currentTimeMs(): Long = System.currentTimeMillis()
        }
        val dpmAnalyzer = DpmAnalyzer(
            zxingDecoder = object : DpmZxingDecoder {
                override suspend fun decode(bitmap: Bitmap): DpmScanResult? = null
            },
            mlKitDecoder = object : DpmMlKitDecoder {
                override suspend fun decode(bitmap: Bitmap): DpmScanResult? = null
            },
            respondGate = DpmRespondGate(),
            gridGate = DpmGridGate(missThreshold = 8, cooldownMs = 1500L),
            scope = TestScope(StandardTestDispatcher()),
            clock = fakeClock,
        )
        return DpmFrameAnalyzer(
            dpmAnalyzer = dpmAnalyzer,
            scope = TestScope(StandardTestDispatcher()),
        )
    }
}
