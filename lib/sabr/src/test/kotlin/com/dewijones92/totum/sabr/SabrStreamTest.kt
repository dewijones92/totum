package com.dewijones92.totum.sabr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a SABR conversation into bytes in order.
 *
 * The shapes here are the real ones: a response interleaves formats (no track bitfield returns
 * video without audio), `MEDIA` payloads carry a one-byte prefix that is not media, and progress
 * comes from `player_time_ms` rather than from anything we tell it about bytes.
 */
class SabrStreamTest {

    private val audio = SabrFormat(itag = 251, lastModified = 1L, xtags = "orig")
    private val video = SabrFormat(itag = 137, lastModified = 2L)

    /** Builds a UMP response the way YouTube frames one, ids and all. */
    private fun response(vararg runs: Triple<SabrFormat, Long, ByteArray>): ByteArray {
        var out = ByteArray(0)
        runs.forEachIndexed { id, (format, offset, payload) ->
            out += header(id, format, offset, payload.size)
            out += media(id, payload)
        }
        return out
    }

    private fun header(id: Int, format: SabrFormat, offset: Long, length: Int) =
        UmpFraming.mediaHeader(id, format, offset, length)

    private fun media(id: Int, payload: ByteArray) = UmpFraming.media(id, payload)

    private fun stream(
        transport: SabrTransport,
        format: SabrFormat = audio,
        totalBytes: Long? = null,
    ) = SabrStream(
        url = "https://example.test/videoplayback",
        ustreamerConfig = byteArrayOf(1, 2, 3),
        format = format,
        kind = if (format == audio) SabrTrackKind.AUDIO else SabrTrackKind.VIDEO,
        totalBytes = totalBytes,
        transport = transport,
    )

    @Test
    fun `serves the requested format's bytes from the start`() = runTest {
        val fake = FakeSabrServer(listOf(response(Triple(audio, 0L, byteArrayOf(10, 11, 12)))))

        val bytes = stream(fake).read(from = 0)

        assertArrayEquals(byteArrayOf(10, 11, 12), bytes)
    }

    /**
     * The interleaving that makes this necessary: a video request returns audio too, and the
     * wrong bytes spliced into a track decode as corruption rather than failing.
     */
    @Test
    fun `bytes belonging to another format are dropped`() = runTest {
        val fake = FakeSabrServer(
            listOf(
                response(
                    Triple(audio, 0L, byteArrayOf(1, 2)),
                    Triple(video, 0L, byteArrayOf(9, 9, 9, 9)),
                ),
            ),
        )

        assertArrayEquals(byteArrayOf(9, 9, 9, 9), stream(fake, video).read(from = 0))
    }

    /**
     * The total comes from the PLAYER RESPONSE, never from a `MEDIA_HEADER`.
     *
     * A header's `contentLength` is one RUN's length. Reading it as the total reported
     * "432274B of 807B" on a real video — 807 being the init segment — and would have let the
     * stream call itself complete on its first run, ending a video seconds in.
     */
    @Test
    fun `the content length is the format's total, not one run's`() = runTest {
        // The response declares a 5-byte run; the format is really 5000 bytes.
        val fake = FakeSabrServer(listOf(response(Triple(audio, 0L, ByteArray(5)))))
        val stream = stream(fake, totalBytes = 5_000)

        stream.read(from = 0)

        assertEquals(5_000L, stream.contentLength)
    }

    /**
     * Progress is driven by `player_time_ms` because that is what the server responds to —
     * `buffered_ranges` alone advanced twice and then stalled. Each fetch must therefore ask
     * from further on than the last, or the same bytes come back forever.
     */
    @Test
    fun `each fetch asks from a later player time`() = runTest {
        val fake = FakeSabrServer(
            listOf(
                response(Triple(audio, 0L, byteArrayOf(1))),
                response(Triple(audio, 1L, byteArrayOf(2))),
            ),
        )
        val stream = stream(fake)

        stream.read(from = 0)
        stream.read(from = 1)

        assertEquals(listOf(0L, 10_000L), fake.timesAsked)
    }

    @Test
    fun `an audio stream asks for audio alone, which is a tenth of the bytes`() = runTest {
        val fake = FakeSabrServer(listOf(response(Triple(audio, 0L, byteArrayOf(1)))))

        stream(fake).read(from = 0)

        val state = Protobuf.read(fake.requests.single())[1]?.first() as Protobuf.Value.Bytes
        assertEquals(1L, (Protobuf.read(state.value)[40]!!.first() as Protobuf.Value.Number).value)
        assertTrue("audio goes in field 16", Protobuf.read(fake.requests.single())[16] != null)
    }

