package com.dewijones92.totum.sabr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A SABR stream opened part-way through must ask the server for THAT point, not for the beginning.
 *
 * This is the whole reason SABR is confined to the first ten seconds of an item, and it was measured
 * rather than assumed (2026-07-31): a video resumed at 367799ms opened its video track about **41MB in**,
 * SABR answered with bytes from the start of the file, every one of them was discarded as already-passed,
 * and the video track died at 16% while the audio carried on — a video playing with no picture.
 *
 * The cause is a units mismatch, not a missing protocol feature. ExoPlayer opens a track at a **byte
 * offset**; a SABR request asks for a **media time**. Nothing translated between the two, so a seek to
 * 41MB still asked for `player_time_ms = 0` and got the opening of the file, forever.
 *
 * The translation already existed and was simply not wired to this: `HeldSegments.timeOfByte` scales an
 * offset by the format's own length and duration, and `advanceClaimedTime` had been using it since the
 * morning to keep the claimed position honest. So this is a wiring fix.
 *
 * The estimate is a ratio, so it is only as good as a constant bitrate — fine for audio, approximate for
 * video. That is acceptable because the server answers with `MEDIA_HEADER`s naming the segments it
 * actually sent, and the stream then holds real ranges rather than the estimate. **Landing near the
 * target and correcting beats landing at zero and never arriving.**
 */
class OpeningAtAnOffsetAsksForThatTimeTest {

    private val video = SabrFormat(itag = 136, lastModified = 7L, xtags = null)

    private fun stream(transport: SabrTransport) = SabrStream(
        url = "https://example.test/videoplayback",
        ustreamerConfig = byteArrayOf(9),
        format = video,
        kind = SabrTrackKind.VIDEO,
        transport = transport,
        totalBytes = TOTAL_BYTES,
        durationMs = DURATION_MS,
    )

    /** One run of real media, so a read can actually hand bytes out. */
    private fun segment(index: Int): ByteArray {
        val header = UmpFraming.part(
            UmpPart.MEDIA_HEADER,
            Protobuf.number(1, 0L) +
                Protobuf.number(3, video.itag.toLong()) +
                Protobuf.number(6, index.toLong() * CHUNK) +
                Protobuf.number(9, index.toLong() + 1),
        )
        return header + UmpFraming.media(0, ByteArray(CHUNK.toInt()) { 7 })
    }

    /** THE case: a mid-file open must not ask for the beginning. */
    @Test
    fun `opening at an offset asks for the matching media time`() = runTest {
        val asked = AskedTimes()

        stream(asked).read(from = HALFWAY_BYTES)

        assertTrue("no request was made at all", asked.times.isNotEmpty())
        assertEquals(
            "opening ${HALFWAY_BYTES}B into a ${TOTAL_BYTES}B / ${DURATION_MS}ms stream should ask for " +
                "roughly the halfway mark, not the start",
            HALFWAY_MS,
            asked.times.first(),
        )
    }

    /** And opening at the start still asks for the start — the ordinary case must not move. */
    @Test
    fun `opening at zero still asks for the beginning`() = runTest {
        val asked = AskedTimes()

        stream(asked).read(from = 0)

        assertEquals(0L, asked.times.first())
    }

    /**
     * Sequential reading must NOT be re-estimated per read.
     *
     * The claimed time is advanced from the bytes actually served (`advanceClaimedTime`), which is more
     * truthful than a ratio. Recomputing an estimate on every read would throw that away and could move
     * the claim BACKWARDS mid-stream, which reads to the server as a seek and re-sends everything —
     * exactly the 52%-wasted-bytes problem that sending buffered ranges was introduced to fix.
     *
     * So it needs a stream that actually hands bytes out: the distinction is between a read that
     * continues from the last one and a read that jumps, and with no data every read looks like a jump.
     */
    @Test
    fun `reading on from what it holds does not re-estimate`() = runTest {
        val asked = AskedTimes(listOf(segment(0), segment(1)))
        val stream = stream(asked)

        val first = stream.read(from = 0)
        assertEquals("the fixture must hand out the first run", CHUNK.toInt(), first.size)
        stream.read(from = first.size.toLong())

        assertTrue(
            "a read continuing from ${first.size} should follow the bytes served, not re-estimate: " +
                "asked ${asked.times}",
            asked.times.drop(1).all { it < HALFWAY_MS },
        )
    }

    /**
     * A JUMP after sequential reading must be aimed — the case a cold-start-only guard silently skipped.
     *
     * Found by a probe that meant to test whether YouTube allows a seek inside an established
     * conversation and instead asked for the position sequential reading had already reached (130005ms
     * when it meant 407499ms). It would have recorded "session continuity is not the missing piece" as a
     * finding, from an instrument that never performed the seek.
     */
    @Test
    fun `a jump after sequential reading is aimed at the new position`() = runTest {
        // Every response carries media: a response WITHOUT any marks the stream exhausted, and an
        // exhausted stream returns from `read` without fetching at all — so the jump would make no
        // request and the test would pass or fail for the wrong reason.
        val asked = AskedTimes(listOf(segment(0), segment(1), segment(2)))
        val stream = stream(asked)

        val first = stream.read(from = 0)
        stream.read(from = first.size.toLong())
        val beforeJump = asked.times.size
        stream.read(from = HALFWAY_BYTES)

        assertEquals(
            "the jump should ask for the halfway mark, not carry on from where reading had reached",
            HALFWAY_MS,
            asked.times.drop(beforeJump).firstOrNull(),
        )
    }

    private companion object {
        /** A 97-minute 1080p video, roughly: the case that actually failed. */
        const val TOTAL_BYTES = 320_000_000L
        const val DURATION_MS = 5_820_000L
        const val HALFWAY_BYTES = TOTAL_BYTES / 2
        const val HALFWAY_MS = DURATION_MS / 2
        const val CHUNK = 64L * 1024
    }
}

/** Records the `player_time_ms` of every request, which is the field under test. */
private class AskedTimes(private val responses: List<ByteArray> = emptyList()) : SabrTransport {
    val times: MutableList<Long> = mutableListOf()
    private var index = 0

    override suspend fun post(url: String, body: ByteArray): ByteArray {
        times += playerTimeIn(body)
        return responses.getOrElse(index++) { ByteArray(0) }
    }
}

private fun playerTimeIn(body: ByteArray): Long {
    val request = Protobuf.read(body)
    val state = request[CLIENT_ABR_STATE]?.filterIsInstance<Protobuf.Value.Bytes>()?.firstOrNull()
        ?: return -1
    val fields = Protobuf.read(state.value)
    return (fields[PLAYER_TIME_MS]?.firstOrNull() as? Protobuf.Value.Number)?.value ?: -1
}

private const val CLIENT_ABR_STATE = 1
private const val PLAYER_TIME_MS = 28
