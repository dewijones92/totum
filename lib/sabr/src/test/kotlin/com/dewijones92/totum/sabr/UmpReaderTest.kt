package com.dewijones92.totum.sabr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UMP framing, the envelope every SABR response arrives in.
 *
 * The widths come from the format's own worked examples, and the part sequences from a live
 * response captured 2026-07-31 — a POST to `serverAbrStreamingUrl` carrying only
 * `videoPlaybackUstreamerConfig`, which returned 212KB of WebM and fMP4 segments.
 */
class UmpReaderTest {

    private fun varint(vararg bytes: Int) = bytes.map { it.toByte() }.toByteArray()

    @Test
    fun `a one-byte varint is the byte itself`() {
        // The spec's own example: 100 encodes as 0x64, top bit unset.
        assertEquals(100L, UmpVarint.read(varint(0x64), 0)?.value)
        assertEquals(1, UmpVarint.read(varint(0x64), 0)?.next)
    }

    @Test
    fun `wider varints take their low bits from the first byte and the rest little-endian`() {
        // 2 bytes: 10xxxxxx -> low 6 bits, then one byte shifted 6.
        assertEquals((0x01L) or (0x02L shl 6), UmpVarint.read(varint(0x81, 0x02), 0)?.value)
        // 3 bytes: 110xxxxx -> low 5 bits, then two bytes shifted 5 and 13.
        assertEquals(
            (0x01L) or (0x02L shl 5) or (0x03L shl 13),
            UmpVarint.read(varint(0xC1, 0x02, 0x03), 0)?.value,
        )
        // 4 bytes: 1110xxxx -> low 4 bits, then three shifted 4, 12, 20.
        assertEquals(
            (0x01L) or (0x02L shl 4) or (0x03L shl 12) or (0x04L shl 20),
            UmpVarint.read(varint(0xE1, 0x02, 0x03, 0x04), 0)?.value,
        )
    }

    /**
     * The trap: at five bytes the first byte contributes NOTHING, unlike every other width.
     * Treating it like the others would give a value wrong by whatever its low bits held.
     */
    @Test
    fun `a five-byte varint ignores the first byte entirely`() {
        val withBitsSet = UmpVarint.read(varint(0xF7, 0x01, 0x02, 0x03, 0x04), 0)
        val withBitsClear = UmpVarint.read(varint(0xF0, 0x01, 0x02, 0x03, 0x04), 0)

        assertEquals(withBitsClear?.value, withBitsSet?.value)
        assertEquals(0x04030201L, withBitsSet?.value)
        assertEquals(5, withBitsSet?.next)
    }

    @Test
    fun `an invalid width and a truncated varint are nulls, not zeroes`() {
        assertNull("11111xxx is invalid", UmpVarint.read(varint(0xFF, 0, 0, 0, 0), 0))
        assertNull("declares 5 bytes, has 2", UmpVarint.read(varint(0xF0, 0x01), 0))
        assertNull(UmpVarint.read(varint(0x64), 5))
    }

    @Test
    fun `reads a sequence of parts, naming what they are`() {
        val body = varint(
            20, 3, 0xAA, 0xBB, 0xCC, // MEDIA_HEADER, 3 bytes
            21, 2, 0x00, 0x11, // MEDIA, 2 bytes (null-prefixed, as YouTube sends it)
            22, 1, 0x00, // MEDIA_END
        )

        val result = UmpReader.read(body)

        assertEquals(listOf("MEDIA_HEADER", "MEDIA", "MEDIA_END"), result.parts.map { it.name })
        assertEquals(3, result.parts[0].payload.size)
        assertEquals(body.size, result.consumed)
    }

