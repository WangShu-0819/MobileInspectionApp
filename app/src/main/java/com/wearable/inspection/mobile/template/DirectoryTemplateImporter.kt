package com.wearable.inspection.mobile.template

import java.io.File

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
        return TemplatePackageImporter.parseManifest(manifestText, extracted)
    }
}
