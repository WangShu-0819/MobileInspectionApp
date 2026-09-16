package com.wearable.inspection.mobile.data.export

import android.content.Context
import com.wearable.inspection.mobile.data.entity.DpmScanEvidenceEntity
import com.wearable.inspection.mobile.data.repository.InspectionRepository
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

class DpmEvidenceExportArchiveTest {

    @Test
    fun `export writes frame and readable manifest into finalized zip`() = runTest {
        val directory = Files.createTempDirectory("dpm-evidence-export").toFile()
        val frameBytes = byteArrayOf(1, 2, 3, 4)
        val roiBytes = byteArrayOf(4, 3, 2, 1)
        val frameFile = File(directory, "frame.jpg").apply { writeBytes(frameBytes) }
        val roiFile = File(directory, "roi.jpg").apply { writeBytes(roiBytes) }
        val noReadFile = File(directory, "no-read.jpg").apply { writeBytes(byteArrayOf(9, 9)) }
        val evidence = DpmScanEvidenceEntity(
            id = 9,
            scanSessionId = "session-test",
            frameTimeMs = 1234L,
            frameSource = "CAMERA",
            decodedContent = "CODE-1",
            status = "SUCCESS",
            decodeSource = "ZXING",
            originalImagePath = frameFile.absolutePath,
            roiImagePath = roiFile.absolutePath,
        )
        val noReadEvidence = evidence.copy(
            id = 10,
            scanSessionId = "session-no-read",
            decodedContent = null,
            status = "NO_READ",
            decodeSource = null,
            originalImagePath = noReadFile.absolutePath,
        )
        val independentEvidence = evidence.copy(
            id = 11,
            scanSessionId = "session-independent",
            batchId = null,
        )
        val repository = Mockito.mock(InspectionRepository::class.java)
        `when`(repository.getAllDpmScanEvidence()).thenReturn(
            listOf(evidence, noReadEvidence, independentEvidence)
        )
        val outputFile = File(directory, "evidence.zip")

        val result = DpmEvidenceExportService(
            Mockito.mock(Context::class.java),
            repository
        ).exportEvidenceZip(outputFile)

        assertTrue("Expected successful ZIP export, got $result", result is DpmEvidenceExportResult.Success)
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(FileInputStream(outputFile)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val framePath = "sessions/session-test/frame_9_1234.jpg"
        val roiPath = "sessions/session-test/roi_9_1234.jpg"
        assertArrayEquals(frameBytes, entries[framePath])
        assertArrayEquals(roiBytes, entries[roiPath])
        val manifest = entries["manifest.csv"]?.toString(Charsets.UTF_8)
        assertTrue("ZIP must contain manifest.csv", manifest != null)
        assertTrue(manifest!!.contains("scanSessionId,frameTimeMs"))
        assertTrue(manifest.contains("session-test,1234,CAMERA,SUCCESS,CODE-1,ZXING"))
        assertTrue("无 batchId 的合法证据仍应进入独立 ZIP", manifest.contains("session-independent"))
        assertArrayEquals(frameBytes, entries["sessions/session-independent/frame_11_1234.jpg"])
        assertTrue("NO_READ 记录不得进入独立成功证据 ZIP", !manifest.contains("session-no-read"))
        assertEquals(5, entries.size)

        outputFile.delete()
        frameFile.delete()
        roiFile.delete()
        noReadFile.delete()
        directory.delete()
    }

    @Test
    fun `empty export removes a precreated target and never returns success`() = runTest {
        val directory = Files.createTempDirectory("dpm-evidence-empty").toFile()
        val target = File(directory, "precreated.zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val repository = Mockito.mock(InspectionRepository::class.java)
        `when`(repository.getAllDpmScanEvidence()).thenReturn(
            listOf(
                DpmScanEvidenceEntity(
                    scanSessionId = "no-success",
                    frameTimeMs = 1L,
                    frameSource = "CAMERA",
                    decodedContent = null,
                    status = "NO_READ",
                    decodeSource = null,
                    originalImagePath = File(directory, "missing.jpg").absolutePath,
                )
            )
        )

        val result = DpmEvidenceExportService(Mockito.mock(Context::class.java), repository)
            .exportEvidenceZip(target)

        assertTrue(result === DpmEvidenceExportResult.Empty)
        assertTrue("Empty 导出必须清理 SAF/缓存预创建目标", !target.exists())
        directory.delete()
    }
}
