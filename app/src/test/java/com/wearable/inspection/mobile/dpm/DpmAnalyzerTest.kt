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
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DpmAnalyzerTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            OpenCvTestSupport.loadNative()
        }
    }

    private lateinit var fakeZxing: FakeZxingDecoder
    private lateinit var fakeMlKit: FakeMlKitDecoder
    private lateinit var respondGate: DpmRespondGate
    private lateinit var gridGate: DpmGridGate
    private lateinit var analyzer: DpmAnalyzer
    private lateinit var fakeClock: FakeDpmClock
    private var refocusCount = 0
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @org.junit.Before
    fun setup() {
        fakeZxing = FakeZxingDecoder()
        fakeMlKit = FakeMlKitDecoder()
        respondGate = DpmRespondGate()
        gridGate = DpmGridGate(missThreshold = 5, cooldownMs = 3000L)
        fakeClock = FakeDpmClock()
        refocusCount = 0
        analyzer = DpmAnalyzer(
            zxingDecoder = fakeZxing,
            mlKitDecoder = fakeMlKit,
            respondGate = respondGate,
            gridGate = gridGate,
            scope = testScope,
            clock = fakeClock,
            onLensRefocusNeeded = { refocusCount++ },
        )
    }

    @Test
    fun `decodes via zxing primary`() = testScope.runTest {
        fakeZxing.result = DpmScanResult("CODE-1", com.wearable.inspection.mobile.dpm.BarcodeFormat.DATA_MATRIX, timestampMs = 0L, source = com.wearable.inspection.mobile.dpm.DecodeSource.ZXING)
        val frame = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val result = analyzer.analyze(frame, frameRotation = 0)
        assertEquals(DpmAnalyzeStatus.DECODED, result.status)
        assertEquals("CODE-1", result.code)
    }

    @Test
    fun `falls back to mlkit when zxing returns null`() = testScope.runTest {
        fakeZxing.result = null
        fakeMlKit.result = DpmScanResult("CODE-ML", com.wearable.inspection.mobile.dpm.BarcodeFormat.DATA_MATRIX, timestampMs = 0L, source = com.wearable.inspection.mobile.dpm.DecodeSource.ML_KIT)
        val frame = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val result = analyzer.analyze(frame, frameRotation = 0)
        assertEquals(DpmAnalyzeStatus.DECODED, result.status)
        assertEquals("CODE-ML", result.code)
    }

    @Test
    fun `returns NO_CODE when both decoders fail`() = testScope.runTest {
        fakeZxing.result = null
        fakeMlKit.result = null
        val frame = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val result = analyzer.analyze(frame, frameRotation = 0)
        assertEquals(DpmAnalyzeStatus.NO_CODE, result.status)
    }

    @Test
    fun `deduplicates repeated code via respond gate`() = testScope.runTest {
        fakeZxing.result = DpmScanResult("SAME", com.wearable.inspection.mobile.dpm.BarcodeFormat.DATA_MATRIX, timestampMs = 0L, source = com.wearable.inspection.mobile.dpm.DecodeSource.ZXING)
        val frame = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val first = analyzer.analyze(frame, frameRotation = 0)
        assertEquals(DpmAnalyzeStatus.DECODED, first.status)
        // Advance past throttle and rearm gate
        fakeClock.advance(5000L)
        for (i in 1..10) respondGate.onMiss()
        val second = analyzer.analyze(frame, frameRotation = 0)
        assertEquals(DpmAnalyzeStatus.DECODED, second.status)
    }

    @Test
    fun `miss triggers autofocus callback after threshold`() = testScope.runTest {
        fakeZxing.result = null
        fakeMlKit.result = null
        val frame = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        for (i in 1..6) {
            fakeClock.advance(500L) // advance past throttle delay
            analyzer.analyze(frame, frameRotation = 0)
        }
        assertEquals("Should trigger refocus after miss threshold", 1, refocusCount)
    }

    @Test
    fun `skips analysis when out of focus`() = testScope.runTest {
        analyzer.onFrameFocusChanged(false)
        val frame = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val result = analyzer.analyze(frame, frameRotation = 0)
        assertEquals(DpmAnalyzeStatus.NO_CODE, result.status)
        assertEquals(0, fakeZxing.callCount)
    }

    @Test
    fun `reset clears state`() = testScope.runTest {
        fakeZxing.result = DpmScanResult("X", com.wearable.inspection.mobile.dpm.BarcodeFormat.DATA_MATRIX, timestampMs = 0L, source = com.wearable.inspection.mobile.dpm.DecodeSource.ZXING)
        analyzer.setMode(DpmAnalyzer.AnalysisMode.INSPECTION)
        analyzer.setScanModeActive(true)
        analyzer.resetForTest()
        val frame = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val result = analyzer.analyze(frame, frameRotation = 0)
        assertNotNull(result)
    }

    /** Fake clock for deterministic throttle testing */
    class FakeDpmClock(var nowMs: Long = 1000L) : DpmClock {
        override fun currentTimeMs(): Long = nowMs
        fun advance(ms: Long) { nowMs += ms }
    }

    class FakeZxingDecoder : DpmZxingDecoder {
        var result: DpmScanResult? = null
        var callCount = 0
        override suspend fun decode(bitmap: Bitmap): DpmScanResult? {
            callCount++
            return result
        }
    }

    class FakeMlKitDecoder : DpmMlKitDecoder {
        var result: DpmScanResult? = null
        var callCount = 0
        override suspend fun decode(bitmap: Bitmap): DpmScanResult? {
            callCount++
            return result
        }
    }
}
