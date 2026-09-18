package com.wearable.inspection.mobile.ui.screens

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock

/** DPM 页面退出的真实行为顺序测试，不依赖源码字符串。 */
class DpmScanExitFlowTest {

    @Test
    fun `exit with null frames cleans camera directly without saveEvidenceInScope`() {
        val events = mutableListOf<String>()

        runDpmScanExit(
            sessionId = "session-1",
            getEvidenceFrames = {
                events += "getEvidenceFrames"
                null
            },
            saveEvidenceInScope = { _, _, _ -> events += "unexpected-save" },
            stopScan = { events += "stopScan" },
            cleanupAndDisconnect = { sessionId ->
                events += "cleanupAndDisconnect:$sessionId"
            },
        )

        assertEquals(
            listOf(
                "getEvidenceFrames",
                "stopScan",
                "cleanupAndDisconnect:session-1",
            ),
            events,
        )
    }

    @Test
    fun `exit with evidence frames saves then cleans`() {
        val events = mutableListOf<String>()
        val mockBitmap = mock(Bitmap::class.java)
        org.mockito.Mockito.`when`(mockBitmap.isRecycled).thenReturn(false)
        val fakeFrames = com.wearable.inspection.mobile.dpm.DpmFrameAnalyzer.EvidenceFrames(
            bitmap = mockBitmap,
            roi = null,
            isDecodeSuccess = true,
            decodedCode = "test",
            decodeSource = null,
        )

        runDpmScanExit(
            sessionId = "session-1",
            getEvidenceFrames = {
                events += "getEvidenceFrames"
                fakeFrames
            },
            saveEvidenceInScope = { sessionId, _, afterSave ->
                events += "saveEvidence:$sessionId"
                runBlocking { afterSave() }
            },
            stopScan = { events += "stopScan" },
            cleanupAndDisconnect = { sessionId ->
                events += "cleanupAndDisconnect:$sessionId"
            },
        )

        assertEquals(
            listOf(
                "getEvidenceFrames",
                "saveEvidence:session-1",
                "stopScan",
                "cleanupAndDisconnect:session-1",
            ),
            events,
        )
    }

    @Test
    fun `exit without session only cleans and never reports a save`() {
        val events = mutableListOf<String>()

        runDpmScanExit(
            sessionId = null,
            getEvidenceFrames = {
                events += "getEvidenceFrames"
                null
            },
            saveEvidenceInScope = { _, _, _ -> events += "unexpected-save" },
            stopScan = { events += "stopScan" },
            cleanupAndDisconnect = { events += "unexpected-cleanup" },
        )

        assertEquals(listOf("getEvidenceFrames", "stopScan"), events)
    }
}
