package com.wearable.inspection.mobile.template

import java.io.File
import org.json.JSONException
import org.json.JSONObject

/**
 * 目录模板包解析器（纯 JVM，无 Android API）。
 *
 * 与 [TemplatePackageImporter] 功能等价，但输入是已解压目录（含 template.json + images/），
 * 而非 ZIP 文件。底层复用相同的 manifest 解析和校验逻辑。
 *
 * 目录规范：根目录 template.json + images/ 图片文件夹。
 * template.json：{ partId, partName, dpmCode?, regions: [{ regionName, imageFiles: ["images/x.jpg"], order?, roi? }] }
 */
object DirectoryTemplateImporter {

    private const val MANIFEST_ENTRY = "template.json"
    private const val IMAGES_PREFIX = "images/"

    /** partId 字符集校验（同 TemplatePackageImporter） */
    private val PART_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

    /**
     * 解析目录模板包。
     *
     * @param directory 包含 template.json 和 images/ 的目录
     * @return 解析结果
     * @throws TemplatePackageImportException 结构/安全校验失败
     */
    fun parse(directory: File): TemplatePackage {
        require(directory.isDirectory) { "不是目录：$directory" }

        // 1. 读取 template.json
        val manifestFile = File(directory, MANIFEST_ENTRY)
        if (!manifestFile.exists()) {
            throw TemplatePackageImportException("模板包缺少 $MANIFEST_ENTRY")
        }
        if (manifestFile.length() > TemplatePackageImporter.MAX_JSON_BYTES) {
            throw TemplatePackageImportException("$MANIFEST_ENTRY 超过大小上限 ${TemplatePackageImporter.MAX_JSON_BYTES} 字节")
        }
        val manifestText = manifestFile.readText(Charsets.UTF_8)

        // 2. 扫描 images/ 目录
        val imagesDir = File(directory, "images")
        val extracted = mutableMapOf<String, File>() // basename → file
        if (imagesDir.isDirectory) {
            imagesDir.listFiles()
                ?.sortedWith(compareBy<File> { it.name }.thenBy { it.absolutePath })
                ?.forEach { file ->
                if (file.isFile) {
                    extracted[file.name] = file
                }
                }
        }

        // 3. 解析 manifest（复用 TemplatePackageImporter 的解析逻辑）
        return parseManifest(manifestText, extracted)
    }

    private fun parseManifest(text: String, extracted: Map<String, File>): TemplatePackage {
        val warnings = mutableListOf<String>()
        val obj = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw TemplatePackageImportException("template.json 不是合法 JSON：${e.message}")
        }

        val partId = obj.optString("partId").trim()
        if (partId.isEmpty()) {
            throw TemplatePackageImportException("template.json 缺少 partId")
        }
        if (!PART_ID_REGEX.matches(partId)) {
            throw TemplatePackageImportException("partId 非法（仅允许字母/数字/下划线/连字符，1~64 位）：$partId")
        }
        val partName = obj.optString("partName").trim()
        if (partName.isEmpty()) {
            throw TemplatePackageImportException("template.json 缺少 partName")
        }
        if (partName.length > 64) {
            throw TemplatePackageImportException("partName 超过 64 字符")
        }
        val dpmCode = when {
            !obj.has("dpmCode") || obj.isNull("dpmCode") -> null
            else -> obj.optString("dpmCode").trim().ifEmpty { null }
        }

        val regions = mutableListOf<IndexedRegion>()
        obj.optJSONArray("regions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val region = arr.optJSONObject(i) ?: continue
                val regionName = region.optString("regionName").trim()
                if (regionName.isEmpty()) {
                    warnings += "已跳过缺少名称的视角（index $i）"
                    continue
                }
                validateRoi(region, regionName, warnings)
                val imageFiles = mutableListOf<File>()
                region.optJSONArray("imageFiles")?.let { refs ->
                    for (j in 0 until refs.length()) {
                        val ref = refs.optString(j).trim()
                        if (ref.isEmpty()) continue
                        // 兼容 "images/x.jpg" 与裸 "x.jpg"：一律按 basename 匹配
                        val basename = ref.removePrefix(IMAGES_PREFIX).substringAfterLast('/')
                        val file = extracted[basename]
                        if (file == null) {
                            warnings += "视角「$regionName」缺图 $ref（已跳过）"
                            continue
                        }
                        imageFiles += file
                    }
                }
                regions += IndexedRegion(
                    originalIndex = i,
                    displayOrder = regionOrder(region, i),
                    region = TemplateRegionData(regionName, imageFiles)
                )
            }
        }
        val orderedRegions = regions
            .sortedWith(compareBy<IndexedRegion> { it.displayOrder }.thenBy { it.originalIndex })
            .mapIndexed { index, item -> item.region.copy(displayOrder = index) }
        return TemplatePackage(partId, partName, dpmCode, orderedRegions, warnings)
    }

    private data class IndexedRegion(
        val originalIndex: Int,
        val displayOrder: Int,
        val region: TemplateRegionData,
    )

    /** 显式 manifest order 优先；没有合法 order 时使用 manifest 数组 index。 */
    private fun regionOrder(region: JSONObject, manifestIndex: Int): Int {
        val raw = region.opt("order")
        return when (raw) {
            is Number -> raw.toInt().takeIf { it >= 0 } ?: manifestIndex
            is String -> raw.toIntOrNull()?.takeIf { it >= 0 } ?: manifestIndex
            else -> manifestIndex
        }
    }

    private fun validateRoi(region: JSONObject, regionName: String, warnings: MutableList<String>) {
        val roi = region.optJSONObject("roi") ?: return
        val ok = runCatching {
            val x = roi.getDouble("x")
            val y = roi.getDouble("y")
            val w = roi.getDouble("width")
            val h = roi.getDouble("height")
            val eps = 1e-9
            listOf(x, y, w, h).all { it.isFinite() } &&
                w > 0 && h > 0 &&
                x in 0.0..1.0 && y in 0.0..1.0 &&
                x + w <= 1.0 + eps && y + h <= 1.0 + eps
        }.getOrDefault(false)
        if (!ok) {
            warnings += "视角「$regionName」的 roi 数值非法，已忽略"
        }
    }
}
