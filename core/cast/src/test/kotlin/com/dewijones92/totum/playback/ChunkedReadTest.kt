package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic and the stopping rule behind every stream this app plays.
 *
 * Both were wrong and neither was tested. `clen` — the length of the WHOLE resource — was read
 * straight into "bytes remaining" while the caller was starting partway through it, so a stream
 * resumed at byte P over-declared itself by exactly P; and having reached the true end, the reader
 * still believed bytes were owed and asked for a range past it, forever, inside one `read()`.
 *
 * What that looked like in the wild (report 0.1.359, 2026-08-06, a Pixel 7): four consecutive
 * videos each hard-stalled inside their last 45 seconds — 11.5s, 35.8s, 42.8s and 0.1s of content
 * left — and never recovered, 208 of 244 seconds of buffering abandoned, loads outstanding climbing
 * 35 → 37 with the oldest frozen and no load errors at all, and the heap walking from 102MB to
 * 255MB of 256MB as every stuck loader was retained. Dewi: *"buffers towards the end of the
 * video??????"*.
 *
 * ExoPlayer restarts its loader at a non-zero byte offset on every seek AND every time the load
 * control pauses loading, so this was not a resumed-playback edge case: it was every stream.
 */
class ChunkedReadTest {

    // ---- remainingFrom: which quantity is which -------------------------------------------------

    @Test
    fun `a length the caller stated is already relative to where they started`() {
        assertEquals(500, remainingFrom(position = 1_000, requestedLength = 500, resourceLength = 9_999))
    }

    /** THE BUG. `clen` describes the whole resource, so the position has to come off it. */
    @Test
    fun `a resource length has the starting position taken off it`() {
        assertEquals(
            "a stream resumed 200 bytes into a 1000-byte resource has 800 left, not 1000",
            800,
            remainingFrom(position = 200, requestedLength = UNKNOWN_LENGTH, resourceLength = 1_000),
        )
    }

    /** The real numbers from the report: a 62-minute AV1 upload resumed at 57 minutes. */
    @Test
    fun `the video from the report declares what is actually left of it`() {
        val clen = 278_941_757L
        val resumedAt = 256_872_000L
        assertEquals(clen - resumedAt, remainingFrom(resumedAt, UNKNOWN_LENGTH, clen))
    }

    @Test
    fun `starting at zero is the case that always worked`() {
        assertEquals(1_000, remainingFrom(position = 0, requestedLength = UNKNOWN_LENGTH, resourceLength = 1_000))
    }

    @Test
    fun `nothing to go on stays unknown rather than guessing`() {
        assertEquals(
            UNKNOWN_LENGTH,
            remainingFrom(position = 10, requestedLength = UNKNOWN_LENGTH, resourceLength = null),
        )
        assertEquals(
            UNKNOWN_LENGTH,
            remainingFrom(position = 10, requestedLength = UNKNOWN_LENGTH, resourceLength = UNKNOWN_LENGTH),
        )
    }

    /**
     * Clamped rather than negative, because a negative length reads as UNKNOWN and a reader that
     * thinks the length is unknown streams on forever — the exact failure this file exists for.
     */
    @Test
    fun `a position past the end of the resource owes nothing`() {
        assertEquals(0, remainingFrom(position = 1_500, requestedLength = UNKNOWN_LENGTH, resourceLength = 1_000))
    }

    // ---- the cursor: which range next ----------------------------------------------------------

    @Test
    fun `the first range starts where the caller did and is one chunk long`() {
        val read = ChunkedRead(remaining = 10_000, chunkBytes = 2_000)
        assertEquals(ChunkedRead.Range(offset = 0, bytes = 2_000), read.nextRange())
    }

    @Test
    fun `each range picks up exactly where the last one stopped`() {
        val read = ChunkedRead(remaining = 10_000, chunkBytes = 2_000)
        read.consumeWholeRange(2_000)
        assertEquals(ChunkedRead.Range(offset = 2_000, bytes = 2_000), read.nextRange())
    }

    @Test
    fun `the last range asks only for what is left, never past the end`() {
        val read = ChunkedRead(remaining = 5_000, chunkBytes = 2_000)
        read.consumeWholeRange(2_000)
        read.consumeWholeRange(2_000)
        assertEquals(
            "asking for a full chunk here would request 1000 bytes beyond the resource",
            ChunkedRead.Range(offset = 4_000, bytes = 1_000),
            read.nextRange(),
        )
    }

