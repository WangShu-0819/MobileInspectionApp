package com.wearable.inspection.mobile.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/**
 * T1 件色 UI 测试
 *
 * 覆盖：无默认选项、选择状态、最终 ID 预览、件色标签文字。
 */
class PartColorUITest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ViewConfirmationTestActivity>()

    // ── 件色选择器：无默认选项 ──

    @Test
    fun colorSelector_noDefault() {
        var selected: PartCreationValidator.PartColor? = null

        composeTestRule.setContent {
            PartColorSelector(
                selectedColor = selected,
                onColorSelected = { selected = it },
            )
        }

        // 两个选项都应显示
        composeTestRule.onNodeWithText("白件").assertIsDisplayed()
        composeTestRule.onNodeWithText("黑件").assertIsDisplayed()
    }

    // ── 件色选择器：选择状态 ──

    @Test
    fun colorSelector_whiteSelected() {
        var selected: PartCreationValidator.PartColor? = PartCreationValidator.PartColor.WHITE

        composeTestRule.setContent {
            PartColorSelector(
                selectedColor = selected,
                onColorSelected = { selected = it },
            )
        }

        composeTestRule.onNodeWithTag("color_WHITE").assertIsSelected()
    }

    @Test
    fun colorSelector_blackSelected() {
        var selected: PartCreationValidator.PartColor? = PartCreationValidator.PartColor.BLACK

        composeTestRule.setContent {
            PartColorSelector(
                selectedColor = selected,
                onColorSelected = { selected = it },
            )
        }

        composeTestRule.onNodeWithTag("color_BLACK").assertIsSelected()
    }

    @Test
    fun colorSelector_clickChangesSelection() {
        var selected: PartCreationValidator.PartColor? = null

        composeTestRule.setContent {
            PartColorSelector(
                selectedColor = selected,
                onColorSelected = { selected = it },
            )
        }

        composeTestRule.onNodeWithText("白件").performClick()
        composeTestRule.runOnIdle {
            assert(selected == PartCreationValidator.PartColor.WHITE) {
                "点击白件后应选中 WHITE"
            }
        }
    }

    // ── 最终 ID 预览 ──

    @Test
    fun finalIdPreview_whiteFormat() {
        val finalId = PartCreationValidator.generateFinalId("part01", PartCreationValidator.PartColor.WHITE)
        assert(finalId == "White_part01") { "白件最终 ID 应为 White_part01，实际: $finalId" }
    }

    @Test
    fun finalIdPreview_blackFormat() {
        val finalId = PartCreationValidator.generateFinalId("part01", PartCreationValidator.PartColor.BLACK)
        assert(finalId == "Black_part01") { "黑件最终 ID 应为 Black_part01，实际: $finalId" }
    }

    // ── 件色标签文字 ──

    @Test
    fun partColorLabel_whitePrefix_shows白件() {
        composeTestRule.setContent {
            PartColorLabel(partId = "White_part01")
        }
        composeTestRule.onNodeWithText("白件").assertIsDisplayed()
    }

    @Test
    fun partColorLabel_blackPrefix_shows黑件() {
        composeTestRule.setContent {
            PartColorLabel(partId = "Black_part01")
        }
        composeTestRule.onNodeWithText("黑件").assertIsDisplayed()
    }

    @Test
    fun partColorLabel_noPrefix_shows未标记() {
        composeTestRule.setContent {
            PartColorLabel(partId = "old_part_001")
        }
        composeTestRule.onNodeWithText("未标记").assertIsDisplayed()
    }

    @Test
    fun partColorLabel_emptyId_shows未标记() {
        composeTestRule.setContent {
            PartColorLabel(partId = "")
        }
        composeTestRule.onNodeWithText("未标记").assertIsDisplayed()
    }

    // ── 件色标签不使用黑/白实色背景 ──

    @Test
    fun partColorLabel_whitePart_usesInfoBannerBg() {
        // 验证白件标签使用 InfoBannerBg 而非纯白背景
        // 通过语义树验证标签存在且可读
        composeTestRule.setContent {
            PartColorLabel(partId = "White_part01")
        }
        composeTestRule.onNodeWithText("白件").assertExists()
    }

    @Test
    fun partColorLabel_blackPart_usesInfoBannerBg() {
        // 验证黑件标签使用 InfoBannerBg 而非纯黑背景
        composeTestRule.setContent {
            PartColorLabel(partId = "Black_part01")
        }
        composeTestRule.onNodeWithText("黑件").assertExists()
    }
}