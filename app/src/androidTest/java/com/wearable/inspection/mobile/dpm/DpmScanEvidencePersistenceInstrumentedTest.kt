package com.wearable.inspection.mobile.dpm

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wearable.inspection.mobile.data.db.AppDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DpmScanEvidencePersistenceInstrumentedTest {

    @Test
    fun noEccSuccessCreatesNoFileAndNoDatabaseRow() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val sessionId = "no-ecc-${System.nanoTime()}"
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val viewModel = DpmScanViewModel(app)

        viewModel.saveEvidence(
            sessionId,
            DpmFrameAnalyzer.EvidenceFrames(
                bitmap = bitmap,
                roi = Rect(1, 1, 7, 7),
                isDecodeSuccess = false,
                decodedCode = null,
                decodeSource = null,
                frameToken = 1L,
                frameTimeMs = 10L,
            ),
        )

        assertTrue(bitmap.isRecycled)
        assertTrue(AppDatabase.get(app).dpmScanEvidenceDao().getBySessionId(sessionId).isEmpty())
        assertFalse(File(app.filesDir, "dpm_evidence").listFiles().orEmpty().any { it.name.contains(sessionId.take(8)) })
    }

    @Test
    fun successPersistsExactSourceFrameAndRoiCrop() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val sessionId = "ecc-${System.nanoTime()}"
        val bitmap = Bitmap.createBitmap(6, 5, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        for (y in 1..3) {
            for (x in 1..4) bitmap.setPixel(x, y, Color.RED)
        }
        val viewModel = DpmScanViewModel(app)

        viewModel.saveEvidence(
            sessionId,
            DpmFrameAnalyzer.EvidenceFrames(
                bitmap = bitmap,
                roi = Rect(1, 1, 5, 4),
                isDecodeSuccess = true,
                decodedCode = "ECC-CODE",
                decodeSource = DecodeSource.ZXING,
                frameToken = 7L,
                frameTimeMs = 123456L,
            ),
        )

        val row = AppDatabase.get(app).dpmScanEvidenceDao().getBySessionId(sessionId).single()
        assertEquals("SUCCESS", row.status)
        assertEquals("ECC-CODE", row.decodedContent)
        assertEquals("ZXING", row.decodeSource)
        assertEquals(123456L, row.frameTimeMs)
        val original = android.graphics.BitmapFactory.decodeFile(row.originalImagePath)
        val roi = android.graphics.BitmapFactory.decodeFile(row.roiImagePath)
        assertEquals(6, original.width)
        assertEquals(5, original.height)
        assertEquals(4, roi.width)
        assertEquals(3, roi.height)
        assertColorNear(Color.RED, original.getPixel(3, 2))
        assertColorNear(Color.RED, roi.getPixel(2, 1))
        original.recycle()
        roi.recycle()
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun writeFailureLeavesNoDatabaseRowOrOrphanImage() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val sessionId = "write-fail-${System.nanoTime()}"
        val bitmap = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        bitmap.recycle()

        DpmScanViewModel(app).saveEvidence(
            sessionId,
            DpmFrameAnalyzer.EvidenceFrames(
                bitmap = bitmap,
                roi = null,
                isDecodeSuccess = true,
                decodedCode = "ECC-CODE",
                decodeSource = DecodeSource.GRID,
            ),
        )

        assertTrue(AppDatabase.get(app).dpmScanEvidenceDao().getBySessionId(sessionId).isEmpty())
        assertFalse(File(app.filesDir, "dpm_evidence").listFiles().orEmpty().any { it.name.contains(sessionId.take(8)) })
    }

    private fun assertColorNear(expected: Int, actual: Int) {
        val distance = kotlin.math.abs(Color.red(expected) - Color.red(actual)) +
            kotlin.math.abs(Color.green(expected) - Color.green(actual)) +
            kotlin.math.abs(Color.blue(expected) - Color.blue(actual))
        assertTrue(distance < 260)
        assertTrue(Color.red(actual) > Color.green(actual) + 50)
        assertTrue(Color.red(actual) > Color.blue(actual) + 50)
    }
}
