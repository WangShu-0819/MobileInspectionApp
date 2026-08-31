package com.wearable.inspection.mobile.data.image

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 移动端图片存储工具
 *
 * 职责：
 * 1. 私有目录图片存储（filesDir/templates/{partId}/）
 * 2. 从 SAF Uri 复制图片
 * 3. 从本地文件复制
 * 4. 文件名生成（capture_<partId>_<timestamp>.jpg）
 * 5. 删除图片和目录
 * 6. 路径合法性检查
 * 7. 文件存在性检查
 *
 * 与旧工程 ImageStore 的区别：
 * - 移除 OpenCV Mat 依赖（不再保存匹配帧 Mat）
 * - 文件名格式改为 capture_<partId>_<timestamp>.jpg
 * - 目录结构调整为按 partId 隔离
 */
class MobileImageStore(private val context: Context) {

    companion object {
        private const val TEMPLATES_DIR = "templates"
        private const val MATCH_RESULTS_DIR = "match_results"

        /** 文件名格式：capture_<partId>_<yyyyMMdd_HHmmss_SSS>_<uuid8>.jpg */
        private const val CAPTURE_FILE_PATTERN = "capture_%s_%s_%s.jpg"

        /** 匹配结果文件名格式：match_<yyyyMMdd_HHmmss_SSS>.jpg */
        private const val MATCH_FILE_PATTERN = "match_%s.jpg"
    }

    private val baseDir: File
        get() = File(context.filesDir, TEMPLATES_DIR)

    // ========== 模板图片存储 ==========

    /**
     * 获取指定零件的模板目录
     */
    fun getTemplateDir(partId: String): File {
        return File(baseDir, partId).apply { mkdirs() }
    }

    /**
     * 生成模板图片文件名
     */
    fun generateTemplateFileName(partId: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val uuid = UUID.randomUUID().toString().take(8)
        return String.format(CAPTURE_FILE_PATTERN, partId, ts, uuid)
    }

    /**
     * 从 SAF Uri 复制图片到模板目录
     * @param uri SAF Uri
     * @param partId 零件 ID
     * @return 绝对路径
     * @throws IllegalStateException 读取失败
     */
    fun copyFromUri(uri: Uri, partId: String): String {
        val dir = getTemplateDir(partId)
        val target = File(dir, generateTemplateFileName(partId))
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("无法读取所选图片")
        return target.absolutePath
    }

    /**
     * 从本地文件复制到模板目录
     * @param source 源文件
     * @param partId 零件 ID
     * @return 绝对路径
     */
    fun copyFromFile(source: File, partId: String): String {
        val dir = getTemplateDir(partId)
        val target = File(dir, generateTemplateFileName(partId))
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target.absolutePath
    }

    /**
     * 保存拍照文件（直接写入模板目录）
     * @param partId 零件 ID
     * @param data JPEG 字节数组
     * @return 绝对路径
     * @throws IOException 写入失败
     */
    fun saveCapture(partId: String, data: ByteArray): String {
        val dir = getTemplateDir(partId)
        val file = File(dir, generateTemplateFileName(partId))
        FileOutputStream(file).use { it.write(data) }
        return file.absolutePath
    }

    /**
     * 删除单个模板图片
     */
    fun delete(path: String) {
        runCatching { File(path).delete() }
    }

    /**
     * 删除某零件的所有模板图片
     */
    fun deletePartTemplates(partId: String) {
        val dir = File(baseDir, partId)
        runCatching { dir.deleteRecursively() }
    }

    // ========== 匹配结果存储 ==========

    /**
     * 保存匹配结果图片（JPEG）
     * @param data JPEG 字节数组
     * @return 绝对路径
     */
    fun saveMatchResult(data: ByteArray): String {
        val dir = File(context.filesDir, MATCH_RESULTS_DIR).apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val file = File(dir, String.format(MATCH_FILE_PATTERN, ts))
        FileOutputStream(file).use { it.write(data) }
        return file.absolutePath
    }

    /**
     * 批量删除匹配结果文件
     */
    fun deleteMatchResults(paths: List<String>) {
        paths.forEach { runCatching { File(it).delete() } }
    }

    /**
     * 清空所有匹配结果
     */
    fun clearAllMatchResults() {
        val dir = File(context.filesDir, MATCH_RESULTS_DIR)
        runCatching { dir.deleteRecursively() }
    }

    // ========== 路径合法性检查 ==========

    /**
     * 检查路径是否在私有目录内（防路径遍历攻击）
     */
    fun isPathSafe(path: String): Boolean {
        return try {
            val file = File(path)
            val canonical = file.canonicalPath
            val baseCanonical = context.filesDir.canonicalPath
            canonical.startsWith(baseCanonical)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查文件是否存在且非空
     */
    fun fileExistsAndNonEmpty(path: String): Boolean {
        val file = File(path)
        return file.exists() && file.length() > 0
    }

    // ========== 清理孤儿文件 ==========

    /**
     * 清理指定列表中不存在的文件（返回被删除的路径列表）
     */
    fun cleanOrphanFiles(paths: List<String>): List<String> {
        val deleted = mutableListOf<String>()
        paths.forEach { path ->
            if (!fileExistsAndNonEmpty(path)) {
                runCatching { File(path).delete() }
                deleted.add(path)
            }
        }
        return deleted
    }

    /**
     * 清理指定零件的所有模板图片（删除零件时调用）
     */
    fun cleanupPart(partId: String) {
        deletePartTemplates(partId)
        // TODO: 同时清理该零件的匹配结果（需传入匹配结果路径列表）
    }
}
