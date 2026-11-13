package com.dewijones92.totum.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashRecordsTest {

    @get:Rule val folder = TemporaryFolder()

    private fun records(): CrashRecords = CrashRecords(File(folder.root, "last-crash.json"))

    private fun failure(): Throwable = IllegalStateException(
        "the player was released",
        java.io.IOException("socket closed"),
    )

    @Test
    fun `a crash is recorded with its whole stack trace, its causes and the report it wrote`() {
        val store = records()

        store.write(CrashRecords.recordOf(failure(), thread = "main", reportName = "17-abc.json", atEpochMs = 1_000L))

        val record = store.pending()
        assertNotNull(record)
        assertEquals("java.lang.IllegalStateException", record!!.exception)
        assertEquals("the player was released", record.message)
        assertEquals("main", record.thread)
        assertEquals("17-abc.json", record.reportName)
        assertEquals(1_000L, record.atEpochMs)
        assertTrue(record.stackTrace.contains("CrashRecordsTest"))
        assertTrue(record.stackTrace.contains("Caused by: java.io.IOException: socket closed"))
    }

    @Test
    fun `nothing is pending until a crash is recorded, and clearing forgets it`() {
        val store = records()
        assertNull(store.pending())

        store.write(CrashRecords.recordOf(failure(), "main", null, 5L))
        assertNotNull(store.pending())

        store.clear()
        assertNull(store.pending())
        assertFalse(File(folder.root, "last-crash.json").exists())
    }

    @Test
    fun `a record that cannot be read is treated as no crash rather than crashing the launch`() {
        File(folder.root, "last-crash.json").writeText("{ not json")

        assertNull(records().pending())
    }

    @Test
    fun `a newer crash replaces an older one that was never looked at`() {
        val store = records()
        store.write(CrashRecords.recordOf(RuntimeException("first"), "main", null, 1L))
        store.write(CrashRecords.recordOf(RuntimeException("second"), "worker", null, 2L))

        assertEquals("second", store.pending()?.message)
    }

    @Test
    fun `an error with no message still records`() {
        val record = CrashRecords.recordOf(NullPointerException(), "main", null, 1L)

        assertEquals("", record.message)
        assertEquals("java.lang.NullPointerException", record.exception)
    }

    @Test
    fun `the crash screen is offered for a pending crash, but not in a test run`() {
        val record = CrashRecords.recordOf(failure(), "main", null, 1L)

        assertTrue(CrashRecords.offer(record, underInstrumentation = false))
        assertFalse(CrashRecords.offer(record, underInstrumentation = true))
        assertFalse(CrashRecords.offer(null, underInstrumentation = false))
    }

    @Test
    fun `the note given to GitHub names the crash without the stack trace, which travels separately`() {
        val record = CrashRecords.recordOf(failure(), "main", null, 1L)

        val note = CrashRecords.noteFor(record)

        assertTrue(note, note.startsWith("Totum crashed: java.lang.IllegalStateException: the player was released"))
        assertFalse(note, note.contains("at com."))
    }
}
