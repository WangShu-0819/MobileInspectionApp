package com.wearable.inspection.mobile.detection

import com.wearable.inspection.mobile.registration.RegistrationStatus
import com.wearable.inspection.mobile.ui.screens.NormalizedRect
import com.wearable.inspection.mobile.ui.screens.SessionRoi
import com.wearable.inspection.mobile.ui.screens.SessionRoiRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionRoiRegistryTest {

    private fun roi(id: String, name: String = "ROI_$id") = SessionRoi(
        id = id,
        name = name,
        rect = NormalizedRect(0.1f, 0.2f, 0.5f, 0.6f),
    )

    @Test
    fun `write and readAndConsume returns entry`() {
        SessionRoiRegistry.clearAll()
        val rois = listOf(roi("r1"), roi("r2"))

        SessionRoiRegistry.write("b1", 100L, 0, rois, RegistrationStatus.SUCCESS)
        val entry = SessionRoiRegistry.readAndConsume("b1", 100L, 0)

        assertNotNull(entry)
        assertEquals(RegistrationStatus.SUCCESS, entry.registrationStatus)
        assertEquals(2, entry.sessionRois.size)
        assertEquals("r1", entry.sessionRois[0].id)
        assertEquals("r2", entry.sessionRois[1].id)
    }

    @Test
    fun `readAndConsume removes entry (consume semantics)`() {
        SessionRoiRegistry.clearAll()
        SessionRoiRegistry.write("b1", 100L, 0, listOf(roi("r1")), RegistrationStatus.SUCCESS)

        val first = SessionRoiRegistry.readAndConsume("b1", 100L, 0)
        val second = SessionRoiRegistry.readAndConsume("b1", 100L, 0)

        assertNotNull(first)
        assertNull(second, "Second read must return null (consumed)")
    }

    @Test
    fun `readAndConsume returns null for missing key`() {
        SessionRoiRegistry.clearAll()

        val entry = SessionRoiRegistry.readAndConsume("nonexistent", 999L, 0)

        assertNull(entry)
    }

    @Test
    fun `write overwrites previous entry`() {
        SessionRoiRegistry.clearAll()
        SessionRoiRegistry.write("b1", 100L, 0, listOf(roi("old")), RegistrationStatus.FAILED)
        SessionRoiRegistry.write("b1", 100L, 0, listOf(roi("new1"), roi("new2")), RegistrationStatus.SUCCESS)

        val entry = SessionRoiRegistry.readAndConsume("b1", 100L, 0)

        assertNotNull(entry)
        assertEquals(RegistrationStatus.SUCCESS, entry.registrationStatus)
        assertEquals(2, entry.sessionRois.size)
        assertEquals("new1", entry.sessionRois[0].id)
    }

    @Test
    fun `different keys are independent`() {
        SessionRoiRegistry.clearAll()
        SessionRoiRegistry.write("b1", 100L, 0, listOf(roi("a")), RegistrationStatus.SUCCESS)
        SessionRoiRegistry.write("b2", 200L, 1, listOf(roi("b")), RegistrationStatus.FALLBACK_FULL_IMAGE)

        val entryA = SessionRoiRegistry.readAndConsume("b1", 100L, 0)
        val entryB = SessionRoiRegistry.readAndConsume("b2", 200L, 1)

        assertNotNull(entryA)
        assertNotNull(entryB)
        assertEquals("a", entryA.sessionRois[0].id)
        assertEquals("b", entryB.sessionRois[0].id)
        assertEquals(RegistrationStatus.SUCCESS, entryA.registrationStatus)
        assertEquals(RegistrationStatus.FALLBACK_FULL_IMAGE, entryB.registrationStatus)
    }

    @Test
    fun `clear removes specific entry`() {
        SessionRoiRegistry.clearAll()
        SessionRoiRegistry.write("b1", 100L, 0, listOf(roi("r1")), RegistrationStatus.SUCCESS)
        SessionRoiRegistry.clear("b1", 100L, 0)

        val entry = SessionRoiRegistry.readAndConsume("b1", 100L, 0)
        assertNull(entry)
    }

    @Test
    fun `clearAll removes all entries`() {
        SessionRoiRegistry.clearAll()
        SessionRoiRegistry.write("b1", 100L, 0, listOf(roi("a")), RegistrationStatus.SUCCESS)
        SessionRoiRegistry.write("b2", 200L, 1, listOf(roi("b")), RegistrationStatus.SUCCESS)
        SessionRoiRegistry.clearAll()

        assertNull(SessionRoiRegistry.readAndConsume("b1", 100L, 0))
        assertNull(SessionRoiRegistry.readAndConsume("b2", 200L, 1))
    }

    @Test
    fun `readAndConsume is stable for repeated calls on missing key`() {
        SessionRoiRegistry.clearAll()

        val first = SessionRoiRegistry.readAndConsume("b1", 100L, 0)
        val second = SessionRoiRegistry.readAndConsume("b1", 100L, 0)

        assertNull(first)
        assertNull(second)
    }

    @Test
    fun `registration status is preserved`() {
        SessionRoiRegistry.clearAll()

        for (status in RegistrationStatus.entries) {
            SessionRoiRegistry.write("b_$status", 1L, 0, listOf(roi("r")), status)
            val entry = SessionRoiRegistry.readAndConsume("b_$status", 1L, 0)
            assertNotNull(entry)
            assertEquals(status, entry.registrationStatus)
        }
    }
}
