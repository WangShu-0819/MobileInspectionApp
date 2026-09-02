package com.wearable.inspection.mobile.result

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipInputStream

/**
 * ResultPackager JVM 单测 — 纯 java.util.zip，无需 Android/OpenCV 原生库。
 */
class ResultPackagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `打包多张照片 - 条目齐全且内容一致`() = runBlocking {
        val a = tmp.newFile("a.png").apply { writeBytes(ByteArray(1024) { 1 }) }
        val b = tmp.newFile("b.png").apply { writeBytes(ByteArray(2048) { 2 }) }
        val out = tmp.newFolder("out")

        val zip = ResultPackager.createResultZip(listOf(a, b), out, "part_123", 1000L)

        assertNotNull("应生成 zip", zip)
        assertEquals("文件名符合 result_[partId]_[timestamp].zip", "result_part_123_1000.zip", zip?.name)
        val entries = ZipInputStream(zip!!.inputStream().buffered()).use { zis ->
            buildList {
                var e = zis.nextEntry
                while (e != null) {
                    val bytes = zis.readBytes()
                    add(e.name to bytes)
                    e = zis.nextEntry
                }
            }
        }
        assertEquals("2 个条目", 2, entries.size)
        assertEquals("条目带序号前缀", "1_a.png", entries[0].first)
        assertEquals("条目带序号前缀", "2_b.png", entries[1].first)
        assertTrue("条目内容一致", entries[0].second.contentEquals(a.readBytes()))
        assertTrue("条目内容一致", entries[1].second.contentEquals(b.readBytes()))
    }

    @Test
    fun `空列表 - 返回 null 且不生成文件`() = runBlocking {
        val out = tmp.newFolder("out")

        assertNull(ResultPackager.createResultZip(emptyList(), out, "part_1", 1L))
        assertTrue("目录应为空", out.listFiles().isNullOrEmpty())
    }

    @Test
    fun `全部文件不存在 - 返回 null`() = runBlocking {
        val out = tmp.newFolder("out")

        assertNull(ResultPackager.createResultZip(listOf(File("no_such.png")), out, "part_1", 1L))
    }

    @Test
    fun `输出目录不存在 - 自动创建并生成 zip`() = runBlocking {
        val a = tmp.newFile("a.png").apply { writeBytes("hello".toByteArray()) }
        val out = File(tmp.root, "nested/zip/dir")

        val zip = ResultPackager.createResultZip(listOf(a), out, "part_1", 2L)

        assertNotNull("应生成 zip", zip)
        assertTrue("zip 已落盘", zip!!.exists())
        assertTrue("嵌套目录已创建", out.exists())
    }
}
