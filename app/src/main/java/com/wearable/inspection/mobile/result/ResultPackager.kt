package com.wearable.inspection.mobile.result

import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 采集结果打包器 — 把一次采集会话保存的照片打包为 result_[partId]_[timestamp].zip。
 *
 * 输出目录作为参数传入（不持有 Context），JVM 单测可直接用临时目录验证。
 */
object ResultPackager {

    /**
     * 将 [files] 中存在的文件按序打入 zip（IO 线程执行，不阻塞调用方）。
     *
     * @param files 本轮 Session 采集的照片路径（不存在/读取失败的文件跳过）
     * @param outputDir 输出目录（不存在时自动创建）
     * @param partId 零件 id（zip 文件名标识）
     * @param timestamp 时间戳（zip 文件名标识，同一零件多次采集不冲突）
     * @return 生成的 zip 文件；无任何有效文件或目录创建失败时返回 null
     */
    suspend fun createResultZip(
        files: List<File>,
        outputDir: File,
        partId: String,
        timestamp: Long,
    ): File? = withContext(Dispatchers.IO) {
        val valid = files.filter { it.isFile }
        if (valid.isEmpty()) return@withContext null
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            Log.w(TAG, "无法创建输出目录：${outputDir.path}")
            return@withContext null
        }
        val zipFile = File(outputDir, "result_${partId}_${timestamp}.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            valid.forEachIndexed { index, file ->
                // 序号前缀：同一视角重复采集（重拍）时避免同名条目冲突
                zos.putNextEntry(ZipEntry("${index + 1}_${file.name}"))
                try {
                    file.inputStream().use { it.copyTo(zos) }
                } catch (e: Exception) {
                    Log.w(TAG, "照片读取失败，跳过：${file.path} — ${e.message}")
                }
                zos.closeEntry()
            }
        }
        zipFile
    }

    private const val TAG = "ResultPackager"
}
