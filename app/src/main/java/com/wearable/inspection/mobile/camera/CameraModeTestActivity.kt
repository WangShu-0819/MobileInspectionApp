package com.wearable.inspection.mobile.camera

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.view.PreviewView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 相机模式切换测试 Activity
 *
 * 通过 adb 启动运行自动化测试：
 * adb shell am start -n com.wearable.inspection.mobile/.camera.CameraModeTestActivity
 *
 * 测试完成后自动关闭，结果输出到 logcat。
 */
class CameraModeTestActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CameraModeTest"
        private const val ROUND_TRIP_COUNT = 20
        private const val MODE_SWITCH_DELAY_MS = 500L
    }

    private lateinit var statusText: TextView
    private lateinit var previewView: PreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 简单布局
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        statusText = TextView(this).apply {
            text = "相机模式切换测试中..."
            textSize = 16f
        }

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }

        layout.addView(statusText)
        layout.addView(previewView, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1f
        ))

        setContentView(layout)

        // 开始测试
        runModeSwitchTest()
    }

    private fun runModeSwitchTest() {
        lifecycleScope.launch {
            val controller = CameraController.getInstance(this@CameraModeTestActivity)
            val results = mutableListOf<String>()
            var passCount = 0
            var failCount = 0

            try {
                Log.i(TAG, "========== 相机模式切换测试开始 ==========")
                updateStatus("初始连接中...")

                // 初始连接
                val connectResult = controller.connect(
                    this@CameraModeTestActivity,
                    previewView.surfaceProvider,
                    CameraMode.INSPECTION
                )

                if (connectResult.isFailure) {
                    val error = connectResult.exceptionOrNull()?.message ?: "未知错误"
                    Log.e(TAG, "初始连接失败: $error")
                    updateStatus("初始连接失败: $error")
                    return@launch
                }

                Log.i(TAG, "初始连接成功，模式: ${controller.currentMode()}")
                delay(1000) // 等待 CameraX 完成绑定

                // 20 轮 round-trip
                val modes = listOf(
                    CameraMode.INSPECTION,
                    CameraMode.DPM_SCAN,
                    CameraMode.STAMP_OCR,
                    CameraMode.TEMPLATE_CAPTURE,
                    CameraMode.IDLE,
                    CameraMode.INSPECTION
                )

                for (round in 1..ROUND_TRIP_COUNT) {
                    updateStatus("第 $round/$ROUND_TRIP_COUNT 轮...")
                    Log.i(TAG, "--- 第 $round 轮 ---")

                    for (mode in modes) {
                        if (mode == controller.currentMode()) {
                            continue
                        }

                        val result = controller.switchMode(mode)

                        val currentMode = controller.currentMode()
                        val isActive = controller.isActive

                        if (result.isSuccess && currentMode == mode) {
                            passCount++
                            Log.i(TAG, "  ✓ $mode → currentMode=$currentMode, isActive=$isActive")
                        } else {
                            failCount++
                            val error = result.exceptionOrNull()?.message ?: "模式不匹配"
                            Log.e(TAG, "  ✗ $mode 失败: $error, currentMode=$currentMode")
                            results.add("第 $round 轮 $mode 失败: $error")
                        }

                        delay(MODE_SWITCH_DELAY_MS)
                    }
                }

                // 断开连接
                controller.disconnect()
                Log.i(TAG, "连接已断开")

                // 输出结果
                Log.i(TAG, "========== 测试结果 ==========")
                Log.i(TAG, "通过: $passCount, 失败: $failCount")
                if (results.isNotEmpty()) {
                    Log.e(TAG, "失败详情:")
                    results.forEach { Log.e(TAG, "  $it") }
                }
                Log.i(TAG, "========== 测试完成 ==========")

                updateStatus("测试完成: $passCount 通过, $failCount 失败")

                // 等待一会让用户看到结果
                delay(3000)

            } catch (e: Exception) {
                Log.e(TAG, "测试异常", e)
                updateStatus("测试异常: ${e.message}")
            } finally {
                // 自动关闭
                delay(2000)
                finish()
            }
        }
    }

    private fun updateStatus(text: String) {
        statusText.text = text
        Log.d(TAG, "状态: $text")
    }
}
