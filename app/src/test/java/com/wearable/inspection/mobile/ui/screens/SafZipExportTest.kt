package com.wearable.inspection.mobile.ui.screens

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.OutputStream

/** SAF ZIP 复制与 CreateDocument 失败清理的真实行为测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [SafContentResolverShadow::class])
class SafZipExportTest {

    @Test
    fun `copy rejects a null SAF output stream`() {
        SafContentResolverShadow.reset()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://test/empty")
        val source = Files.createTempFile("saf-source", ".zip").toFile().apply { writeBytes(byteArrayOf(1)) }
        SafContentResolverShadow.registerOutputStream(uri) { null }

        assertThrows(IllegalStateException::class.java) {
            copyZipToSafUri(context, uri, source)
        }

        source.delete()
    }

    @Test
    fun `copy verifies exact nonempty bytes written to SAF`() {
        SafContentResolverShadow.reset()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://test/zip")
        val bytes = byteArrayOf(5, 4, 3, 2)
        val sink = ByteArrayOutputStream()
        val source = Files.createTempFile("saf-source", ".zip").toFile().apply { writeBytes(bytes) }
        SafContentResolverShadow.registerOutputStream(uri) { sink }

        assertEquals(bytes.size.toLong(), copyZipToSafUri(context, uri, source))
        assertArrayEquals(bytes, sink.toByteArray())
        source.delete()
    }

    @Test
    fun `failure cleanup removes temp ZIP and precreated SAF document`() {
        SafContentResolverShadow.reset()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://test/precreated")
        val tempFile = Files.createTempFile("saf-temp", ".zip").toFile()

        cleanupSafExportFailure(context, uri, tempFile)

        assertFalse(tempFile.exists())
        assertEquals(listOf(uri), SafContentResolverShadow.deletedUris)
    }
}

/** 覆盖 Robolectric 默认回落行为，使测试能精确构造 SAF null 输出流。 */
@Implements(ContentResolver::class)
class SafContentResolverShadow {
    @Implementation
    protected fun openOutputStream(uri: Uri): OutputStream? = outputStreams[uri]?.invoke()

    @Implementation
    protected fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        deletedUris += uri
        return 1
    }

    companion object {
        private val outputStreams = mutableMapOf<Uri, () -> OutputStream?>()
        val deletedUris = mutableListOf<Uri>()

        fun registerOutputStream(uri: Uri, supplier: () -> OutputStream?) {
            outputStreams[uri] = supplier
        }

        fun reset() {
            outputStreams.clear()
            deletedUris.clear()
        }
    }
}
