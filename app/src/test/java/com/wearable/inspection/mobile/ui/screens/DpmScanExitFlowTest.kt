package com.wearable.inspection.mobile.ui.screens

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** DPM 页面退出的真实行为顺序测试，不依赖源码字符串。 */
class DpmScanExitFlowTest {

    @Test
    fun `exit snapshots evidence before save and cleans camera after save`() {
        val events = mutableListOf<String>()

        runDpmScanExit(
            sessionId = "session-1",
            getEvidenceFrames = {
                events += "getEvidenceFrames"
                null
            },
            saveEvidenceInScope = { sessionId, _, afterSave ->
                events += "saveEvidence:$sessionId"
                runBlocking { afterSave() }
            },
            stopScan = { events += "stopScan" },
            clearFrameAnalyzer = { events += "clearFrameAnalyzer" },
            disconnect = { sessionId ->
                events += "disconnect:$sessionId"
                true
            },
        )

        assertEquals(
            listOf(
                "getEvidenceFrames",
                "saveEvidence:session-1",
                "stopScan",
                "clearFrameAnalyzer",
                "disconnect:session-1",
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
            clearFrameAnalyzer = { events += "unexpected-clear" },
            disconnect = { events += "unexpected-disconnect"; true },
        )

        assertEquals(listOf("getEvidenceFrames", "stopScan"), events)
    }
}
