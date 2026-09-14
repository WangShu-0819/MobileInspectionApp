package com.wearable.inspection.mobile.ui.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoiExifMapperTest {
    @Test
    fun `saved EXIF rotation defines upright dimensions and crops in upright space`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val photo = File(app.cacheDir, "roi-exif-${System.nanoTime()}.jpg")
        Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888).useBitmap { bitmap ->
            photo.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
            }
        }
        ExifInterface(photo.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val geometry = RoiCoordinateMapper.getImageGeometry(photo.absolutePath)!!
        assertEquals(2, geometry.rawWidth)
        assertEquals(3, geometry.rawHeight)
        assertEquals(3, geometry.width)
        assertEquals(2, geometry.height)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, geometry.exifOrientation)

        val crop = RoiCoordinateMapper.cropRoiBitmap(
            photo.absolutePath,
            ContentRectBounds(0, 0, geometry.width, geometry.height)
        )!!
        try {
            assertEquals(3, crop.width)
            assertEquals(2, crop.height)
        } finally {
            crop.recycle()
            photo.delete()
        }
    }

    private inline fun <T : Bitmap, R> T.useBitmap(block: (T) -> R): R = try {
        block(this)
    } finally {
        recycle()
    }
}
