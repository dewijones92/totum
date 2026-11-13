package com.dewijones92.totum.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class GitHubReportTest {

    private val token = "d3v1ce-t0ken-9f8e7d6c"
    private val redactor = ReportRedactor(
        secrets = listOf(token, "prowl4rr-k3y-123456"),
        hosts = listOf("home.example.org", "totum.home.example.org"),
    )
    private val facts = listOf(
        GitHubReport.VERSION to "0.1.823 (build 923, commit abc1234)",
        "Device" to "Google Pixel 7, Android 15",
    )

    private fun decoded(url: String): String = URLDecoder.decode(url.replace("+", "%2B"), "UTF-8")

    @Test
    fun `no token, key, home server address or email leaves in the title, body or link`() {
        val draft = GitHubReport.draft(
            note = "It stalled. Signed in as person@mail.example.com via totum.home.example.org with $token",
            facts = facts + ("Server" to "https://totum.home.example.org/fetch?token=$token&key=prowl4rr-k3y-123456"),
            events = listOf(
                "21:03:48 [home] GET https://totum.home.example.org/files?id=1 header X-Totum-Token: $token",
                "21:03:49 [cast] TV at 192.168.0.44:8009 for person@mail.example.com",
            ),
            redactor = redactor,
        )

        listOf(draft.title, draft.body, draft.url, decoded(draft.url)).forEach { text ->
            assertFalse(text, token in text)
            assertFalse(text, "prowl4rr" in text)
            assertFalse(text, "example.org" in text)
            assertFalse(text, "example.com" in text)
            assertFalse(text, "192.168.0.44" in text)
        }
    }

    @Test
    fun `the link opens the new issue form with the title and body filled in`() {
        val draft = GitHubReport.draft("Audio cuts out\nafter the second item", facts, listOf("a line"), redactor)

        assertTrue(draft.url, draft.url.startsWith("${GitHubReport.NEW_ISSUE_URL}?title=Audio%20cuts%20out&body="))
        assertTrue(decoded(draft.url).contains("a line"))
        assertEquals("Audio cuts out", draft.title)
        assertTrue(draft.body.contains("- App version: 0.1.823"))
        assertTrue(draft.body.startsWith("Audio cuts out\nafter the second item"))
    }

    @Test
    fun `an empty note gets a title naming the version`() {
        val draft = GitHubReport.draft("   ", facts, emptyList(), redactor)

        assertEquals("Report from Totum 0.1.823 (build 923, commit abc1234)", draft.title)
    }

    @Test
    fun `a long first line is cut for the title`() {
        val draft = GitHubReport.draft("word ".repeat(40), facts, emptyList(), redactor)

        assertEquals(80, draft.title.length)
        assertTrue(draft.title.endsWith("…"))
    }

    @Test
    fun `too many events are dropped oldest first until the link fits`() {
        val events = (1..400).map { "line $it " + "word ".repeat(20) }

        val draft = GitHubReport.draft("note", facts, events, redactor)

        assertTrue(draft.url.length.toString(), draft.url.length <= GitHubReport.MAX_URL_CHARS)
        assertTrue(draft.eventsKept in 1 until events.size)
        assertEquals(events.size, draft.eventsOffered)
        assertTrue(draft.body.contains("line 400 "))
        assertFalse(draft.body.contains("line 1 "))
        assertFalse(draft.noteCut)
    }

    @Test
    fun `a note too long for any link is cut with a mark and says so`() {
        val draft = GitHubReport.draft("word ".repeat(3_000), facts, emptyList(), redactor)

        assertTrue(draft.url.length <= GitHubReport.MAX_URL_CHARS)
        assertTrue(draft.noteCut)
        assertEquals(0, draft.eventsKept)
        assertTrue(draft.body.contains("…"))
    }

    @Test
    fun `with no events there is no activity section`() {
        val draft = GitHubReport.draft("note", facts, emptyList(), redactor)

        assertFalse(draft.body.contains("Recent activity"))
        assertEquals(0, draft.eventsKept)
    }

    @Test
    fun `a long event line is cut`() {
        val draft = GitHubReport.draft("n", facts, listOf("word ".repeat(400)), redactor)

        assertTrue(draft.body.lines().none { it.length > GitHubReport.EVENT_LINE_CHARS + 20 })
    }

    private fun frames(count: Int): String =
        "java.lang.IllegalStateException: boom\n" +
            (1..count).joinToString("\n") { "\tat com.dewijones92.totum.Frame$it.run(Frame$it.kt:$it)" }

    @Test
    fun `a short stack trace is in the body and the link under its own heading`() {
        val draft = GitHubReport.draft("Totum crashed", facts, emptyList(), redactor, trace = frames(5))

        assertTrue(draft.body.contains("Stack trace"))
        assertTrue(draft.body.contains("Frame5.run(Frame5.kt:5)"))
        assertTrue(decoded(draft.url).contains("Frame5.run(Frame5.kt:5)"))
        assertFalse(draft.traceCut)
    }

    @Test
    fun `a stack trace too long for the link is kept whole in the body and cut from the link`() {
        val draft = GitHubReport.draft("Totum crashed", facts, emptyList(), redactor, trace = frames(400))

        assertTrue(draft.url.length.toString(), draft.url.length <= GitHubReport.MAX_URL_CHARS)
        assertTrue(draft.traceCut)
        assertFalse(draft.noteCut)
        assertTrue(draft.body.contains("Frame400.run(Frame400.kt:400)"))
        assertTrue(decoded(draft.url).contains("Frame1.run(Frame1.kt:1)"))
        assertFalse(decoded(draft.url).contains("Frame400.run(Frame400.kt:400)"))
    }

    @Test
    fun `activity lines go before any of the stack trace does`() {
        val events = (1..400).map { "line $it " + "word ".repeat(20) }

        val draft = GitHubReport.draft("Totum crashed", facts, events, redactor, trace = frames(60))

        assertTrue(draft.url.length <= GitHubReport.MAX_URL_CHARS)
        assertFalse(draft.traceCut)
        assertTrue(decoded(draft.url).contains("Frame60.run(Frame60.kt:60)"))
        assertTrue(draft.eventsKept < events.size)
    }

    @Test
    fun `the stack trace is redacted like everything else`() {
        val trace = "java.io.IOException: refused for $token at totum.home.example.org\n\tat a.B.c(B.kt:1)"

        val draft = GitHubReport.draft("Totum crashed", facts, emptyList(), redactor, trace = trace)

        listOf(draft.body, draft.url, decoded(draft.url)).forEach { text ->
            assertFalse(text, token in text)
            assertFalse(text, "example.org" in text)
        }
    }

    @Test
    fun `with no stack trace there is no trace section`() {
        val draft = GitHubReport.draft("note", facts, emptyList(), redactor)

        assertFalse(draft.body.contains("Stack trace"))
        assertFalse(draft.traceCut)
    }
}
