package com.wearable.inspection.mobile.dpm

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DpmEvidenceFrameTracker 真实行为测试。
 *
 * 覆盖：in-flight 保留、token 匹配、冻结保护、GRID 帧、cleanup 时序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DpmEvidenceFrameTrackerTest {

    private fun tracker() = DpmEvidenceFrameTracker()

    private fun bitmap() = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)

    private fun successResult(token: Long, code: String = "DPM-CODE") = DpmAnalyzeResult(
        status = DpmAnalyzeStatus.DECODED,
        code = code,
        source = DecodeSource.ZXING,
        sourceFrameToken = token,
        sourceFrameTimeMs = 1000L,
    )

    // ─── 测试 1：连续帧时序，帧 A 在分析中帧 B 到达，帧 A 仍可被选中 ───

    @Test
    fun `in-flight frame survives cleanup when next frame arrives`() {
        val t = tracker()
        val bitmapA = bitmap()
        val bitmapB = bitmap()

        // 帧 A 加入并标记 in-flight
        assertTrue(t.addFrame(1, 100, bitmapA, null))
        t.markInFlight(1)

        // 帧 B 加入（触发 cleanupUnusedLocked）
        assertTrue(t.addFrame(2, 200, bitmapB, null))

        // 帧 A 解码成功
        assertTrue(t.recordDecodeSuccess(successResult(1)))

        // freeze 应返回帧 A 的快照
        val snapshot = t.freeze()
        assertNotNull("in-flight frame must survive cleanup", snapshot)
        assertEquals(1, snapshot!!.frameToken)
        assertEquals("DPM-CODE", snapshot.decodedCode)
        assertTrue(snapshot.isDecodeSuccess)

        // 清理
        if (!bitmapB.isRecycled) bitmapB.recycle()
    }

    @Test
    fun `non-in-flight frame is cleaned up when next frame arrives`() {
        val t = tracker()
        val bitmapA = bitmap()
        val bitmapB = bitmap()

        // 帧 A 加入但不标记 in-flight
        assertTrue(t.addFrame(1, 100, bitmapA, null))
        // 帧 B 加入
        assertTrue(t.addFrame(2, 200, bitmapB, null))

        // 帧 A 解码成功但已被清理
        assertFalse(t.recordDecodeSuccess(successResult(1)))

        // freeze 应返回 null（无成功帧）
        assertNull(t.freeze())
        if (!bitmapB.isRecycled) bitmapB.recycle()
    }

    // ─── 测试 2：sourceFrameToken 正确匹配 ───

    @Test
    fun `recordDecodeSuccess matches correct token`() {
        val t = tracker()
        val b1 = bitmap()
        val b2 = bitmap()

        assertTrue(t.addFrame(10, 100, b1, null))
        assertTrue(t.addFrame(20, 200, b2, null))
        t.markInFlight(10)
        t.markInFlight(20)

        // 用 token=20 记录成功
        assertTrue(t.recordDecodeSuccess(successResult(20, "CODE-20")))
        val snapshot = t.freeze()!!
        assertEquals(20, snapshot.frameToken)
        assertEquals("CODE-20", snapshot.decodedCode)
    }

    // ─── 测试 3：token 不存在时的行为 ───

    @Test
    fun `recordDecodeSuccess fails when token not in entries`() {
        val t = tracker()
        val b = bitmap()
        assertTrue(t.addFrame(1, 100, b, null))

        // token=999 不存在
        assertFalse(t.recordDecodeSuccess(successResult(999)))
        assertNull(t.freeze())
    }

    @Test
    fun `recordDecodeSuccess fails when sourceFrameToken is null`() {
        val t = tracker()
        val b = bitmap()
        assertTrue(t.addFrame(1, 100, b, null))

        val result = DpmAnalyzeResult(
            status = DpmAnalyzeStatus.DECODED,
            code = "CODE",
            source = DecodeSource.ZXING,
            sourceFrameToken = null,
        )
        assertFalse(t.recordDecodeSuccess(result))
    }

    // ─── 测试 4：GRID 异步解码成功源帧保存 ───

    @Test
    fun `grid pending frame survives cleanup and can be selected`() {
        val t = tracker()
        val b1 = bitmap()
        val b2 = bitmap()

        assertTrue(t.addFrame(1, 100, b1, null))
        t.markGridSubmitted(1)

        // 帧 B 到达，帧 A 因 pendingGrid 保留
        assertTrue(t.addFrame(2, 200, b2, null))

        // 帧 A GRID 解码成功
        assertTrue(t.recordDecodeSuccess(successResult(1)))

        val snapshot = t.freeze()!!
        assertEquals(1, snapshot.frameToken)

        if (!b2.isRecycled) b2.recycle()
    }

    // ─── 测试 5：Tracker 冻结后不接受新帧和新结果 ───

    @Test
    fun `frozen tracker rejects new frames`() {
        val t = tracker()
        t.freeze()

        val b = bitmap()
        assertFalse(t.addFrame(1, 100, b, null))
        // bitmap should be recycled by addFrame
    }

    @Test
    fun `frozen tracker rejects recordDecodeSuccess`() {
        val t = tracker()
        val b = bitmap()
        assertTrue(t.addFrame(1, 100, b, null))
        t.markInFlight(1)

        t.freeze()

        assertFalse(t.recordDecodeSuccess(successResult(1)))
    }

    @Test
    fun `freeze returns null on second call`() {
        val t = tracker()
        val b = bitmap()
        assertTrue(t.addFrame(1, 100, b, null))
        t.markInFlight(1)
        assertTrue(t.recordDecodeSuccess(successResult(1)))

        assertNotNull(t.freeze())
        assertNull(t.freeze())
    }

    // ─── 测试 6：unmarkInFlight 后帧可被正常清理 ───

    @Test
    fun `unmarkInFlight allows cleanup on next addFrame`() {
        val t = tracker()
        val b1 = bitmap()
        val b2 = bitmap()

        assertTrue(t.addFrame(1, 100, b1, null))
        t.markInFlight(1)

        // 分析完成，取消 in-flight
        t.unmarkInFlight(1)

        // 帧 B 到达，帧 A 现在可被清理
        assertTrue(t.addFrame(2, 200, b2, null))

        // 帧 A 已被清理，解码成功无法记录
        assertFalse(t.recordDecodeSuccess(successResult(1)))

        if (!b2.isRecycled) b2.recycle()
    }

    // ─── 测试 7：only first success is recorded ───

    @Test
    fun `only first decode success is recorded`() {
        val t = tracker()
        val b1 = bitmap()
        val b2 = bitmap()

        assertTrue(t.addFrame(1, 100, b1, null))
        t.markInFlight(1)
        assertTrue(t.addFrame(2, 200, b2, null))
        t.markInFlight(2)

        assertTrue(t.recordDecodeSuccess(successResult(1, "FIRST")))
        // 第二次记录返回 true（已记录）但不覆盖
        assertTrue(t.recordDecodeSuccess(successResult(2, "SECOND")))

        val snapshot = t.freeze()!!
        assertEquals(1, snapshot.frameToken)
        assertEquals("FIRST", snapshot.decodedCode)
    }

    // ─── 测试 8：DECODED 但 code 为空不记录 ───

    @Test
    fun `decoded with blank code is rejected`() {
        val t = tracker()
        val b = bitmap()
        assertTrue(t.addFrame(1, 100, b, null))
        t.markInFlight(1)

        val result = DpmAnalyzeResult(
            status = DpmAnalyzeStatus.DECODED,
            code = "",
            source = DecodeSource.ZXING,
            sourceFrameToken = 1,
        )
        assertFalse(t.recordDecodeSuccess(result))
    }

    // ─── 测试 9：非 DECODED 状态不记录 ───

    @Test
    fun `non-DECODED status is rejected`() {
        val t = tracker()
        val b = bitmap()
        assertTrue(t.addFrame(1, 100, b, null))
        t.markInFlight(1)

        val result = DpmAnalyzeResult(
            status = DpmAnalyzeStatus.NO_CODE,
            sourceFrameToken = 1,
        )
        assertFalse(t.recordDecodeSuccess(result))
    }
}