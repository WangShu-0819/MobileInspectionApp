package com.wearable.inspection.mobile.dpm

import android.graphics.Bitmap
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DpmEvidenceFrameTrackerTest {

    @Test
    fun `zxing success retains exact source frame after later frames`() {
        val tracker = DpmEvidenceFrameTracker()
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val later = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        tracker.addFrame(11L, 100L, source, Rect(1, 1, 3, 3))
        assertTrue(tracker.recordDecodeSuccess(success(DecodeSource.ZXING, 11L, 100L)))
        tracker.addFrame(12L, 200L, later, null)

        val snapshot = tracker.freeze()
        assertEquals(11L, snapshot?.frameToken)
        assertEquals(100L, snapshot?.frameTimeMs)
        assertEquals(DecodeSource.ZXING, snapshot?.decodeSource)
        assertTrue(snapshot?.isDecodeSuccess == true)
        assertTrue(later.isRecycled)
        snapshot?.bitmap?.recycle()
    }

    @Test
    fun `ml kit success retains its source frame`() {
        val tracker = DpmEvidenceFrameTracker()
        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        tracker.addFrame(21L, 300L, source, null)
        assertTrue(tracker.recordDecodeSuccess(success(DecodeSource.ML_KIT, 21L, 300L)))

        val snapshot = tracker.freeze()
        assertEquals(21L, snapshot?.frameToken)
        assertEquals(DecodeSource.ML_KIT, snapshot?.decodeSource)
        snapshot?.bitmap?.recycle()
    }

    @Test
    fun `grid success uses submitted source frame rather than latest frame`() {
        val tracker = DpmEvidenceFrameTracker()
        val gridSource = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        val latest = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        tracker.addFrame(31L, 400L, gridSource, Rect(0, 0, 2, 2))
        tracker.markGridSubmitted(31L)
        tracker.addFrame(32L, 500L, latest, null)
        assertTrue(tracker.recordDecodeSuccess(success(DecodeSource.GRID, 31L, 400L)))
        tracker.markGridFinished(31L)

        val snapshot = tracker.freeze()
        assertEquals(31L, snapshot?.frameToken)
        assertEquals(DecodeSource.GRID, snapshot?.decodeSource)
        assertTrue(latest.isRecycled)
        snapshot?.bitmap?.recycle()
    }

    @Test
    fun `no ECC success creates no snapshot and recycles frames`() {
        val tracker = DpmEvidenceFrameTracker()
        val frame = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        tracker.addFrame(41L, 600L, frame, null)
        tracker.markGridSubmitted(41L)
        tracker.markGridFinished(41L)

        assertNull(tracker.freeze())
        assertTrue(frame.isRecycled)
    }

    @Test
    fun `grid cancellation and late result after freeze cannot select a frame`() {
        val tracker = DpmEvidenceFrameTracker()
        val frame = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        tracker.addFrame(51L, 700L, frame, null)
        tracker.markGridSubmitted(51L)
        tracker.markGridFinished(51L)
        assertNull(tracker.freeze())
        assertFalse(tracker.recordDecodeSuccess(success(DecodeSource.GRID, 51L, 700L)))
        assertTrue(frame.isRecycled)
    }

    @Test
    fun `old tracker result cannot cover a new session`() {
        val old = DpmEvidenceFrameTracker()
        val oldFrame = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        old.addFrame(1L, 800L, oldFrame, null)
        assertNull(old.freeze())

        val current = DpmEvidenceFrameTracker()
        val currentFrame = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        current.addFrame(1L, 900L, currentFrame, null)
        assertTrue(current.recordDecodeSuccess(success(DecodeSource.ZXING, 1L, 900L)))
        assertEquals(900L, current.freeze()?.frameTimeMs)
        assertTrue(oldFrame.isRecycled)
        currentFrame.recycle()
    }

    @Test
    fun `explicit source token never falls back to latest frame`() {
        val tracker = DpmEvidenceFrameTracker()
        val frame = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        tracker.addFrame(61L, 1000L, frame, null)
        assertFalse(tracker.recordDecodeSuccess(success(DecodeSource.ZXING, 999L, 1000L)))
        assertNull(tracker.freeze())
        assertTrue(frame.isRecycled)
    }

    private fun success(source: DecodeSource, token: Long, timeMs: Long) = DpmAnalyzeResult(
        status = DpmAnalyzeStatus.DECODED,
        code = "DM-OK",
        source = source,
        sourceFrameToken = token,
        sourceFrameTimeMs = timeMs,
    )
}
