package com.wearable.inspection.mobile.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 真实验证 Unit-key effect 在连接回调后销毁时读取最新 sessionId。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DpmScanExitLifecycleComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ViewConfirmationTestActivity>()

    @Test
    fun `CameraPreview connection followed by page disposal uses latest session`() {
        val pageVisible = mutableStateOf(true)
        val connectedSessionId = mutableStateOf<String?>(null)
        val disposedSessionIds = mutableListOf<String?>()
        val onConnected: (String) -> Unit = { connectedSessionId.value = it }

        composeRule.setContent {
            MaterialTheme {
                if (pageVisible.value) {
                    DpmScanExitEffect(connectedSessionId.value) { disposedSessionIds += it }
                }
            }
        }

        composeRule.runOnIdle { onConnected("session-connected") }
        composeRule.runOnIdle { pageVisible.value = false }
        composeRule.runOnIdle {
            assertEquals(listOf("session-connected"), disposedSessionIds)
        }
    }
}
