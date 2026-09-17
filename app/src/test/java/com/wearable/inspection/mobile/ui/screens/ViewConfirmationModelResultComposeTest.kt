package com.wearable.inspection.mobile.ui.screens

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected

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

/**
 * 确认页 ROI 卡片 UI 测试
 *
 * 验证：
 * - 模型推理细节（UUID、检测状态、目标类别、匹配分数、阈值、模型版本、检测框叠加）均不可见
 * - ROI 图片显示原始裁剪图（无检测框叠加）
 * - statusHint 正确显示（FEATURE→"部件类别暂不支持"、NO_DETECTION→"未检出"、DETECTED/BELOW→无）
 * - 人工 OK/NG 选择独立于模型结果
 * - 模型 OK/NG 默认选中；FEATURE/未执行不默认选中且不自动判 NG
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ViewConfirmationModelResultComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ViewConfirmationTestActivity>()

    private val threadRoi = RoiDefinitionEntity(
        id = "roi-compose", templateId = "template-compose", name = "螺纹 ROI",
        order = 0, normalizedRect = "{}", inspectionType = "THREAD", targetType = "THREAD"
    )

    private val featureRoi = RoiDefinitionEntity(
        id = "roi-feature", templateId = "template-compose", name = "部件 ROI",
        order = 0, normalizedRect = "{}", inspectionType = "FEATURE", targetType = "FEATURE"
    )

    // --- 模型 DETECTED OK → 默认选中 OK，无模型细节 ---

    @Test
    fun detectedModelOkDefaultSelectsOkAndShowsNoModelDetails() {
        val result = NanoDetRoiInferenceResult(
            roiId = threadRoi.id,
            status = NanoDetInferenceStatus.DETECTED,
            modelSuggestion = NanoDetSuggestion.OK,
            matchingScore = 0.89f,
            targetClassIndex = 1,
            threshold = 0.37f,
            roiBounds = listOf(0, 0, 100, 100),
            detections = listOf(
                NanoDetDetection(
                    classIndex = 1, className = "thread", score = 0.89f, point = 10,
                    roiBox = NanoDetBox(4.0, 5.0, 50.0, 60.0),
                    imageBox = NanoDetBox(4.0, 5.0, 50.0, 60.0)
                )
            )
        )
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        // selectedResult="OK" 模拟 ViewModel applyDefaultSelections 传入模型默认值
        val humanResult = mutableStateOf<String?>("OK")
        composeRule.setContent {
            MaterialTheme {
                RoiConfirmCard(threadRoi, bitmap, result, humanResult.value) { humanResult.value = it }
            }
        }

        // 模型细节全部不可见
        composeRule.onNodeWithText("检测状态：已检出，达到模型阈值").assertDoesNotExist()
        composeRule.onNodeWithText("最高匹配分数：").assertDoesNotExist()
        composeRule.onNodeWithText("阈值起始值").assertDoesNotExist()
        composeRule.onNodeWithText("检测框").assertDoesNotExist()
        composeRule.onNodeWithText("目标类别").assertDoesNotExist()
        composeRule.onNodeWithText("模型建议").assertDoesNotExist()
        composeRule.onNodeWithText("模型版本").assertDoesNotExist()
        // UUID 不可见
        composeRule.onNodeWithText("ID: roi-compose").assertDoesNotExist()
        composeRule.onNodeWithText("roi-compose").assertDoesNotExist()
        // DETECTED 无 statusHint
        composeRule.onNodeWithTag("inference-status-${threadRoi.id}").assertDoesNotExist()

        // 应显示 ROI 编号和类型，不显示名称
        composeRule.onNodeWithText("ROI 1").assertIsDisplayed()
        composeRule.onNodeWithText("ROI 类型：螺纹").assertIsDisplayed()
        composeRule.onNodeWithText("螺纹 ROI").assertDoesNotExist()

        // 默认选中 OK（来自模型）
        val ok = composeRule.onNodeWithTag("human-result-${threadRoi.id}-OK")
        val ng = composeRule.onNodeWithTag("human-result-${threadRoi.id}-NG")
        ok.assertIsSelected()
        ng.assertIsNotSelected()

        // 人工可改判为 NG
        ng.performClick()
        composeRule.runOnIdle { assertEquals("NG", humanResult.value) }
        ng.assertIsSelected()
        ok.assertIsNotSelected()

        // 改回 OK
        ok.performClick()
        composeRule.runOnIdle { assertEquals("OK", humanResult.value) }
        ok.assertIsSelected()
    }

    // --- 模型 DETECTED NG → 默认选中 NG ---

    @Test
    fun detectedModelNgDefaultSelectsNg() {
        val result = NanoDetRoiInferenceResult(
            roiId = threadRoi.id,
            status = NanoDetInferenceStatus.DETECTED_BELOW_THRESHOLD,
            modelSuggestion = NanoDetSuggestion.NG,
            matchingScore = 0.2f,
            targetClassIndex = 1,
            threshold = 0.37f
        )
        // selectedResult="NG" 模拟 ViewModel applyDefaultSelections 传入模型默认值
        composeRule.setContent {
            MaterialTheme { RoiConfirmCard(threadRoi, null, result, "NG") {} }
        }

        val ok = composeRule.onNodeWithTag("human-result-${threadRoi.id}-OK")
        val ng = composeRule.onNodeWithTag("human-result-${threadRoi.id}-NG")
        ok.assertIsNotSelected()
        ng.assertIsSelected()
    }

    // --- NO_DETECTION → "未检出"提示，默认选中 NG ---

    @Test
    fun noDetectionShowsHintAndDefaultsToNg() {
        val result = NanoDetRoiInferenceResult(
            roiId = threadRoi.id,
            status = NanoDetInferenceStatus.NO_DETECTION,
            modelSuggestion = NanoDetSuggestion.NG,
            matchingScore = null,
            targetClassIndex = 1,
            threshold = 0.37f
        )
        composeRule.setContent {
            MaterialTheme { RoiConfirmCard(threadRoi, null, result, "NG") {} }
        }

        composeRule.onNodeWithText("未检出").assertIsDisplayed()
        composeRule.onNodeWithText("检测状态：未检出").assertDoesNotExist()
        composeRule.onNodeWithText("最高匹配分数").assertDoesNotExist()

        val ok = composeRule.onNodeWithTag("human-result-${threadRoi.id}-OK")
        val ng = composeRule.onNodeWithTag("human-result-${threadRoi.id}-NG")
        ok.assertIsNotSelected()
        ng.assertIsSelected()
    }

    // --- FEATURE_UNSUPPORTED → "部件类别暂不支持"，不默认选中 ---

    @Test
    fun unsupportedFeatureShowsHintAndDoesNotDefaultSelect() {
        val result = NanoDetRoiInferenceResult(
            roiId = featureRoi.id,
            status = NanoDetInferenceStatus.FEATURE_UNSUPPORTED,
            modelSuggestion = null,
            matchingScore = null,
            targetClassIndex = null,
            threshold = 0.37f
        )
        // selectedResult=null 模拟 ViewModel 不对 FEATURE 设置默认选中
        composeRule.setContent {
            MaterialTheme { RoiConfirmCard(featureRoi, null, result, null) {} }
        }

        composeRule.onNodeWithText("部件类别暂不支持").assertIsDisplayed()
        composeRule.onNodeWithText("检测状态：部件类别暂不支持").assertDoesNotExist()
        composeRule.onNodeWithText("目标类别").assertDoesNotExist()
        composeRule.onNodeWithText("最高匹配分数").assertDoesNotExist()
        composeRule.onNodeWithText("阈值起始值").assertDoesNotExist()

        // 不默认选中，两个按钮均未选中
        val ok = composeRule.onNodeWithTag("human-result-${featureRoi.id}-OK")
        val ng = composeRule.onNodeWithTag("human-result-${featureRoi.id}-NG")
        ok.assertIsNotSelected()
        ng.assertIsNotSelected()
    }

    // --- 无推理结果（null inference）→ "模型未执行"，不默认选中 ---

    @Test
    fun nullInferenceShowsModelNotExecutedAndDoesNotDefaultSelect() {
        composeRule.setContent {
            MaterialTheme { RoiConfirmCard(threadRoi, null, null, null) {} }
        }

        composeRule.onNodeWithText("模型未执行").assertIsDisplayed()
        // 无模型细节
        composeRule.onNodeWithText("检测状态").assertDoesNotExist()
        composeRule.onNodeWithText("最高匹配分数").assertDoesNotExist()
        composeRule.onNodeWithText("目标类别").assertDoesNotExist()

        val ok = composeRule.onNodeWithTag("human-result-${threadRoi.id}-OK")
        val ng = composeRule.onNodeWithTag("human-result-${threadRoi.id}-NG")
        ok.assertIsNotSelected()
        ng.assertIsNotSelected()
    }

    // --- 已保存人工选择优先：模型 OK 但已存 NG → 保持 NG ---

    @Test
    fun savedHumanResultOverridesModelDefault() {
        val result = NanoDetRoiInferenceResult(
            roiId = threadRoi.id,
            status = NanoDetInferenceStatus.DETECTED,
            modelSuggestion = NanoDetSuggestion.OK,
            matchingScore = 0.89f,
            targetClassIndex = 1,
            threshold = 0.37f
        )
        // selectedResult="NG" 模拟 ViewModel restoreManualSelections 传入已保存值
        composeRule.setContent {
            MaterialTheme { RoiConfirmCard(threadRoi, null, result, "NG") {} }
        }

        val ok = composeRule.onNodeWithTag("human-result-${threadRoi.id}-OK")
        val ng = composeRule.onNodeWithTag("human-result-${threadRoi.id}-NG")
        // 已保存 NG 优先于模型 OK
        ok.assertIsNotSelected()
        ng.assertIsSelected()
    }
}
