package com.wearable.inspection.mobile.ui.screens

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.wearable.inspection.mobile.data.entity.RoiDefinitionEntity
import com.wearable.inspection.mobile.detection.NanoDetBox
import com.wearable.inspection.mobile.detection.NanoDetDetection
import com.wearable.inspection.mobile.detection.NanoDetInferenceStatus
import com.wearable.inspection.mobile.detection.NanoDetRoiInferenceResult
import com.wearable.inspection.mobile.detection.NanoDetSuggestion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ViewConfirmationModelResultComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ViewConfirmationTestActivity>()

    @Test
    fun modelOutputIsVisibleAndManualResultRemainsIndependent() {
        val roi = RoiDefinitionEntity(
            id = "roi-compose",
            templateId = "template-compose",
            name = "螺纹 ROI",
            order = 0,
            normalizedRect = "{}",
            inspectionType = "THREAD",
            targetType = "THREAD"
        )
        val result = NanoDetRoiInferenceResult(
            roiId = roi.id,
            status = NanoDetInferenceStatus.DETECTED,
            modelSuggestion = NanoDetSuggestion.OK,
            matchingScore = 0.89f,
            targetClassIndex = 1,
            threshold = 0.37f,
            roiBounds = listOf(0, 0, 100, 100),
            detections = listOf(
                NanoDetDetection(
                    classIndex = 1,
                    className = "thread",
                    score = 0.89f,
                    point = 10,
                    roiBox = NanoDetBox(4.0, 5.0, 50.0, 60.0),
                    imageBox = NanoDetBox(4.0, 5.0, 50.0, 60.0)
                )
            )
        )
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val humanResult = mutableStateOf<String?>(null)
        composeRule.setContent {
            MaterialTheme {
                RoiConfirmCard(roi, bitmap, result, humanResult.value) { humanResult.value = it }
            }
        }

        composeRule.onNodeWithText("检测状态：已检出，达到模型阈值").assertIsDisplayed()
        composeRule.onNodeWithText("目标类别：螺纹（类别 1）　模型建议（仅参考）：OK").assertIsDisplayed()
        composeRule.onNodeWithText("最高匹配分数：89.0%　阈值起始值（未校准）：37.0%").assertIsDisplayed()
        composeRule.onNodeWithText("检测框 1 个，已叠加显示全部保留框（绿色为匹配类别，橙色为其他类别）").assertIsDisplayed()
        composeRule.onNodeWithTag("detection-box-overlay-${roi.id}").assertIsDisplayed()
        val ok = composeRule.onNodeWithTag("human-result-${roi.id}-OK")
        val ng = composeRule.onNodeWithTag("human-result-${roi.id}-NG")
        ok.assertIsNotSelected()
        ng.assertIsNotSelected()

        ng.performClick()
        composeRule.runOnIdle { assertEquals("NG", humanResult.value) }
        ng.assertIsSelected()
        composeRule.onNodeWithText("目标类别：螺纹（类别 1）　模型建议（仅参考）：OK").assertIsDisplayed()

        ok.performClick()
        composeRule.runOnIdle { assertEquals("OK", humanResult.value) }
        ok.assertIsSelected()
        composeRule.onNodeWithText("目标类别：螺纹（类别 1）　模型建议（仅参考）：OK").assertIsDisplayed()
    }

    @Test
    fun noDetectionShowsNGSuggestionAndNoMatchingScore() {
        val roi = RoiDefinitionEntity(
            id = "roi-empty",
            templateId = "template-compose",
            name = "无框 ROI",
            order = 0,
            normalizedRect = "{}",
            inspectionType = "THREAD",
            targetType = "THREAD"
        )
        val result = NanoDetRoiInferenceResult(
            roiId = roi.id,
            status = NanoDetInferenceStatus.NO_DETECTION,
            modelSuggestion = NanoDetSuggestion.NG,
            matchingScore = null,
            targetClassIndex = 1,
            threshold = 0.37f
        )
        composeRule.setContent {
            MaterialTheme { RoiConfirmCard(roi, null, result, null) {} }
        }

        composeRule.onNodeWithText("检测状态：未检出").assertIsDisplayed()
        composeRule.onNodeWithText("目标类别：螺纹（类别 1）　模型建议（仅参考）：NG").assertIsDisplayed()
        composeRule.onNodeWithText("最高匹配分数：—　阈值起始值（未校准）：37.0%").assertIsDisplayed()
        composeRule.onNodeWithText("没有检测框；模型建议 NG，匹配分数为空。").assertIsDisplayed()
    }

    @Test
    fun unsupportedFeatureDoesNotShowModelDecisionOrAppliedThreshold() {
        val roi = RoiDefinitionEntity(
            id = "roi-feature",
            templateId = "template-compose",
            name = "部件 ROI",
            order = 0,
            normalizedRect = "{}",
            inspectionType = "FEATURE",
            targetType = "FEATURE"
        )
        val result = NanoDetRoiInferenceResult(
            roiId = roi.id,
            status = NanoDetInferenceStatus.FEATURE_UNSUPPORTED,
            modelSuggestion = null,
            matchingScore = null,
            targetClassIndex = null,
            threshold = 0.37f
        )
        composeRule.setContent {
            MaterialTheme { RoiConfirmCard(roi, null, result, null) {} }
        }

        composeRule.onNodeWithText("检测状态：部件类别暂不支持").assertIsDisplayed()
        composeRule.onNodeWithText("目标类别：无　模型建议（仅参考）：无").assertIsDisplayed()
        composeRule.onNodeWithText("最高匹配分数：—　阈值起始值（未校准）：未执行").assertIsDisplayed()
    }
}
