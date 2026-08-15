package com.dewijones92.totum.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a hand-sent report says it is about.
 *
 * Every hand-sent report used to read `Sent by hand from Settings`, which the `kind` field already
 * says. Dewi asked for a box to type into (2026-08-15), and the value is that a report is four
 * hundred events and the reader's problem is knowing which moment to look at — "warfronts video not
 * playing, skipping to another Rest Is Politics video" is what made 0.1.383 diagnosable, because
 * without it the trail reads as a video failing and being skipped, which is the app working.
 */
class DiagnosticsNoteTest {

    private val fallback = "Sent by hand from Settings"

    @Test
    fun `what was typed is what the report says`() {
        assertEquals(
            "warfronts video not playing",
            diagnosticsNote("warfronts video not playing", fallback),
        )
    }

    /** Sending must never be blocked on writing something, so an empty box still sends. */
    @Test
    fun `an empty box falls back rather than sending an empty note`() {
        assertEquals(fallback, diagnosticsNote("", fallback))
    }

    /** Whitespace is not a note — it would look like a description and say nothing. */
    @Test
    fun `whitespace alone is not a note`() {
        assertEquals(fallback, diagnosticsNote("   \n\t ", fallback))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("it skipped", diagnosticsNote("  it skipped\n", fallback))
    }

    /** A report is uploaded over whatever connection the phone has; the field is bounded. */
    @Test
    fun `a very long note is capped`() {
        val note = diagnosticsNote("x".repeat(NOTE_MAX_CHARS * 2), fallback)

        assertEquals(NOTE_MAX_CHARS, note.length)
    }

    /** Capping must not turn a long note into a blank one and lose it to the fallback. */
    @Test
    fun `a capped note is still the note, not the fallback`() {
        val note = diagnosticsNote("y".repeat(NOTE_MAX_CHARS * 2), fallback)

        assertEquals('y', note.first())
    }

    /** Newlines survive: a description of steps is the useful shape of note. */
    @Test
    fun `line breaks inside a note are kept`() {
        assertEquals("tapped it\nit skipped", diagnosticsNote("tapped it\nit skipped", fallback))
    }
}