    @Test
    fun `a video stream asks in field 17 and accepts audio alongside`() = runTest {
        val fake = FakeSabrServer(listOf(response(Triple(video, 0L, byteArrayOf(1)))))

        stream(fake, video).read(from = 0)

        val body = fake.requests.single()
        assertTrue("video goes in field 17", Protobuf.read(body)[17] != null)
        val state = Protobuf.read(body)[1]?.first() as Protobuf.Value.Bytes
        assertEquals(
            "0 means both tracks",
            0L,
            (Protobuf.read(state.value)[40]!!.first() as Protobuf.Value.Number).value
        )
    }

    /**
     * The bug that made video decode to corruption, reproduced.
     *
     * Runs INTERLEAVE. Measured on itag 134 with audio alongside it, one real response went
     * `MEDIA_HEADER(3), MEDIA(3), MEDIA(1), MEDIA(1), MEDIA_END(1), MEDIA_HEADER(4), MEDIA(4),
     * MEDIA(3)` — header 1's run resuming three parts after header 3 was declared. Binding a
     * MEDIA part to the most recent header therefore splices one format's bytes into another's
     * at the wrong offset, and ExoPlayer reports `Invalid NAL length` rather than failing
     * cleanly. Audio-only hid it, because one format's runs arrive in order.
     */
    @Test
    fun `interleaved runs go to the format that owns them, not the last header seen`() = runTest {
        // header 0 = our video at 0; header 1 = audio at 0; then MORE of header 0, out of order.
        val body = header(0, video, 0, 4) + media(0, byteArrayOf(1, 2)) +
            header(1, audio, 0, 2) + media(1, byteArrayOf(9, 9)) +
            media(0, byteArrayOf(3, 4))
        val fake = FakeSabrServer(listOf(body))

        val first = stream(fake, video).read(from = 0)

        // Both of header 0's runs, contiguous, with the audio bytes nowhere among them.
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), first)
    }

    /** A MEDIA part naming a run we never saw declared must be dropped, not guessed at. */
    @Test
    fun `media for an unknown header id is ignored`() = runTest {
        val body = header(0, video, 0, 2) + media(0, byteArrayOf(7, 7)) + media(42, byteArrayOf(1, 1))
        val fake = FakeSabrServer(listOf(body))

        assertArrayEquals(byteArrayOf(7, 7), stream(fake, video).read(from = 0))
    }

    /** A server with nothing left to send must end the stream, not spin forever. */
    @Test
    fun `an empty response ends the stream rather than looping`() = runTest {
        val fake = FakeSabrServer(emptyList())

        assertEquals(0, stream(fake).read(from = 0).size)
        assertTrue("must give up, not retry indefinitely", fake.requests.size <= 6)
    }

    /**
     * A video must not FINISH EARLY. One empty answer used to end the stream, and since the
     * declared length says how long the format really is, stopping short of it is a stall — not
     * an end. Ending it there makes the player believe the video is over and the queue advance,
     * which is indistinguishable from the video simply being short.
     */
    @Test
    fun `an empty response part-way through does not end the stream`() = runTest {
        val fake = FakeSabrServer(
            listOf(
                // Declares 10 bytes but sends 4, then nothing, then the remaining 6.
                header(0, audio, 0, 10) + media(0, byteArrayOf(1, 2, 3, 4)),
                ByteArray(0),
                header(1, audio, 4, 6) + media(1, byteArrayOf(5, 6, 7, 8, 9, 10)),
            ),
        )
        val stream = stream(fake, totalBytes = 10)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), stream.read(from = 0))
        // The empty answer in between must not have ended it: the rest still arrives.
        assertArrayEquals(byteArrayOf(5, 6, 7, 8, 9, 10), stream.read(from = 4))
    }

    /** Once every declared byte is served, an empty answer IS the end and must be taken as one. */
    @Test
    fun `a complete stream ends without complaint`() = runTest {
        val fake = FakeSabrServer(listOf(header(0, audio, 0, 2) + media(0, byteArrayOf(1, 2))))
        val stream = stream(fake, totalBytes = 2)

        assertArrayEquals(byteArrayOf(1, 2), stream.read(from = 0))
        assertEquals("nothing left, and that is correct", 0, stream.read(from = 2).size)
    }
}
