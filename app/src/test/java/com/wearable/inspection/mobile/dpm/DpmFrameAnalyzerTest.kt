package com.wearable.inspection.mobile.dpm

import android.app.Application
import android.graphics.Bitmap
import com.wearable.inspection.mobile.camera.FrameAnalyzer
import com.wearable.inspection.mobile.vision.OpenCvTestSupport
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DpmFrameAnalyzerTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            OpenCvTestSupport.loadNative()
        }
    }

    @Test
    fun `implements FrameAnalyzer interface`() {
        val analyzer = createAnalyzer()
        assertTrue("Should implement FrameAnalyzer", analyzer is FrameAnalyzer)
    }

    @Test
    fun `stop does not throw`() = runTest {
        val analyzer = createAnalyzer()
        analyzer.stop() // Should not throw
    }

    @Test
    fun `results flow is accessible`() = runTest {
        val analyzer = createAnalyzer()
        assertNotNull(analyzer.results)
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
            gridGate = DpmGridGate(missThreshold = 5, cooldownMs = 3000L),
            scope = TestScope(StandardTestDispatcher()),
            clock = fakeClock,
        )
        return DpmFrameAnalyzer(
            dpmAnalyzer = dpmAnalyzer,
            scope = TestScope(StandardTestDispatcher()),
        )
    }
}
