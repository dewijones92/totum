package com.dewijones92.totum.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReportReceiptTest {
    @get:Rule val folder = TemporaryFolder()

    private fun report(): File = folder.newFile("1700000000000-abc.json").also { it.writeText("{}") }

    @Test
    fun `no receipt until the sink has accepted the report`() {
        assertNull(ReportReceipt(report()).read())
    }

    @Test
    fun `a receipt keeps the report id and the attempts, beside the report and not as a report`() {
        val file = report()

        ReportReceipt(file).write(Receipt("20261114T120000-abcd1234", stateAttempts = 3))

        assertEquals(Receipt("20261114T120000-abcd1234", 3), ReportReceipt(file).read())
        assertEquals(listOf(file.name), file.parentFile.listFiles { f -> f.extension == "json" }!!.map { it.name })
    }

    @Test
    fun `a receipt with no id is kept as no id`() {
        val file = report()

        ReportReceipt(file).write(Receipt(null, stateAttempts = 1))

        assertEquals(Receipt(null, 1), ReportReceipt(file).read())
    }

    @Test
    fun `a receipt that cannot be read says the report was accepted, with no id, rather than none`() {
        val file = report()
        File(file.parentFile, file.name + ".receipt").writeText("garbage\nnot a number\nmore")

        assertEquals(Receipt(null, 0), ReportReceipt(file).read())
    }

    @Test
    fun `deleting a receipt leaves the report, and deleting what is not there is harmless`() {
        val file = report()
        ReportReceipt(file).write(Receipt("20261114T120000-abcd1234", 1))

        ReportReceipt(file).delete()
        ReportReceipt(file).delete()

        assertNull(ReportReceipt(file).read())
        assertTrue(file.exists())
        assertFalse(File(file.parentFile, file.name + ".receipt").exists())
    }
}
