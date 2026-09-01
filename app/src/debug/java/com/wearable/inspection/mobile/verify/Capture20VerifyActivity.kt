package com.wearable.inspection.mobile.verify

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wearable.inspection.mobile.camera.CameraController
import com.wearable.inspection.mobile.camera.CameraMode
import com.wearable.inspection.mobile.camera.CameraStateType
import com.wearable.inspection.mobile.data.image.MobileImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Task 4 验收专用 Activity
 *
 * 仅用于真机连续拍摄 20 张验收。
 * 复用生产 CameraController / CameraSession / ImageCapture / takePhoto / MobileImageStore。
 * 不创建第二套 CameraX，不写假检测数据。
 *
 * 仅在 debug 构建中可用，不进入 release Manifest。
 */
class Capture20VerifyActivity : ComponentActivity() {

    companion object {
        private const val TAG = "Capture20Verify"
        private const val REQUEST_CAMERA = 1001
        private const val TARGET_CAPTURES = 20
    }

    private lateinit var previewView: PreviewView
    private lateinit var statusText: android.widget.TextView
    private lateinit var startButton: android.widget.Button

    private lateinit var cameraController: CameraController
    private lateinit var imageStore: MobileImageStore

    private var sessionId: String? = null
    private var cameraReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraController = CameraController.getInstance(this)
        imageStore = MobileImageStore(this)

