package com.wearable.inspection.mobile.template

import java.io.File
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TemplatePackageImporter JVM 单测 — 纯 java.util.zip + org.json，无需 Android。
 */
class TemplatePackageImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- helpers ----------

    /** 手工构造 zip（ZipOutputStream 直写条目；newFile() 无参避免同测试内重复调用同名冲突） */
    private fun buildZip(entries: Map<String, ByteArray>): File {
        val zip = tmp.newFile()
        ZipOutputStream(zip.outputStream().buffered()).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return zip
    }

    private fun region(
        name: String,
        imageFiles: List<String>,
        order: Int? = 1,
    ): JSONObject = JSONObject().apply {
        put("regionId", "region_x")
        put("regionName", name)
        order?.let { put("order", it) }
        put("imageFiles", JSONArray(imageFiles))
    }

    private fun buildManifest(
        partId: String = "PART_88A92",
        partName: String = "黑件 齿轮箱盖",
        dpmCode: String? = "DPM-88A92-001",
        regions: JSONArray = JSONArray().apply { put(region("正前方", listOf("images/region_01_view1.jpg"))) },
    ): String = JSONObject().apply {
        put("partId", partId)
        put("partName", partName)
        dpmCode?.let { put("dpmCode", it) }
        put("regions", regions)
    }.toString()

    private fun assertImportFails(zip: File, messageContains: String) {
        try {
            TemplatePackageImporter.parse(zip, tmp.newFolder())
            fail("应抛出 TemplatePackageImportException（$messageContains）")
        } catch (e: TemplatePackageImportException) {
            assertTrue("异常信息应包含「$messageContains」，实际：${e.message}", e.message.orEmpty().contains(messageContains))
        }
    }

    // ---------- 用例 ----------

    @Test
    fun `正常包 - 多视角多图解析成功且内容一致`() {
        val img = ByteArray(1024) { (it % 251).toByte() }
        val zip = buildZip(
            mapOf(
                "template.json" to buildManifest(
                    regions = JSONArray().apply {
                        put(region("正前方", listOf("images/region_01_view1.jpg", "images/region_01_view2.jpg")))
                        put(region("左侧 45°", listOf("images/region_02_view1.jpg")))
                    },
                ).toByteArray(),
                "images/region_01_view1.jpg" to img,
                "images/region_01_view2.jpg" to img.copyOf(512),
                "images/region_02_view1.jpg" to img,
                // zip 里有但 JSON 未引用 → 忽略（JSON 是唯一清单）
                "images/region_03_unreferenced.jpg" to img,
            ),
        )

        val pkg = TemplatePackageImporter.parse(zip, tmp.newFolder("work"))

        assertEquals("PART_88A92", pkg.partId)
        assertEquals("黑件 齿轮箱盖", pkg.partName)
        assertEquals("DPM-88A92-001", pkg.dpmCode)
        assertEquals("2 视角，未引用图片不进入结果", 2, pkg.regions.size)
        assertEquals("正前方", pkg.regions[0].regionName)
        assertEquals("保持 imageFiles 顺序", 2, pkg.regions[0].imageFiles.size)
        assertArrayEquals("图片内容逐字节一致", img, pkg.regions[0].imageFiles[0].readBytes())
        assertArrayEquals("图片内容逐字节一致", img.copyOf(512), pkg.regions[0].imageFiles[1].readBytes())
        assertEquals("左侧 45°", pkg.regions[1].regionName)
        assertTrue("无警告", pkg.warnings.isEmpty())
    }

    @Test
    fun `显式 order - 按 manifest order 排列`() {
        val zip = buildZip(
            mapOf(
                "template.json" to buildManifest(
                    regions = JSONArray().apply {
                        put(region("视角三", listOf("images/3.jpg"), order = 30))
                        put(region("视角一", listOf("images/1.jpg"), order = 10))
                        put(region("视角二", listOf("images/2.jpg"), order = 20))
                    },
                ).toByteArray(),
                "images/1.jpg" to byteArrayOf(1),
                "images/2.jpg" to byteArrayOf(2),
                "images/3.jpg" to byteArrayOf(3),
            ),
        )

        val pkg = TemplatePackageImporter.parse(zip, tmp.newFolder("work"))

        assertEquals(listOf("视角一", "视角二", "视角三"), pkg.regions.map { it.regionName })
        assertEquals(listOf(0, 1, 2), pkg.regions.map { it.displayOrder })
    }

    @Test
    fun `缺少 order - 使用 manifest index 作为稳定 fallback`() {
        val zip = buildZip(
            mapOf(
                "template.json" to buildManifest(
                    regions = JSONArray().apply {
                        put(region("第一", listOf("images/1.jpg"), order = null))
                        put(region("第二", listOf("images/2.jpg"), order = null))
                    },
                ).toByteArray(),
                "images/1.jpg" to byteArrayOf(1),
                "images/2.jpg" to byteArrayOf(2),
            ),
        )

        val pkg = TemplatePackageImporter.parse(zip, tmp.newFolder("work"))

        assertEquals(listOf("第一", "第二"), pkg.regions.map { it.regionName })
        assertEquals(listOf(0, 1), pkg.regions.map { it.displayOrder })
    }

    @Test
    fun `重复 order - 按 manifest 原始 index 确定性排列`() {
        val zip = buildZip(
            mapOf(
                "template.json" to buildManifest(
                    regions = JSONArray().apply {
                        put(region("后写入", listOf("images/b.jpg"), order = 5))
                        put(region("先写入", listOf("images/a.jpg"), order = 5))
                    },
                ).toByteArray(),
                "images/a.jpg" to byteArrayOf(1),
                "images/b.jpg" to byteArrayOf(2),
            ),
        )

        val pkg = TemplatePackageImporter.parse(zip, tmp.newFolder("work"))

        assertEquals(listOf("后写入", "先写入"), pkg.regions.map { it.regionName })
    }

    @Test
    fun `单视角十张图 - 全部解析且顺序字节一致`() {
        // 样本扩容 5→10 后：导入器需完整保留每视角 10 张（上限截断在 Repository 层，导入器不设限）
        val imgs = (1..10).map { i -> ByteArray(64 + i) { (i * 7).toByte() } }
        val entries = mutableMapOf(
            "template.json" to buildManifest(
                regions = JSONArray().apply {
                    put(region("正前方", (1..10).map { "images/sample_$it.jpg" }))
                },
            ).toByteArray(),
        )
        (1..10).forEach { i -> entries["images/sample_$i.jpg"] = imgs[i - 1] }
        val zip = buildZip(entries)

        val pkg = TemplatePackageImporter.parse(zip, tmp.newFolder("work"))

        assertEquals(1, pkg.regions.size)
        assertEquals("10 张样本全部解析", 10, pkg.regions[0].imageFiles.size)
        imgs.forEachIndexed { idx, bytes ->
            assertArrayEquals("第 ${idx + 1} 张图片内容一致", bytes, pkg.regions[0].imageFiles[idx].readBytes())
        }
        assertTrue("无警告", pkg.warnings.isEmpty())
    }

    @Test
    fun `缺 template json - 异常`() {
        val zip = buildZip(mapOf("images/a.jpg" to ByteArray(4)))
        assertImportFails(zip, "缺少 template.json")
    }

    @Test
    fun `非法 JSON - 异常`() {
        val zip = buildZip(mapOf("template.json" to "not-json{{{".toByteArray()))
        assertImportFails(zip, "不是合法 JSON")
    }

    @Test
    fun `缺 partId 或 partName - 异常`() {
        assertImportFails(buildZip(mapOf("template.json" to buildManifest(partId = "   ").toByteArray())), "partId")
        assertImportFails(buildZip(mapOf("template.json" to buildManifest(partName = "").toByteArray())), "partName")
    }

    @Test
    fun `partId 非法字符 - 异常`() {
        // 含 "/"（路径风格）与含空白，都不能作主键
        assertImportFails(buildZip(mapOf("template.json" to buildManifest(partId = "PART/A").toByteArray())), "partId")
        assertImportFails(buildZip(mapOf("template.json" to buildManifest(partId = "PART A").toByteArray())), "partId")
    }

    @Test
    fun `引用图缺失 - 解析成功且该视角无图并记警告`() {
        val zip = buildZip(mapOf("template.json" to buildManifest().toByteArray()))

        val pkg = TemplatePackageImporter.parse(zip, tmp.newFolder("work"))

        assertEquals(1, pkg.regions.size)
        assertEquals("正前方", pkg.regions[0].regionName)
        assertTrue("缺图视角 imageFiles 为空（容错降级）", pkg.regions[0].imageFiles.isEmpty())
        assertTrue("有缺图警告", pkg.warnings.any { it.contains("缺图") })
    }

    @Test
    fun `路径穿越 - 硬拒绝`() {
        assertImportFails(
            buildZip(mapOf("template.json" to buildManifest().toByteArray(), "../evil.jpg" to ByteArray(4))),
            "路径穿越",
        )
        assertImportFails(
            buildZip(mapOf("template.json" to buildManifest().toByteArray(), "/abs/path.jpg" to ByteArray(4))),
            "绝对路径",
        )
        assertImportFails(
            buildZip(mapOf("template.json" to buildManifest().toByteArray(), "images/..\\evil.jpg" to ByteArray(4))),
            "反斜杠",
        )
    }

    @Test
    fun `条目数超限 - 异常`() {
        val entries = mutableMapOf("template.json" to buildManifest().toByteArray())
        repeat(TemplatePackageImporter.MAX_ENTRIES) { i -> entries["images/f_$i.jpg"] = ByteArray(2) }
        assertImportFails(buildZip(entries), "条目数")
    }

    @Test
    fun `ZipEntry size 元数据巨大 - 不依赖该字段，按真实字节解析成功`() {
        // ZipEntry.size 仅 central directory 元数据（ZipInputStream 流式读取不消费它）：
        // 预置谎报的 10GB 不影响按真实拷贝字节计数
        val zip = tmp.newFile()
        ZipOutputStream(zip.outputStream().buffered()).use { zos ->
            listOf(
                "template.json" to buildManifest(
                    regions = JSONArray().apply { put(region("正前方", listOf("images/fake.jpg"))) },
                ).toByteArray(),
                "images/fake.jpg" to ByteArray(64),
            ).forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name).apply { size = 10L * 1024 * 1024 * 1024 }) // 谎报 10GB
                zos.write(content)
                zos.closeEntry()
            }
        }

        val pkg = TemplatePackageImporter.parse(zip, tmp.newFolder("work"))

        assertEquals(1, pkg.regions.size)
        assertEquals("按真实字节计数，不触发上限", 1, pkg.regions[0].imageFiles.size)
    }

    @Test
    fun `template json 超限 - 异常`() {
        val zip = buildZip(mapOf("template.json" to ByteArray(TemplatePackageImporter.MAX_JSON_BYTES + 10) { 'a'.code.toByte() }))
        assertImportFails(zip, "超过大小上限")
    }

    @Test
    fun `单图片超限 - 异常`() {
        val big = ByteArray((TemplatePackageImporter.MAX_IMAGE_BYTES + 1).toInt())
        Random(42).nextBytes(big)
        val zip = buildZip(
            mapOf(
                "template.json" to buildManifest().toByteArray(),
                "images/big.jpg" to big,
            ),
        )
        assertImportFails(zip, "单文件上限")
    }

    @Test
    fun `dpmCode 缺失 - 为 null`() {
        val zip = buildZip(mapOf("template.json" to buildManifest(dpmCode = null).toByteArray()))

        val pkg = TemplatePackageImporter.parse(zip, tmp.newFolder("work"))

        assertEquals(null, pkg.dpmCode)
    }

    @Test
    fun `重复 basename - 去重后缀且两个文件都解压`() {
        val work = tmp.newFolder("work")
        val zip = buildZip(
            mapOf(
                "template.json" to buildManifest().toByteArray(),
                "images/a.jpg" to ByteArray(8) { 1 },
                "images/x/a.jpg" to ByteArray(8) { 2 },
            ),
        )

        TemplatePackageImporter.parse(zip, work)

        val files = File(work, "images").listFiles()!!.map { it.name }.toSet()
        assertTrue("a.jpg 与 a_1.jpg 都解压", files == setOf("a.jpg", "a_1.jpg"))
    }

    @Test
    fun `非 images 条目 - 忽略`() {
        val zip = buildZip(
            mapOf(
                "template.json" to buildManifest().toByteArray(),
                "images/region_01_view1.jpg" to ByteArray(4), // manifest 引用的图，避免缺图警告
                "README.txt" to "说明".toByteArray(),
            ),
        )

        val pkg = TemplatePackageImporter.parse(zip, tmp.newFolder("work"))

        assertEquals(1, pkg.regions.size)
        assertTrue("README 不产生警告", pkg.warnings.isEmpty())
    }

    @Test
    fun `regions 为空数组 - 解析成功`() {
        val zip = buildZip(mapOf("template.json" to buildManifest(regions = JSONArray()).toByteArray()))

        val pkg = TemplatePackageImporter.parse(zip, tmp.newFolder("work"))

        assertTrue(pkg.regions.isEmpty())
        assertTrue(pkg.warnings.isEmpty())
    }

    @Test
    fun `roi 非法 - 仅警告不失败`() {
        val badRoi = region("正前方", listOf("images/a.jpg")).apply {
            put("roi", JSONObject().apply {
                put("x", -0.5) // 越界
                put("y", 0.1)
                put("width", 0.8)
                put("height", 0.8)
            })
        }
        val zip = buildZip(
            mapOf(
                "template.json" to buildManifest(regions = JSONArray().apply { put(badRoi) }).toByteArray(),
                "images/a.jpg" to ByteArray(4),
            ),
        )

        val pkg = TemplatePackageImporter.parse(zip, tmp.newFolder("work"))

        assertEquals(1, pkg.regions.size)
        assertEquals("图片仍正常解析", 1, pkg.regions[0].imageFiles.size)
        assertTrue("有 roi 警告", pkg.warnings.any { it.contains("roi") })
    }
}
