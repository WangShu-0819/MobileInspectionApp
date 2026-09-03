package com.wearable.inspection.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CameraPreview 辅助函数单元测试
 */
class CameraPreviewTest {

    @Test
    fun `8000x6000 图片计算 inSampleSize=4`() {
        assertEquals(4, calculateInSampleSize(8000, 6000))
    }

    @Test
    fun `6000x8000 纵向图片计算 inSampleSize=4`() {
        assertEquals(4, calculateInSampleSize(6000, 8000))
    }

    @Test
    fun `2048x2048 图片计算 inSampleSize=1`() {
        assertEquals(1, calculateInSampleSize(2048, 2048))
    }

    @Test
    fun `1920x1080 图片计算 inSampleSize=1`() {
        assertEquals(1, calculateInSampleSize(1920, 1080))
    }

    @Test
    fun `1080x1920 纵向图片计算 inSampleSize=1`() {
        assertEquals(1, calculateInSampleSize(1080, 1920))
    }

    @Test
    fun `4000x3000 图片计算 inSampleSize=2`() {
        assertEquals(2, calculateInSampleSize(4000, 3000))
    }

    @Test
    fun `3000x4000 纵向图片计算 inSampleSize=2`() {
        assertEquals(2, calculateInSampleSize(3000, 4000))
    }

    @Test
    fun `4096x4096 图片计算 inSampleSize=2`() {
        assertEquals(2, calculateInSampleSize(4096, 4096))
    }

    @Test
    fun `2049x2049 图片计算 inSampleSize=2`() {
        assertEquals(2, calculateInSampleSize(2049, 2049))
    }

    @Test
    fun `零宽度图片返回 inSampleSize=1`() {
        assertEquals(1, calculateInSampleSize(0, 6000))
    }

    @Test
    fun `零高度图片返回 inSampleSize=1`() {
        assertEquals(1, calculateInSampleSize(8000, 0))
    }

    @Test
    fun `负尺寸图片返回 inSampleSize=1`() {
        assertEquals(1, calculateInSampleSize(-1, -1))
    }

    @Test
    fun `自定义 maxTarget=1024 时 4000x3000 计算 inSampleSize=4`() {
        assertEquals(4, calculateInSampleSize(4000, 3000, maxTarget = 1024))
    }

    @Test
    fun `自定义 maxTarget=1024 时 2000x1500 计算 inSampleSize=2`() {
        assertEquals(2, calculateInSampleSize(2000, 1500, maxTarget = 1024))
    }
}
