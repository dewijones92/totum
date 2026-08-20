package com.dewijones92.totum.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dewijones92.totum.sabr.SabrFormat
import com.dewijones92.totum.sabr.SabrStream
import com.dewijones92.totum.sabr.SabrTrackKind
import com.dewijones92.totum.sabr.SabrTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * A SABR stream that stops short must FAIL, not report the end of the video.
 *
 * From Dewi's Pixel, build 0.1.435 / commit 3a31b58, note *"rachael seeking doesn't work???? when using
 * sabr??"*. The trail:
 *
 * ```
 * PREMATURE END: itag 251 served 920030B of 53458433B (1%) after 4 empty responses
 *                — the player will treat this as the end of the video
 * ...
 * seek 978ms -> 1991370ms      loaded to: track--1=3664121ms
 * ```
 *
 * `read` returned `RESULT_END_OF_INPUT` for any empty read, so ExoPlayer believed a 61-minute video had
 * finished after 1%, marked the whole duration loaded, and every seek afterwards "succeeded" instantly
 * into a stream that was not there. Nothing failed, so the recovery ladder never ran and never fell back
 * to ordinary extraction — which CAN seek.
 *
 * Throwing is what makes it actionable: Media3 surfaces the `IOException`, `Media3PlaybackController`
 * raises a `StreamFailure`, and the ladder re-resolves. A fault that is logged but not raised is
 * invisible to everything downstream, which is the shape this repo has paid for repeatedly.
 *
 * Instrumented for the same reason as `ChunkedDataSourceTest`: `BaseDataSource` and `DataSpec` are
 * Media3 Android classes. No device behaviour and no network — a fake transport supplies the bytes.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class APrematureSabrEndIsNotTheEndTest {

    private val audio = SabrFormat(itag = 251, lastModified = 42L, xtags = null)

    /** A server that answers once and then goes quiet — the reported failure exactly. */
    private fun streamThatStopsShort(totalBytes: Long?) = SabrStream(
        url = "https://example.test/videoplayback",
        ustreamerConfig = byteArrayOf(1),
        format = audio,
        kind = SabrTrackKind.AUDIO,
        transport = object : SabrTransport {
            private var index = 0
            override suspend fun post(url: String, body: ByteArray): ByteArray =
                if (index++ == 0) oneRun() else ByteArray(0)
        },
        totalBytes = totalBytes,
        durationMs = DURATION_MS,
    )

    /**
     * A server that always answers with media the reader cannot use — the OTHER way a read gives up.
     *
     * Every answer carries our own itag, just never at the offset being read, so the empty-answer
     * budget is never spent and the read runs out of fetches instead. `SabrStream` records that as a
     * stall on the read rather than as the stream being spent, and this must still raise a fault: the
     * failure it replaces was returning end-of-input, which tells ExoPlayer the video is over.
     */
    private fun streamThatNeverReachesTheReader(totalBytes: Long?) = SabrStream(
        url = "https://example.test/videoplayback",
        ustreamerConfig = byteArrayOf(1),
        format = audio,
        kind = SabrTrackKind.AUDIO,
        transport = object : SabrTransport {
            private var index = 0
            override suspend fun post(url: String, body: ByteArray): ByteArray =
                oneRun(at = FAR_AHEAD + index++ * CHUNK.toLong())
        },
        totalBytes = totalBytes,
        durationMs = DURATION_MS,
    )

    /** One MEDIA run, framed as YouTube frames it. */
    private fun oneRun(at: Long = 0): ByteArray {
        val header = umpPart(
            MEDIA_HEADER,
            protoNumber(1, 0) + protoNumber(3, audio.itag.toLong()) +
                protoNumber(6, at) + protoNumber(14, CHUNK.toLong()),
        )
        return header + umpPart(MEDIA, byteArrayOf(0) + ByteArray(CHUNK) { 7 })
    }

    private fun umpPart(type: Int, payload: ByteArray) =
        varint(type.toLong()) + varint(payload.size.toLong()) + payload

    private fun varint(value: Long) = byteArrayOf(
        0xF0.toByte(),
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun protoNumber(field: Int, value: Long): ByteArray {
        val out = mutableListOf<Byte>()
        out.add((field shl 3).toByte())
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.add(b.toByte())
                break
            }
            out.add((b or 0x80).toByte())
        }
        return out.toByteArray()
    }

    private fun drain(source: SabrDataSource): Int {
        val buffer = ByteArray(BUFFER)
        var reads = 0
        while (reads < MAX_READS) {
            val got = source.read(buffer, 0, buffer.size)
            reads++
            if (got == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
        }
        return reads
    }

    /** THE case: 1% of a stated 53MB is a failure the ladder can act on, not an ending. */
    @Test
    fun aStreamThatStopsShortThrows() {
        val source = SabrDataSource(streamThatStopsShort(TOTAL))
        source.open(DataSpec(android.net.Uri.parse("sabr://test")))

        val thrown = runCatching { drain(source) }.exceptionOrNull()

        assertTrue(
            "an empty read far short of the stated length must raise an IOException so recovery " +
                "re-resolves — reporting end-of-input is what made a 61-minute video 'finish' at 1%. " +
                "Got: $thrown",
            thrown is IOException,
        )
    }

    /**
     * A stream of UNKNOWN length still reports a plain ending.
     *
     * Live streams state no content length. Throwing there would make every live stream fail at its
     * natural end — worse than the bug being fixed, so it is asserted rather than assumed.
     */
    @Test
    fun aStreamWithNoStatedLengthStillEndsNormally() {
        val source = SabrDataSource(streamThatStopsShort(null))
        source.open(DataSpec(android.net.Uri.parse("sabr://test")))

        assertEquals(
            "with no stated length there is nothing to fall short of",
            C.RESULT_END_OF_INPUT,
            drain(source),
        )
    }

    /** THE second door: the bytes at this offset never arrive, and that is not the video ending. */
    @Test
    fun aStreamThatNeverReachesTheReaderThrows() {
        val source = SabrDataSource(streamThatNeverReachesTheReader(TOTAL))
        source.open(DataSpec(android.net.Uri.parse("sabr://test")))

        val thrown = runCatching { drain(source) }.exceptionOrNull()

        assertTrue(
            "a read that spent its whole fetch budget without its bytes must raise an IOException — " +
                "reporting end-of-input is what makes ExoPlayer mark the whole duration loaded. Got: $thrown",
            thrown is IOException,
        )
    }

    /**
     * And a stall on a stream of UNKNOWN length is a fault too, unlike a quiet end.
     *
     * Deliberate, and the one behaviour this pair does not share. There is nothing to fall SHORT of
     * without a stated length, so the premature-end judgement cannot speak — but a format's real end is
     * the server answering with nothing, and that is [aStreamWithNoStatedLengthStillEndsNormally]. Six
     * answers carrying media for the wrong offset is not an ending at any length, so a live stream gets
     * a fault it can recover from rather than a silent stop.
     */
    @Test
    fun aStallWithNoStatedLengthIsStillAFault() {
        val source = SabrDataSource(streamThatNeverReachesTheReader(null))
        source.open(DataSpec(android.net.Uri.parse("sabr://test")))

        val thrown = runCatching { drain(source) }.exceptionOrNull()

        assertTrue("a stalled live stream reported a clean ending. Got: $thrown", thrown is IOException)
    }

    private companion object {
        const val CHUNK = 4096

        /** Far enough past the reader that no answer can ever satisfy it. */
        const val FAR_AHEAD = 8L * 1024 * 1024
        const val BUFFER = 8192
        const val TOTAL = 53_458_433L
        const val DURATION_MS = 3_664_121L
        const val MAX_READS = 12
        const val MEDIA_HEADER = 20
        const val MEDIA = 21
    }
}
