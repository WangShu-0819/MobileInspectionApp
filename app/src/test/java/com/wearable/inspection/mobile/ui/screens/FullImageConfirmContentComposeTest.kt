package com.wearable.inspection.mobile.ui.screens

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.wearable.inspection.mobile.detection.FullImageInferResult
import com.wearable.inspection.mobile.detection.NanoDetBox
import com.wearable.inspection.mobile.detection.NanoDetDetection
import com.wearable.inspection.mobile.detection.NanoDetInferenceStatus
import com.wearable.inspection.mobile.detection.NanoDetModelContract
import com.wearable.inspection.mobile.detection.NanoDetSuggestion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 整图确认模式 Compose UI 测试
 *
 * 直接渲染 [FullImageConfirmContent] 和 [BottomConfirmBar]，
 * 断言真实 UI 输出，不复制生产字符串拼接逻辑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FullImageConfirmContentComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ViewConfirmationTestActivity>()

    private val detections = listOf(
        NanoDetDetection(
            classIndex = 1, className = "thread", score = 0.89f, point = 10,
            roiBox = NanoDetBox(1.0, 2.0, 50.0, 60.0),
            imageBox = NanoDetBox(101.0, 202.0, 150.0, 262.0),
        ),
        NanoDetDetection(
            classIndex = 0, className = "nut", score = 0.20f, point = 5,
            roiBox = NanoDetBox(10.0, 15.0, 20.0, 28.0),
            imageBox = NanoDetBox(110.0, 215.0, 120.0, 228.0),
        ),
    )

    private fun fullImageResult(
        dets: List<NanoDetDetection> = detections,
        threshold: Float = NanoDetModelContract.STARTING_BUSINESS_THRESHOLD,
        status: NanoDetInferenceStatus = NanoDetInferenceStatus.DETECTED,
        detail: String? = null,
    ) = FullImageInferResult(
        detections = dets,
        aggregatedSuggestion = null,
        highestScore = dets.maxOfOrNull { it.score },
        elapsedMs = 50,
        imageWidth = 1920,
        imageHeight = 1080,
        exifOrientation = 1,
        status = status,
        detail = detail,
        threshold = threshold,
    )

    // ───────────────────────────────────────────────
    // FullImageConfirmContent 渲染测试
    // ───────────────────────────────────────────────

    @Test
    fun fullImageContentShowsDetectionSummaryWithThreshold() {
        val result = fullImageResult()
        composeRule.setContent {
            MaterialTheme {
                FullImageConfirmContent(
                    fullResult = result,
                    photoPath = null, // 不加载真实照片
                )
            }
        }

        // 渲染真实 UI composable，断言 "整图检出" 文案出现
        composeRule.onNodeWithText("整图检出：1 个 · 阈值 50%").assertIsDisplayed()
    }

    @Test
    fun fullImageContentShowsUnavailableWhenResultIsNull() {
        composeRule.setContent {
            MaterialTheme {
                FullImageConfirmContent(
                    fullResult = null,
                    photoPath = null,
                )
            }
        }

        composeRule.onNodeWithText("整图检测结果不可用").assertIsDisplayed()
    }

    @Test
    fun fullImageContentShowsErrorWhenInferenceError() {
        val result = fullImageResult(
            status = NanoDetInferenceStatus.INFERENCE_ERROR,
            detail = "模型加载失败",
        )
        composeRule.setContent {
            MaterialTheme {
                FullImageConfirmContent(
                    fullResult = result,
                    photoPath = null,
                )
            }
        }

        composeRule.onNodeWithText("推理异常：模型加载失败").assertIsDisplayed()
    }

    @Test
    fun fullImageContentShowsUnknownErrorWhenDetailIsNull() {
        val result = fullImageResult(
            status = NanoDetInferenceStatus.INFERENCE_ERROR,
            detail = null,
        )
        composeRule.setContent {
            MaterialTheme {
                FullImageConfirmContent(
                    fullResult = result,
                    photoPath = null,
                )
            }
        }

        composeRule.onNodeWithText("推理异常：未知错误").assertIsDisplayed()
    }

    @Test
    fun fullImageContentFiltersDetectionsBelowThreshold() {
        // 0.20 < 0.50 阈值，应被过滤；只剩 1 个检测
        val result = fullImageResult()
        composeRule.setContent {
            MaterialTheme {
                FullImageConfirmContent(
                    fullResult = result,
                    photoPath = null,
                )
            }
        }

        // 0.89 >= 0.50 保留，0.20 < 0.50 过滤 → 显示 "1 个"
        composeRule.onNodeWithText("整图检出：1 个 · 阈值 50%").assertIsDisplayed()
    }

    @Test
    fun fullImageContentShowsAllDetectionsWhenAllAboveThreshold() {
        val highScoreDetections = listOf(
            NanoDetDetection(
                classIndex = 1, className = "thread", score = 0.89f, point = 10,
                roiBox = NanoDetBox(1.0, 2.0, 50.0, 60.0),
                imageBox = NanoDetBox(101.0, 202.0, 150.0, 262.0),
            ),
            NanoDetDetection(
                classIndex = 2, className = "bolt", score = 0.75f, point = 8,
                roiBox = NanoDetBox(70.0, 80.0, 100.0, 110.0),
                imageBox = NanoDetBox(170.0, 280.0, 200.0, 310.0),
            ),
        )
        val result = fullImageResult(dets = highScoreDetections)
        composeRule.setContent {
            MaterialTheme {
                FullImageConfirmContent(
                    fullResult = result,
                    photoPath = null,
                )
            }
        }

        composeRule.onNodeWithText("整图检出：2 个 · 阈值 50%").assertIsDisplayed()
    }

    @Test
    fun fullImageContentShowsZeroWhenAllBelowThreshold() {
        val lowScoreDetections = listOf(
            NanoDetDetection(
                classIndex = 0, className = "nut", score = 0.10f, point = 3,
                roiBox = NanoDetBox(1.0, 2.0, 10.0, 12.0),
                imageBox = NanoDetBox(101.0, 202.0, 110.0, 212.0),
            ),
            NanoDetDetection(
                classIndex = 1, className = "thread", score = 0.40f, point = 7,
                roiBox = NanoDetBox(20.0, 25.0, 40.0, 45.0),
                imageBox = NanoDetBox(120.0, 225.0, 140.0, 245.0),
            ),
        )
        val result = fullImageResult(dets = lowScoreDetections)
        composeRule.setContent {
            MaterialTheme {
                FullImageConfirmContent(
                    fullResult = result,
                    photoPath = null,
                )
            }
        }

        composeRule.onNodeWithText("整图检出：0 个 · 阈值 50%").assertIsDisplayed()
    }

    // ───────────────────────────────────────────────
    // 旧长文案不出现
    // ───────────────────────────────────────────────

    @Test
    fun fullImageContentDoesNotShowOldVerboseTexts() {
        val result = fullImageResult()
        composeRule.setContent {
            MaterialTheme {
                FullImageConfirmContent(
                    fullResult = result,
                    photoPath = null,
                )
            }
        }

        // 旧长文案不应出现
        composeRule.onNodeWithText("整图检测模式").assertDoesNotExist()
        composeRule.onNodeWithText("当前使用整图检测模式").assertDoesNotExist()
        composeRule.onNodeWithText("原始检出").assertDoesNotExist()
        composeRule.onNodeWithText("显示检出").assertDoesNotExist()
        composeRule.onNodeWithText("已过滤").assertDoesNotExist()
        composeRule.onNodeWithText("最高匹配分数").assertDoesNotExist()
        composeRule.onNodeWithText("推理耗时").assertDoesNotExist()
        composeRule.onNodeWithText("推理耗时：").assertDoesNotExist()
    }

    @Test
    fun fullImageContentDoesNotShowOldThreshold37() {
        val result = fullImageResult()
        composeRule.setContent {
            MaterialTheme {
                FullImageConfirmContent(
                    fullResult = result,
                    photoPath = null,
                )
            }
        }

        // 不应显示旧的37%阈值
        composeRule.onNodeWithText("阈值 37%").assertDoesNotExist()
    }

    // ───────────────────────────────────────────────
    // BottomConfirmBar OK/NG 确认入口
    // ───────────────────────────────────────────────

    @Test
    fun bottomConfirmBarShowsOkNgSelection() {
        var selectedOverall: String? = null
        composeRule.setContent {
            MaterialTheme {
                BottomConfirmBar(
                    overallResult = null,
                    onOverallSelect = { selectedOverall = it },
                    isAllConfirmed = false,
                    isSaving = false,
                    errorMessage = null,
                    onConfirm = {},
                )
            }
        }

        // 总体结果 OK/NG 选择入口存在
        composeRule.onNodeWithText("总体结果").assertIsDisplayed()
        composeRule.onNodeWithText("OK").assertIsDisplayed()
        composeRule.onNodeWithText("NG").assertIsDisplayed()
        composeRule.onNodeWithText("请完成所有选择").assertIsDisplayed()
    }

    @Test
    fun bottomConfirmBarOkNgClickCallbacks() {
        var selectedOverall: String? = null
        composeRule.setContent {
            MaterialTheme {
                BottomConfirmBar(
                    overallResult = null,
                    onOverallSelect = { selectedOverall = it },
                    isAllConfirmed = false,
                    isSaving = false,
                    errorMessage = null,
                    onConfirm = {},
                )
            }
        }

        // 点击 OK
        composeRule.onNodeWithText("OK").performClick()
        composeRule.runOnIdle { assertEquals("OK", selectedOverall) }
    }

    @Test
    fun bottomConfirmBarShowsConfirmButton() {
        composeRule.setContent {
            MaterialTheme {
                BottomConfirmBar(
                    overallResult = "OK",
                    onOverallSelect = {},
                    isAllConfirmed = true,
                    isSaving = false,
                    errorMessage = null,
                    onConfirm = {},
                )
            }
        }

        composeRule.onNodeWithText("确认并继续").assertIsDisplayed()
    }

    @Test
    fun bottomConfirmBarShowsErrorMessage() {
        composeRule.setContent {
            MaterialTheme {
                BottomConfirmBar(
                    overallResult = "OK",
                    onOverallSelect = {},
                    isAllConfirmed = true,
                    isSaving = false,
                    errorMessage = "照片关联无效",
                    onConfirm = {},
                )
            }
        }

        composeRule.onNodeWithText("照片关联无效").assertIsDisplayed()
    }
}
