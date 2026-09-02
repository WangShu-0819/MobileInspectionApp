package com.wearable.inspection.mobile.dpm

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DpmScanRoiMapperTest {

    @Test
    fun `maps centered frame to bitmap coordinates`() {
        // contentRect: 100,0,700,800 (image fills this area in the view)
        val contentRect = Rect(100, 0, 700, 800)
        // screenRect: centered 60% frame in a 800x800 view
        // frameW=480, frameH=480, left=160, top=160
        val screenRect = Rect(160, 160, 640, 640)
        val bitmapW = 1080
        val bitmapH = 1920

        val roi = DpmScanRoiMapper.mapToBitmap(screenRect, contentRect, bitmapW, bitmapH)
        assertNotNull(roi)
        // imageLeft = 160-100 = 60, imageRight = 640-100 = 540
        // imageTop = 160-0 = 160, imageBottom = 640-0 = 640
        // scaleX = 1080/600 = 1.8, scaleY = 1920/800 = 2.4
        // bitmapLeft = 60*1.8 = 108, bitmapRight = 540*1.8 = 972
        // bitmapTop = 160*2.4 = 384, bitmapBottom = 640*2.4 = 1536
        assertEquals(Rect(108, 384, 972, 1536), roi)
    }

    @Test
    fun `returns null when frame has no intersection with contentRect`() {
        val contentRect = Rect(100, 0, 700, 800)
        // Frame entirely in letterbox area (left of content)
        val screenRect = Rect(0, 0, 50, 50)
        val roi = DpmScanRoiMapper.mapToBitmap(screenRect, contentRect, 1080, 1920)
        assertNull("Should return null for no intersection", roi)
    }

    @Test
    fun `returns null for empty inputs`() {
        assertNull(DpmScanRoiMapper.mapToBitmap(Rect(), Rect(0, 0, 100, 100), 100, 100))
        assertNull(DpmScanRoiMapper.mapToBitmap(Rect(0, 0, 50, 50), Rect(), 100, 100))
        assertNull(DpmScanRoiMapper.mapToBitmap(Rect(0, 0, 50, 50), Rect(0, 0, 100, 100), 0, 100))
    }

    @Test
    fun `handles rotation 90 - swapped dimensions`() {
        // After 90° rotation: stream 1920x1080 → bitmap 1080x1920
        // contentRect in a 1080x1920 view with 1080x1920 bitmap = no letterbox
        val contentRect = Rect(0, 0, 1080, 1920)
        val screenRect = Rect(324, 576, 756, 1344) // 60% centered
        val bitmapW = 1080
        val bitmapH = 1920

        val roi = DpmScanRoiMapper.mapToBitmap(screenRect, contentRect, bitmapW, bitmapH)
        assertNotNull(roi)
        // No letterbox, direct scale: scaleX=1, scaleY=1
        assertEquals(Rect(324, 576, 756, 1344), roi)
    }

    @Test
    fun `handles rotation 180`() {
        val contentRect = Rect(0, 0, 1080, 1920)
        val screenRect = Rect(216, 384, 864, 1536)
        val roi = DpmScanRoiMapper.mapToBitmap(screenRect, contentRect, 1080, 1920)
        assertNotNull(roi)
        assertEquals(Rect(216, 384, 864, 1536), roi)
    }

    @Test
    fun `handles rotation 270`() {
        val contentRect = Rect(0, 0, 1080, 1920)
        val screenRect = Rect(324, 576, 756, 1344)
        val roi = DpmScanRoiMapper.mapToBitmap(screenRect, contentRect, 1080, 1920)
        assertNotNull(roi)
        assertEquals(Rect(324, 576, 756, 1344), roi)
    }

    @Test
    fun `handles FIT_CENTER letterbox`() {
        // View is 1080x1920, but stream is 4:3 → letterbox on sides
        // Rotated stream: 1440x1920, view: 1080x1920
        // scale = min(1080/1440, 1920/1920) = 0.75
        // contentW = 1440*0.75 = 1080, contentH = 1920*0.75 = 1440
        // Actually contentH = 1920*0.75 = 1440, but viewH = 1920
        // left = (1080-1080)/2 = 0, top = (1920-1440)/2 = 240
        val contentRect = Rect(0, 240, 1080, 1680) // letterbox top/bottom
        val screenRect = Rect(324, 672, 756, 1248) // 60% centered in view
        val bitmapW = 1440
        val bitmapH = 1920

        val roi = DpmScanRoiMapper.mapToBitmap(screenRect, contentRect, bitmapW, bitmapH)
        assertNotNull(roi)
        // imageTop = 672-240 = 432, imageBottom = 1248-240 = 1008
        // scaleY = 1920/1440 = 1.333
        // bitmapTop = 432*1.333 = 576, bitmapBottom = 1008*1.333 = 1344
        // scaleX = 1440/1080 = 1.333
        // imageLeft = 324-0 = 324, imageRight = 756-0 = 756
        // bitmapLeft = 324*1.333 = 432, bitmapRight = 756*1.333 = 1008
        assertEquals(Rect(432, 576, 1008, 1344), roi)
    }

    @Test
    fun `clamps to bitmap boundaries`() {
        val contentRect = Rect(0, 0, 100, 100)
        // Frame extends beyond contentRect
        val screenRect = Rect(-50, -50, 150, 150)
        val roi = DpmScanRoiMapper.mapToBitmap(screenRect, contentRect, 200, 200)
        assertNotNull(roi)
        // Clamped intersection: 0,0,100,100 in content space
        // scaleX=200/100=2, scaleY=200/100=2
        assertEquals(Rect(0, 0, 200, 200), roi)
    }

    @Test
    fun `handles size change`() {
        // Initial: 1080x1920 view
        val contentRect1 = Rect(0, 0, 1080, 1920)
        val screenRect1 = Rect(324, 576, 756, 1344)
        val roi1 = DpmScanRoiMapper.mapToBitmap(screenRect1, contentRect1, 1080, 1920)
        assertNotNull(roi1)

        // After rotation: 1920x1080 view
        val contentRect2 = Rect(0, 0, 1920, 1080)
        val screenRect2 = Rect(576, 324, 1344, 756)
        val roi2 = DpmScanRoiMapper.mapToBitmap(screenRect2, contentRect2, 1920, 1080)
        assertNotNull(roi2)
    }
}
