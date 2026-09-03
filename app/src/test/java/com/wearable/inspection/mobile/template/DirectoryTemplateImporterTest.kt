package com.wearable.inspection.mobile.template

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DirectoryTemplateImporter JVM 单测 — 纯文件系统 + org.json，无需 Android。
 */
class DirectoryTemplateImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- helpers ----------

    private fun region(
        name: String,
        imageFiles: List<String>,
        roi: JSONObject? = null,
        order: Int? = 1,
    ): JSONObject =
        JSONObject().apply {
            put("regionId", "region_x")
            put("regionName", name)
            order?.let { put("order", it) }
            put("imageFiles", JSONArray(imageFiles))
            roi?.let { put("roi", it) }
        }

    private fun buildManifest(
        partId: String = "PART_88A92",
        partName: String = "黑件 齿轮箱盖",
        dpmCode: String? = "DPM-88A92-001",
        regions: JSONArray = JSONArray().apply {
            put(region("正前方", listOf("images/region_01.jpg")))
        },
    ): String = JSONObject().apply {
        put("partId", partId)
        put("partName", partName)
        dpmCode?.let { put("dpmCode", it) }
        put("regions", regions)
    }.toString()

    private fun buildDirectory(
        manifest: String = buildManifest(),
        images: Map<String, ByteArray> = mapOf("region_01.jpg" to ByteArray(1024) { (it % 251).toByte() }),
    ): File {
        val dir = tmp.newFolder()
        File(dir, "template.json").writeText(manifest)
        if (images.isNotEmpty()) {
            val imagesDir = File(dir, "images").apply { mkdirs() }
            images.forEach { (name, bytes) ->
                File(imagesDir, name).writeBytes(bytes)
            }
        }
        return dir
    }

    private fun assertImportFails(dir: File, messageContains: String) {
        try {
            DirectoryTemplateImporter.parse(dir)
            fail("应抛出 TemplatePackageImportException（$messageContains）")
        } catch (e: TemplatePackageImportException) {
            assertTrue(
                "异常信息应包含「$messageContains」，实际：${e.message}",
                e.message.orEmpty().contains(messageContains)
            )
        }
    }

    // ---------- 用例 ----------

    @Test
    fun `正常目录 - 解析成功且内容一致`() {
        val img = ByteArray(1024) { (it % 251).toByte() }
        val dir = buildDirectory(
            manifest = buildManifest(
                regions = JSONArray().apply {
                    put(region("正前方", listOf("images/view1.jpg", "images/view2.jpg")))
                    put(region("左侧 45°", listOf("images/view3.jpg")))
                },
            ),
            images = mapOf(
                "view1.jpg" to img,
                "view2.jpg" to img.copyOf(512),
                "view3.jpg" to img,
            ),
        )

        val pkg = DirectoryTemplateImporter.parse(dir)

        assertEquals("PART_88A92", pkg.partId)
        assertEquals("黑件 齿轮箱盖", pkg.partName)
        assertEquals("DPM-88A92-001", pkg.dpmCode)
        assertEquals(2, pkg.regions.size)
        assertEquals("正前方", pkg.regions[0].regionName)
        assertEquals(2, pkg.regions[0].imageFiles.size)
        assertTrue(pkg.warnings.isEmpty())
    }

    @Test
    fun `显式 order - 按 manifest order 排列`() {
        val dir = buildDirectory(
            manifest = buildManifest(
                regions = JSONArray().apply {
                    put(region("视角三", listOf("images/3.jpg"), order = 30))
                    put(region("视角一", listOf("images/1.jpg"), order = 10))
                    put(region("视角二", listOf("images/2.jpg"), order = 20))
                },
            ),
            images = mapOf(
                "3.jpg" to byteArrayOf(3),
                "1.jpg" to byteArrayOf(1),
                "2.jpg" to byteArrayOf(2),
            ),
        )

        val pkg = DirectoryTemplateImporter.parse(dir)

        assertEquals(listOf("视角一", "视角二", "视角三"), pkg.regions.map { it.regionName })
        assertEquals(listOf(0, 1, 2), pkg.regions.map { it.displayOrder })
    }

    @Test
    fun `缺少 order - 使用 manifest index 作为稳定 fallback`() {
        val dir = buildDirectory(
            manifest = buildManifest(
                regions = JSONArray().apply {
                    put(region("第一", listOf("images/1.jpg"), order = null))
                    put(region("第二", listOf("images/2.jpg"), order = null))
                },
            ),
            images = mapOf("1.jpg" to byteArrayOf(1), "2.jpg" to byteArrayOf(2)),
        )

        val pkg = DirectoryTemplateImporter.parse(dir)

        assertEquals(listOf("第一", "第二"), pkg.regions.map { it.regionName })
        assertEquals(listOf(0, 1), pkg.regions.map { it.displayOrder })
    }

    @Test
    fun `重复 order - 按 manifest 原始 index 确定性排列`() {
        val dir = buildDirectory(
            manifest = buildManifest(
                regions = JSONArray().apply {
                    put(region("后写入", listOf("images/b.jpg"), order = 5))
                    put(region("先写入", listOf("images/a.jpg"), order = 5))
                },
            ),
            images = mapOf("b.jpg" to byteArrayOf(2), "a.jpg" to byteArrayOf(1)),
        )

        val pkg = DirectoryTemplateImporter.parse(dir)

        assertEquals(listOf("后写入", "先写入"), pkg.regions.map { it.regionName })
    }

    @Test
    fun `缺 template json - 异常`() {
        val dir = tmp.newFolder()
        File(dir, "images").mkdirs()
        assertImportFails(dir, "缺少 template.json")
    }

    @Test
    fun `非法 JSON - 异常`() {
        val dir = tmp.newFolder()
        File(dir, "template.json").writeText("not-json{{{")
        assertImportFails(dir, "不是合法 JSON")
    }

    @Test
    fun `缺 partId - 异常`() {
        val dir = buildDirectory(manifest = buildManifest(partId = "   "))
        assertImportFails(dir, "partId")
    }

    @Test
    fun `缺 partName - 异常`() {
        val dir = buildDirectory(manifest = buildManifest(partName = ""))
        assertImportFails(dir, "partName")
    }

    @Test
    fun `partId 非法字符 - 异常`() {
        val dir = buildDirectory(manifest = buildManifest(partId = "PART/A"))
        assertImportFails(dir, "partId")
    }

    @Test
    fun `引用图缺失 - 解析成功且该视角无图并记警告`() {
        val dir = buildDirectory(
            images = emptyMap() // 不创建任何图片
        )

        val pkg = DirectoryTemplateImporter.parse(dir)

        assertEquals(1, pkg.regions.size)
        assertEquals("正前方", pkg.regions[0].regionName)
        assertTrue("缺图视角 imageFiles 为空", pkg.regions[0].imageFiles.isEmpty())
        assertTrue("有缺图警告", pkg.warnings.any { it.contains("缺图") })
    }

    @Test
    fun `dpmCode 缺失 - 为 null`() {
        val dir = buildDirectory(manifest = buildManifest(dpmCode = null))
        val pkg = DirectoryTemplateImporter.parse(dir)
        assertNull(pkg.dpmCode)
    }

    @Test
    fun `regions 为空数组 - 解析成功`() {
        val dir = buildDirectory(
            manifest = buildManifest(regions = JSONArray()),
            images = emptyMap(),
        )
        val pkg = DirectoryTemplateImporter.parse(dir)
        assertTrue(pkg.regions.isEmpty())
        assertTrue(pkg.warnings.isEmpty())
    }

    @Test
    fun `roi 非法 - 仅警告不失败`() {
        val badRoi = JSONObject().apply {
            put("x", -0.5)
            put("y", 0.1)
            put("width", 0.8)
            put("height", 0.8)
        }
        val dir = buildDirectory(
            manifest = buildManifest(
                regions = JSONArray().apply {
                    put(region("正前方", listOf("images/a.jpg"), badRoi))
                },
            ),
            images = mapOf("a.jpg" to ByteArray(4)),
        )

        val pkg = DirectoryTemplateImporter.parse(dir)

        assertEquals(1, pkg.regions.size)
        assertEquals(1, pkg.regions[0].imageFiles.size)
        assertTrue("有 roi 警告", pkg.warnings.any { it.contains("roi") })
    }

    @Test
    fun `非目录输入 - 异常`() {
        val file = tmp.newFile()
        try {
            DirectoryTemplateImporter.parse(file)
            fail("应抛出异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("不是目录"))
        }
    }

    @Test
    fun `template json 超限 - 异常`() {
        val dir = tmp.newFolder()
        File(dir, "template.json").writeText("a".repeat(TemplatePackageImporter.MAX_JSON_BYTES + 10))
        assertImportFails(dir, "超过大小上限")
    }

    @Test
    fun `非 images 条目 - 忽略`() {
        val dir = buildDirectory()
        File(dir, "README.txt").writeText("说明")
        val pkg = DirectoryTemplateImporter.parse(dir)
        assertEquals(1, pkg.regions.size)
    }
}
