package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.common.GitHubReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportSourcesTest {

    private val redactor = reportRedactorFor(
        homeServerBase = "pi.example.org",
        homeServerToken = "tok-abcdef-123456",
        prowlarrKey = "prowl-key-987654",
        builtInBase = "built.example.net",
        sinkEndpoint = "https://crashlog.sink.example.org/ingest",
    )

    @Test
    fun `every address the phone knows the home server by is removed from a report`() {
        val draft = GitHubReport.draft(
            note = "it failed at https://totum.pi.example.org/fetch and totumauth.built.example.net and " +
                "crashlog.sink.example.org with tok-abcdef-123456 and prowl-key-987654",
            facts = listOf(GitHubReport.VERSION to "0.1.1"),
            events = listOf(
                "12:00:00.000 [home] POST https://crashlog.sink.example.org/ingest from person@mail.example.org"
            ),
            redactor = redactor,
        )

        listOf(draft.body, draft.title, draft.url).forEach { text ->
            assertFalse(text, "example" in text)
            assertFalse(text, "tok-abcdef" in text)
            assertFalse(text, "prowl-key" in text)
            assertFalse(text, "person@" in text)
        }
    }

    @Test
    fun `a phone with no home server set still hides the built-in address and the sink`() {
        val bare = reportRedactorFor("", "", "", "built.example.net", "https://crashlog.sink.example.org/ingest")

        val out = bare.redact("totum.built.example.net and crashlog.sink.example.org")

        assertFalse(out, "example" in out)
    }

    @Test
    fun `opened reports are kept newest first and capped`() {
        var reports = emptyList<OpenedReport>()
        repeat(
            OpenedReportsCodec.KEEP + 5
        ) { reports = OpenedReportsCodec.added(reports, OpenedReport(it.toLong(), "t$it")) }

        assertEquals(OpenedReportsCodec.KEEP, reports.size)
        assertEquals("t${OpenedReportsCodec.KEEP + 4}", reports.first().title)
        assertTrue(reports.none { it.title == "t0" })
    }

    @Test
    fun `opened reports survive a round trip`() {
        val reports = listOf(OpenedReport(5, "Audio cuts out"), OpenedReport(4, "title with \"quotes\" and 🐙"))

        assertEquals(reports, OpenedReportsCodec.decode(OpenedReportsCodec.encode(reports)))
    }
}
