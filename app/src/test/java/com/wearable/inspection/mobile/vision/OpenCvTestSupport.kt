package com.wearable.inspection.mobile.vision

import org.opencv.core.Core
import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile

/**
 * 桌面 OpenCV 原生库加载（JVM 单测共享）。
 *
 * 官方 org.opencv:opencv 是 Android AAR（只有 arm64/x86 .so），无法在 JVM 加载，
 * 因此 testImplementation 引入了桌面版 org.openpnp:opencv（含各平台原生库），
 * 加载回退链：System.loadLibrary → nu.pattern.OpenCV.loadLocally → classpath 手动抽取。
 */
object OpenCvTestSupport {

    @JvmStatic
    fun loadNative() {
        // 1) 环境库路径中已有 OpenCV（如本机安装过桌面版）
        if (runCatching { System.loadLibrary(Core.NATIVE_LIBRARY_NAME) }.isSuccess) return
        // 2) openpnp / nu.pattern 桌面 jar：Loader 自动按平台抽取原生库并加载
        runCatching { Class.forName("nu.pattern.OpenCV").getMethod("loadLocally").invoke(null) }
            .onSuccess { return }
        // 3) 兜底：手动扫描 classpath jar 中的原生库并 System.load
        extractAndLoadNativeFromClasspath()
    }

    /** 从测试 classpath 的桌面 OpenCV jar 中提取 Windows/macOS/Linux 原生库并加载。 */
    private fun extractAndLoadNativeFromClasspath() {
        val classPath = System.getProperty("java.class.path")?.split(File.pathSeparator) ?: emptyList()
        val found = classPath
            .filter { it.endsWith(".jar") }
            .firstNotNullOfOrNull { jarPath ->
                runCatching {
                    JarFile(jarPath).use { jar ->
                        jar.entries().asSequence()
                            .firstOrNull { entry ->
                                val name = entry.name
                                !name.contains("class") && (
                                    name.endsWith(".dll") || name.endsWith(".dylib") || name.endsWith(".so")
                                    ) && name.contains("opencv_java")
                            }
                            ?.let { jarPath to it.name }
                    }
                }.getOrNull()
            }
            ?: error("未找到 OpenCV 桌面原生库，请确认 testImplementation 已引入 org.openpnp:opencv")
        val (jarPath, entryName) = found
        val tmpDir = Files.createTempDirectory("opencv_native").toFile()
        val nativeFile = File(tmpDir, entryName.substringAfterLast('/'))
        JarFile(jarPath).use { jar ->
            jar.getInputStream(jar.getEntry(entryName)).use { input ->
                nativeFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        System.load(nativeFile.absolutePath)
    }
}
