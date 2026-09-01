package com.wearable.inspection.mobile.dpm

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.datamatrix.DataMatrixWriter
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZxingDataMatrixDecoderTest {

    private val decoder = ZxingDataMatrixDecoder()

    @Test
    fun `decodes valid DataMatrix barcode`() = runTest {
        val text = "DM-PARITY-TEST-001"
        val bitmap = generateDataMatrixBitmap(text)
        val result = decoder.decode(bitmap)
        assertNotNull("Should decode valid DataMatrix", result)
        assertEquals(text, result!!.rawValue)
        assertEquals(com.wearable.inspection.mobile.dpm.BarcodeFormat.DATA_MATRIX, result.format)
        assertEquals(com.wearable.inspection.mobile.dpm.DecodeSource.ZXING, result.source)
    }

    @Test
    fun `decodes longer alphanumeric DataMatrix`() = runTest {
        val text = "HELLO-WORLD-DM-2026"
        val bitmap = generateDataMatrixBitmap(text)
        val result = decoder.decode(bitmap)
        assertNotNull(result)
        assertEquals(text, result!!.rawValue)
    }

    @Test
    fun `returns null for blank bitmap`() = runTest {
        val blank = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
        }
        val result = decoder.decode(blank)
        assertNull("Should return null for blank bitmap", result)
    }

    @Test
    fun `returns null for random noise`() = runTest {
        val noise = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(200 * 200)
        for (i in pixels.indices) {
            pixels[i] = if (i % 3 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        noise.setPixels(pixels, 0, 200, 0, 0, 200, 200)
        val result = decoder.decode(noise)
        assertNull("Should return null for noise", result)
    }

    companion object {
        fun generateDataMatrixBitmap(text: String, size: Int = 300): Bitmap {
            val writer = DataMatrixWriter()
            val bitMatrix: BitMatrix = writer.encode(text, com.google.zxing.BarcodeFormat.DATA_MATRIX, size, size)
            val w = bitMatrix.width
            val h = bitMatrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    pixels[y * w + x] = if (bitMatrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
            }
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        }
    }
}