    @Test
    fun `an unknown length asks for a full chunk every time`() {
        val read = ChunkedRead(remaining = UNKNOWN_LENGTH, chunkBytes = 2_000)
        assertEquals(ChunkedRead.Range(offset = 0, bytes = 2_000), read.nextRange())
        read.consumeWholeRange(2_000)
        assertEquals(ChunkedRead.Range(offset = 2_000, bytes = 2_000), read.nextRange())
    }

    @Test
    fun `a read is capped to the open range so the next one starts in the right place`() {
        val read = ChunkedRead(remaining = 10_000, chunkBytes = 2_000)
        read.nextRange()
        read.opened(2_000)
        read.served(1_900)
        assertEquals("only 100 bytes of this range are left", 100, read.cap(8_192))
    }

    @Test
    fun `serving everything owed finishes the read`() {
        val read = ChunkedRead(remaining = 1_000, chunkBytes = 2_000)
        assertFalse(read.finished)
        read.consumeWholeRange(1_000)
        assertTrue(read.finished)
        assertEquals(0, read.remaining)
    }

    @Test
    fun `the declared length is what open reports and does not move as bytes are served`() {
        val read = ChunkedRead(remaining = 4_000, chunkBytes = 2_000)
        read.consumeWholeRange(2_000)
        assertEquals(4_000, read.declaredLength)
        assertEquals(2_000, read.remaining)
    }

    // ---- the stopping rule ---------------------------------------------------------------------

    /**
     * The failure, at the level it happened. A range that produces nothing must end the read: asking
     * again asks for exactly the same bytes and gets exactly the same nothing.
     */
    @Test
    fun `a range that produces nothing ends the read instead of being asked for again`() {
        val read = ChunkedRead(remaining = 10_000, chunkBytes = 2_000)
        read.nextRange()
        read.opened(0)

        assertEquals(ChunkedRead.RangeEnd.EndedEarly, read.endOfRange())
        assertTrue("anything else here is an unbounded loop", read.finished)
    }

    @Test
    fun `a range that stops short of what it promised ends the read`() {
        val read = ChunkedRead(remaining = 10_000, chunkBytes = 2_000)
        read.nextRange()
        read.opened(2_000)
        read.served(500)

        assertEquals(ChunkedRead.RangeEnd.EndedEarly, read.endOfRange())
        assertTrue(read.finished)
    }

    @Test
    fun `a range that delivered all it promised carries on to the next one`() {
        val read = ChunkedRead(remaining = UNKNOWN_LENGTH, chunkBytes = 2_000)
        read.nextRange()
        read.opened(UNKNOWN_LENGTH)
        read.served(2_000)

        assertEquals(ChunkedRead.RangeEnd.Continue, read.endOfRange())
        assertFalse(read.finished)
    }

    @Test
    fun `the end of a known-length resource is an ordinary end, not an anomaly`() {
        val read = ChunkedRead(remaining = 2_000, chunkBytes = 2_000)
        read.nextRange()
        read.opened(UNKNOWN_LENGTH)
        read.served(2_000)

        assertEquals(
            "everything owed arrived, so this must not be reported as a stream ending early",
            ChunkedRead.RangeEnd.Ended,
            read.endOfRange(),
        )
    }

    /**
     * The whole of a resumed stream, chunk by chunk, ending exactly once.
     *
     * The property that matters and that nothing checked: a reader handed "bytes remaining from
     * here" serves precisely that many and then stops. It used to serve them and keep asking.
     */
    @Test
    fun `a resumed stream serves exactly what is left of it and then stops`() {
        val resourceLength = 1_000_000L
        val resumedAt = 750_000L
        val read = ChunkedRead(
            remaining = remainingFrom(resumedAt, UNKNOWN_LENGTH, resourceLength),
            chunkBytes = 64_000,
        )

        var served = 0L
        var ranges = 0
        while (!read.finished) {
            val range = read.nextRange()
            ranges++
            assertTrue(
                "range $ranges asks for bytes past the end of the resource: " +
                    "${resumedAt + range.offset}..${resumedAt + range.offset + range.bytes}",
                resumedAt + range.offset + range.bytes <= resourceLength,
            )
            read.opened(range.bytes)
            read.served(range.bytes.toInt())
            served += range.bytes
            assertTrue("this should have finished long ago", ranges < 100)
        }

        assertEquals(resourceLength - resumedAt, served)
    }

    /** Advances the cursor through one whole range, as a caller reading it to the end would. */
    private fun ChunkedRead.consumeWholeRange(bytes: Int) {
        nextRange()
        opened(bytes.toLong())
        served(bytes)
    }
}