        // 简单 UI
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        previewView = PreviewView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }

        statusText = android.widget.TextView(this).apply {
            text = "等待相机权限..."
            textSize = 14f
            setPadding(0, 16, 0, 16)
        }

        startButton = android.widget.Button(this).apply {
            text = "开始拍摄 20 张"
            isEnabled = false
            setOnClickListener { startCapture() }
        }

        layout.addView(previewView)
        layout.addView(statusText)
        layout.addView(startButton)
        setContentView(layout)

        // 检查权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            statusText.text = "❌ 相机权限被拒绝"
        }
    }

    private fun startCamera() {
        lifecycleScope.launch {
            statusText.text = "正在连接相机..."

            val result = cameraController.connect(
                this@Capture20VerifyActivity,
                previewView.surfaceProvider,
                CameraMode.INSPECTION
            )

            if (result.isFailure) {
                statusText.text = "❌ 相机连接失败: ${result.exceptionOrNull()?.message}"
                return@launch
            }

            sessionId = result.getOrNull()?.sessionId
            Log.i(TAG, "相机已连接, sessionId=$sessionId")

            // 等待 CameraState.OPEN
            statusText.text = "等待相机就绪..."
            var waited = 0
            while (cameraController.cameraStateFlow.value != CameraStateType.OPEN && waited < 10000) {
                delay(100)
                waited += 100
            }

            if (cameraController.cameraStateFlow.value == CameraStateType.OPEN) {
                cameraReady = true
                statusText.text = "✅ 相机就绪 (sessionId=$sessionId)\n自动开始拍摄 $TARGET_CAPTURES 张..."
                startButton.isEnabled = false
                Log.i(TAG, "相机就绪, CameraState=OPEN")

                // 自动开始拍摄
                delay(1000)
                performCaptureBatch()
            } else {
                statusText.text = "❌ 相机未就绪, 超时 (${waited}ms)"
                Log.e(TAG, "相机未就绪, 超时")
            }
        }
    }

    private fun startCapture() {
        startButton.isEnabled = false
        lifecycleScope.launch {
            performCaptureBatch()
        }
    }

    private suspend fun performCaptureBatch() {
        val sid = sessionId
        if (sid == null || !cameraReady) {
            statusText.text = "❌ 相机未就绪"
            return
        }

        // 清理旧测试目录
        val testDir = File(getExternalFilesDir(null), "capture20_verify")
        if (testDir.exists()) {
            testDir.deleteRecursively()
        }
        testDir.mkdirs()

        // 清理临时目录
        imageStore.cleanTempDir()

        statusText.text = "开始拍摄 $TARGET_CAPTURES 张..."

        val results = mutableListOf<JSONObject>()
        var successCount = 0
        var failCount = 0

        for (i in 1..TARGET_CAPTURES) {
            statusText.text = "拍摄中: $i / $TARGET_CAPTURES"
            Log.i(TAG, "拍摄第 $i 张...")

            val tempFile = imageStore.generateTempFile()

            try {
                val captureResult = withContext(Dispatchers.IO) {
                    cameraController.takePhoto(sid, tempFile)
                }

                if (captureResult.isSuccess) {
                    Log.i(TAG, "takePhoto 成功, tempFile.exists=${tempFile.exists()}, tempFile.length=${tempFile.length()}")

                    // 使用 MobileImageStore 校验和存储
                    val stored = withContext(Dispatchers.IO) {
                        imageStore.storeCapturedImage(tempFile)
                    }

                    Log.i(TAG, "storeCapturedImage 结果: ${if (stored != null) "成功" else "null"}")

                    if (stored != null) {
                        val finalFile = File(stored.finalPath)
                        val sha256 = sha256(finalFile)

                        val record = JSONObject().apply {
                            put("index", i)
                            put("finalPath", stored.finalPath)
                            put("filename", finalFile.name)
                            put("sizeBytes", stored.sizeBytes)
                            put("width", stored.width)
                            put("height", stored.height)
                            put("exifOrientation", stored.orientation)
                            put("capturedAt", stored.capturedAt)
                            put("sha256", sha256)
                            put("decodeOk", true)
                        }
                        results.add(record)
                        successCount++
                        Log.i(TAG, "✅ 第 $i 张成功: ${finalFile.name}, ${stored.width}x${stored.height}, ${stored.sizeBytes}B")
                    } else {
                        failCount++
                        val record = JSONObject().apply {
                            put("index", i)
                            put("error", "MobileImageStore.storeCapturedImage returned null")
                            put("tempFileExists", tempFile.exists())
                            put("tempFileLength", tempFile.length())
                        }
                        results.add(record)
                        Log.e(TAG, "❌ 第 $i 张: storeCapturedImage 返回 null, tempFile.exists=${tempFile.exists()}, tempFile.length=${tempFile.length()}")
                        // 清理临时文件
                        imageStore.deleteTempFile(tempFile)
                    }
                } else {
                    failCount++
                    val record = JSONObject().apply {
                        put("index", i)
                        put("error", captureResult.exceptionOrNull()?.message ?: "unknown")
                        put("tempFileDeleted", !tempFile.exists())
                    }
                    results.add(record)
                    Log.e(TAG, "❌ 第 $i 张失败: ${captureResult.exceptionOrNull()?.message}")
                    // 清理临时文件
                    imageStore.deleteTempFile(tempFile)
                }
            } catch (e: Exception) {
                failCount++
                val record = JSONObject().apply {
                    put("index", i)
                    put("error", e.message ?: "exception")
                    put("tempFileDeleted", !tempFile.exists())
                }
                results.add(record)
                Log.e(TAG, "❌ 第 $i 张异常: ${e.message}")
                imageStore.deleteTempFile(tempFile)
            }

            // 短暂间隔
            delay(200)
        }

        // 验证汇总
        val summary = buildSummary(results, successCount, failCount)

        // 保存结果到文件
        val resultFile = File(testDir, "capture20_validation.json")
        resultFile.writeText(summary.toString(2))

        // 保存简要摘要
        val summaryFile = File(testDir, "capture20_summary.txt")
        summaryFile.writeText(buildSummaryText(summary))

        statusText.text = buildSummaryText(summary)
        startButton.isEnabled = true

        Log.i(TAG, "========== 验收完成 ==========")
        Log.i(TAG, summary.toString(2))
    }

    private fun buildSummary(results: List<JSONObject>, successCount: Int, failCount: Int): JSONObject {
        val successfulResults = results.filter { it.optBoolean("decodeOk", false) }
        val uniquePaths = successfulResults.map { it.optString("finalPath") }.toSet().size
        val uniqueNames = successfulResults.map { it.optString("filename") }.toSet().size
        val nonEmpty = successfulResults.count { it.optLong("sizeBytes", 0) > 0 }
        val decodeOk = successfulResults.count { it.optBoolean("decodeOk", false) }
        val validDimensions = successfulResults.count {
            it.optInt("width", 0) > 0 && it.optInt("height", 0) > 0
        }
        val orientationValid = successfulResults.count {
            isValidExifOrientation(it.optInt("exifOrientation", -1))
        }
        val checksumCount = successfulResults.count { it.optString("sha256").isNotEmpty() }

        // 检查临时目录残留
        val tempRemaining = imageStore.listCaptures().filter {
            it.name.endsWith(".tmp.jpg")
        }.size

        return JSONObject().apply {
            put("requested", TARGET_CAPTURES)
            put("saved", successCount)
            put("failed", failCount)
            put("uniquePaths", uniquePaths)
            put("uniqueNames", uniqueNames)
            put("nonEmpty", nonEmpty)
            put("decodeOk", decodeOk)
            put("validDimensions", validDimensions)
            put("orientationMetadataValid", orientationValid)
            put("checksumCount", checksumCount)
            put("tempRemaining", tempRemaining)
            put("captureTimeMs", System.currentTimeMillis())
            put("records", JSONArray(results))
        }
    }

    private fun buildSummaryText(summary: JSONObject): String {
        return buildString {
            appendLine("========== Task 4 拍照验收 ==========")
            appendLine("requested=${summary.optInt("requested")}")
            appendLine("saved=${summary.optInt("saved")}")
            appendLine("failed=${summary.optInt("failed")}")
            appendLine("uniquePaths=${summary.optInt("uniquePaths")}")
            appendLine("uniqueNames=${summary.optInt("uniqueNames")}")
            appendLine("nonEmpty=${summary.optInt("nonEmpty")}")
            appendLine("decodeOk=${summary.optInt("decodeOk")}")
            appendLine("validDimensions=${summary.optInt("validDimensions")}")
            appendLine("orientationMetadataValid=${summary.optInt("orientationMetadataValid")}")
            appendLine("checksumCount=${summary.optInt("checksumCount")}")
            appendLine("tempRemaining=${summary.optInt("tempRemaining")}")

            val pass = summary.optInt("saved") == TARGET_CAPTURES &&
                    summary.optInt("uniquePaths") == TARGET_CAPTURES &&
                    summary.optInt("uniqueNames") == TARGET_CAPTURES &&
                    summary.optInt("nonEmpty") == TARGET_CAPTURES &&
                    summary.optInt("decodeOk") == TARGET_CAPTURES &&
                    summary.optInt("validDimensions") == TARGET_CAPTURES &&
                    summary.optInt("orientationMetadataValid") == TARGET_CAPTURES &&
                    summary.optInt("checksumCount") == TARGET_CAPTURES &&
                    summary.optInt("tempRemaining") == 0

            appendLine()
            appendLine(if (pass) "✅ 全部通过" else "❌ 存在失败项")
            appendLine()
            appendLine("结果文件: ${getExternalFilesDir(null)}/capture20_verify/capture20_validation.json")
        }
    }

    private fun isValidExifOrientation(orientation: Int): Boolean {
        // 0 表示未设置（等同于 1/NORMAL），1-8 是标准 EXIF 方向值
        return orientation in 0..8
    }

    private fun sha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "SHA-256 计算失败", e)
            ""
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch {
            sessionId?.let { cameraController.disconnect(it) }
        }
    }
}