    /**
     * A part can span HTTP responses, so a trailing partial one must be REPORTED rather than
     * dropped or guessed at. Dropping it corrupts exactly the boundary hardest to notice.
     */
    @Test
    fun `a part split across responses is left unconsumed`() {
        // MEDIA_HEADER complete, then a MEDIA part declaring 8 bytes with only 3 present.
        val body = varint(20, 1, 0xAA, 21, 8, 0x00, 0x11, 0x22)

        val result = UmpReader.read(body)

        assertEquals(listOf("MEDIA_HEADER"), result.parts.map { it.name })
        assertEquals("the incomplete MEDIA must stay for the next read", 3, result.consumed)
        assertTrue(result.consumed < body.size)
    }

    @Test
    fun `an unknown part type still reads, so one new part cannot break the stream`() {
        val result = UmpReader.read(varint(99, 1, 0x07, 22, 1, 0x00))

        assertEquals(listOf("UNKNOWN_99", "MEDIA_END"), result.parts.map { it.name })
    }

    @Test
    fun `an empty body is no parts rather than a failure`() {
        assertEquals(emptyList<UmpReader.Part>(), UmpReader.read(ByteArray(0)).parts)
    }

    /**
     * A body that is not UMP at all must read as nothing — never throw.
     *
     * `readPart` narrowed the declared length to an Int BEFORE bounding it, and a five-byte UMP varint
     * holds far more than an Int: `0xF0 FF FF FF FF` decodes to 4294967295, `toInt()` wrapped it to -1,
     * the guard passed because the Long was positive and the wrapped end was small, and `copyOfRange`
     * threw `IllegalArgumentException`.
     *
     * That is not a hypothetical. Every caller here catches `IOException` and nothing else — the whole
     * point of [SabrTransport] is that a failure arrives as one — so this escaped the transport's catch,
     * escaped `SabrStream.fetch`'s catch, reached ExoPlayer as an unexpected runtime exception rather
     * than the retryable IO failure the recovery ladder handles, and on the download path escaped the
     * flow entirely and left the row `Downloading` for ever. The way in is an HTTP error body that is
     * gzip or protobuf rather than text, which is the likeliest thing to arrive from the attestation
     * wall: [ResponseSummary.of] is called on exactly that, inside the try.
     */
    @Test
    fun `a length that overflows an Int is corrupt, not a crash`() {
        val fourGigabytesDeclared = varint(0x01, 0xF0, 0xFF, 0xFF, 0xFF, 0xFF)

        val read = runCatching { UmpReader.read(fourGigabytesDeclared) }

        assertEquals(
            "a hostile length must read as an unfinished part, not throw ${read.exceptionOrNull()}",
            emptyList<UmpReader.Part>(),
            read.getOrNull()?.parts,
        )
        assertEquals("and nothing may be reported as consumed", 0, read.getOrNull()?.consumed)
    }

    /** And the summariser above it must survive the same body, because that is where it is met. */
    @Test
    fun `summarising a hostile error body says something rather than throwing`() {
        val summary = runCatching { ResponseSummary.of(varint(0x01, 0xF0, 0xFF, 0xFF, 0xFF, 0xFF)) }

        assertTrue("it threw ${summary.exceptionOrNull()}", summary.isSuccess)
    }

    /**
     * The real first probe's answer, which is how the missing config field was found.
     *
     * Part **44** — and this test used to assert that id was `RELOAD_PLAYER_RESPONSE`, because the
     * hand-written part table said so. Checked against `UMPPartId` in `LuanRT/googlevideo`, 44 is
     * `SABR_ERROR` and `RELOAD_PLAYER_RESPONSE` is 46. The wire byte is unchanged; only the name was
     * wrong, and this test was pinning the wrong name. Both are refusals, so nothing about the original
     * diagnosis changes — see [UmpPart].
     */
    @Test
    fun `a malformed-config refusal reads as a SABR_ERROR`() {
        val reason = "sabr.malformed_config".toByteArray()
        val body = varint(44, reason.size + 2, 0x0A, reason.size) + reason

        val part = UmpReader.read(body).parts.single()

        assertEquals(UmpPart.SABR_ERROR, part.type)
        assertTrue("sabr.malformed_config" in String(part.payload))
    }
}
