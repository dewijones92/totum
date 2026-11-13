package com.dewijones92.totum.common

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagTest {

    private val written = mutableListOf<Triple<Diag.Level, String, String>>()

    @Before
    fun setUp() {
        Breadcrumbs.clear()
        Diag.sink = Diag.Sink { level, tag, message, _ -> written += Triple(level, tag, message) }
    }

    @After
    fun tearDown() {
        Breadcrumbs.clear()
        Diag.sink = Diag.Sink { _, _, _, _ -> }
    }

    @Test
    fun `logging records a breadcrumb and prints it`() {
        Diag.log("queue", "size=3")

        assertEquals(listOf("queue" to "size=3"), Breadcrumbs.snapshot().map { it.tag to it.message })
        assertEquals(Triple(Diag.Level.INFO, "queue", "size=3"), written.single())
    }

    @Test
    fun `warn includes the throwable in the remembered text`() {
        Diag.warn("download", "failed", IllegalStateException("no space"))

        val remembered = Breadcrumbs.snapshot().single().message
        assertTrue(remembered, remembered.contains("failed"))
        assertTrue(remembered, remembered.contains("IllegalStateException"))
        assertTrue(remembered, remembered.contains("no space"))
        assertEquals(Diag.Level.WARN, written.single().first)
    }

    @Test
    fun `warn without a throwable keeps the message clean`() {
        Diag.warn("download", "failed: 404")

        assertEquals("failed: 404", Breadcrumbs.snapshot().single().message)
    }

    /** The whole point of the buffer: a long session must not grow without limit. */
    @Test
    fun `the trail stays bounded however long the session runs`() {
        repeat(6_000) { Diag.log("tick", "event $it") }

        val snapshot = Breadcrumbs.snapshot()
        assertEquals(5_000, snapshot.size)
        assertEquals("event 1000", snapshot.first().message)
        assertEquals("event 5999", snapshot.last().message)
    }

    @Test
    fun `snapshot is oldest first so a report reads as a story`() {
        Diag.log("a", "first")
        Diag.log("b", "second")

        assertEquals(listOf("first", "second"), Breadcrumbs.snapshot().map { it.message })
    }

    @Test
    fun `entries are stamped with a readable wall-clock time`() {
        Diag.log("a", "x")

        val formatted = Breadcrumbs.formatTime(Breadcrumbs.snapshot().single().atEpochMs)
        assertTrue(formatted, formatted.matches(Regex("""\d{2}:\d{2}:\d{2}\.\d{3}""")))
    }

    /** A module with no sink installed (unit tests, pure-JVM callers) must still record. */
    @Test
    fun `breadcrumbs are recorded even with the silent default sink`() {
        Diag.sink = Diag.Sink { _, _, _, _ -> }

        Diag.log("engine", "extracting")

        assertEquals("extracting", Breadcrumbs.snapshot().single().message)
    }

    /**
     * The floor holds regardless of age: a quiet session that has been open for hours
     * must still arrive with context, not an empty trail.
     */
    @Test
    fun `entries under the count floor are kept however old`() {
        repeat(400) { Breadcrumbs.record("old", "line $it") }

        assertEquals(400, Breadcrumbs.snapshot().size)
    }

    /**
     * Beyond the floor, age decides — a chatty minute of ticks must not evict the thing
     * that actually broke twenty minutes ago.
     */
    @Test
    fun `beyond the floor, recent entries survive and stale ones do not`() {
        repeat(600) { Breadcrumbs.record("chatty", "line $it") }
        val trail = Breadcrumbs.snapshot()

        assertEquals("nothing recent should have been dropped", 600, trail.size)
        assertEquals("line 0", trail.first().message)
        assertEquals("line 599", trail.last().message)
    }

    @Test
    fun `events restored from before a restart are marked and come first, once`() {
        Diag.log("queue", "now")

        Breadcrumbs.restore(
            listOf(
                Breadcrumbs.Entry(1_000, "queue", "older"),
                Breadcrumbs.Entry(2_000, "home", "newer"),
                Breadcrumbs.Entry(1_500, "queue", "${Breadcrumbs.BEFORE_RESTART}already marked"),
            ),
        )

        assertEquals(
            listOf(
                "${Breadcrumbs.BEFORE_RESTART}older",
                "${Breadcrumbs.BEFORE_RESTART}already marked",
                "${Breadcrumbs.BEFORE_RESTART}newer",
                "now",
            ),
            Breadcrumbs.snapshot().map { it.message },
        )
    }
}
