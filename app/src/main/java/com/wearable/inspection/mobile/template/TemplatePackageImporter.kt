package com.wearable.inspection.mobile.template

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream
import org.json.JSONException
import org.json.JSONObject

/** 模板包中一个视角的解析结果（regionName 是与本地视角匹配的唯一键；roi 仅校验不落库） */
data class TemplateRegionData(
    val regionName: String,
    /** 已解压到 workDir 的实际文件，保持 template.json 中 imageFiles 的顺序 */
    val imageFiles: List<File>,
)

/** 离线模板包解析结果（结构与目录无关，落库由 Repository 编排） */
data class TemplatePackage(
    val partId: String,
    val partName: String,
    val dpmCode: String?,
    val regions: List<TemplateRegionData>,
    /** 解析期警告（缺图 / roi 非法 / 跳过的视角），供导入消息展示 */
    val warnings: List<String>,
)

/** 模板包结构/校验失败（缺 template.json、字段非法、路径穿越、超限） */
class TemplatePackageImportException(message: String) : Exception(message)

/**
 * 离线 template.zip 模板包解析器（纯 JVM，无 Android API，可 JVM 单测）。
 *
 * zip 规范（Windows 桌面端导出）：根目录 template.json + images/ 图片文件夹。
 * template.json：{ partId, partName, dpmCode?, regions: [{ regionName, imageFiles: ["images/x.jpg"], order?, roi? }] }
 *
 * 安全护栏：
 * - 条目名校验（`..` 段 / 绝对路径 / 反斜杠 → 硬拒绝，防路径穿越）；
 * - 条目数、真实解压字节数、单文件大小上限（防 zip 炸弹）；
 * - 字节数按实际拷贝累计，不信任 ZipEntry.size（可为 -1 或谎报）。
 */
object TemplatePackageImporter {

    const val MANIFEST_ENTRY = "template.json"
    private const val IMAGES_PREFIX = "images/"

    const val MAX_ENTRIES = 200
    const val MAX_TOTAL_BYTES = 100L * 1024 * 1024
    const val MAX_JSON_BYTES = 1024 * 1024
    const val MAX_IMAGE_BYTES = 20L * 1024 * 1024

    /** partId 同时是零件表主键：限制字符集，防恶意包用 REPLACE 覆盖播种零件 */
    private val PART_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

    /**
     * 解析模板包：解压 images/ 到 [workDir]/images/（扁平化，重复 basename 追加 _1/_2 去重），
     * 校验并解析 template.json。
     * @throws TemplatePackageImportException 结构/安全校验失败
     */
    fun parse(zipFile: File, workDir: File): TemplatePackage {
        var entryCount = 0
        var totalBytes = 0L
        var manifestBytes: ByteArray? = null
        val extracted = LinkedHashMap<String, File>() // basename → 解压文件（JSON 引用匹配用）
        val usedNames = HashSet<String>()

        ZipInputStream(FileInputStream(zipFile).buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entryCount++
                if (entryCount > MAX_ENTRIES) {
                    throw TemplatePackageImportException("模板包条目数超过上限 $MAX_ENTRIES")
                }
                val name = entry.name
                validateEntryName(name)
                when {
                    name == MANIFEST_ENTRY -> {
                        if (manifestBytes != null) {
                            throw TemplatePackageImportException("模板包存在多个 $MANIFEST_ENTRY")
                        }
                        manifestBytes = readLimited(zip, MAX_JSON_BYTES, MANIFEST_ENTRY)
                    }

                    name.startsWith(IMAGES_PREFIX) && name.length > IMAGES_PREFIX.length -> {
                        val basename = name.substring(name.lastIndexOf('/') + 1)
                        if (basename.isEmpty()) { /* "images//" 之类，忽略 */ } else {
                            val unique = uniqueBasename(basename, usedNames)
                            val imagesDir = File(workDir, "images").apply { mkdirs() }
                            val target = File(imagesDir, unique)
                            // 双保险：扁平化 + 条目名校验已防穿越，此处再断言规范路径
                            if (!target.canonicalPath.startsWith(imagesDir.canonicalPath + File.separator)) {
                                throw TemplatePackageImportException("模板包条目名非法（路径穿越）：$name")
                            }
                            var written = 0L
                            target.outputStream().use { out ->
                                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val n = zip.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    written += n
                                    if (written > MAX_IMAGE_BYTES) {
                                        throw TemplatePackageImportException("图片 $name 超过单文件上限 $MAX_IMAGE_BYTES 字节")
                                    }
                                }
                            }
                            totalBytes += written
                            if (totalBytes > MAX_TOTAL_BYTES) {
                                throw TemplatePackageImportException("模板包解压总量超过上限 $MAX_TOTAL_BYTES 字节")
                            }
                            extracted[unique] = target
                        }
                    }

                    else -> { /* README 等非模板内容忽略 */ }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val manifest = manifestBytes ?: throw TemplatePackageImportException("模板包缺少 $MANIFEST_ENTRY")
        return parseManifest(String(manifest, Charsets.UTF_8), extracted)
    }

    /** 读满 limit 字节即抛（防 zip 炸弹的 JSON 内容） */
    private fun readLimited(zip: ZipInputStream, limit: Int, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) {
                throw TemplatePackageImportException("$name 超过大小上限 $limit 字节")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 条目名安全校验：`..` 段 / 以 `/` 开头 / 含 `\` 或盘符 → 硬拒绝 */
    private fun validateEntryName(name: String) {
        if (name.isEmpty()) {
            throw TemplatePackageImportException("模板包存在空条目名")
        }
        if (name.startsWith("/") || name.contains('\\')) {
            throw TemplatePackageImportException("模板包条目名非法（绝对路径或反斜杠）：$name")
        }
        if (name.length >= 2 && name[1] == ':') {
            throw TemplatePackageImportException("模板包条目名非法（盘符路径）：$name")
        }
        if (name.split('/').any { it == ".." || it == "." }) {
            throw TemplatePackageImportException("模板包条目名非法（路径穿越）：$name")
        }
    }

    /** 扁平化解压去重：重名追加 _1/_2 后缀（zip 可能有 images/a.jpg 与 images/x/a.jpg） */
    private fun uniqueBasename(basename: String, used: MutableSet<String>): String {
        if (used.add(basename)) return basename
        val dot = basename.lastIndexOf('.')
        val base = if (dot > 0) basename.substring(0, dot) else basename
        val ext = if (dot > 0) basename.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "${base}_$i$ext"
            if (used.add(candidate)) return candidate
            i++
        }
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

        val regions = mutableListOf<TemplateRegionData>()
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
                        // 兼容 "images/x.jpg" 与裸 "x.jpg"：一律按 basename 匹配解压结果
                        val basename = ref.removePrefix(IMAGES_PREFIX).substringAfterLast('/')
                        val file = extracted[basename]
                        if (file == null) {
                            warnings += "视角「$regionName」缺图 $ref（已跳过）"
                            continue
                        }
                        imageFiles += file
                    }
                }
                regions += TemplateRegionData(regionName, imageFiles)
            }
        }
        return TemplatePackage(partId, partName, dpmCode, regions, warnings)
    }

    /** roi 仅解析校验（TemplateImage 无 roi 列，匹配链路暂不消费），非法仅警告不失败 */
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
