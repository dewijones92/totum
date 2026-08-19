package com.dewijones92.totum.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.Vitals
import com.dewijones92.totum.sabr.SabrStream
import kotlinx.coroutines.runBlocking

/**
 * Feeds ExoPlayer one track fetched over SABR.
 *
 * SABR is the protocol behind everything YouTube will not hand out as a plain URL — measured
 * 2026-07-31, an ANDROID-client stream URL serves its first megabyte and then 403s forever, and
 * the rest is only reachable this way. [SabrStream] already turns the conversation into bytes in
 * order, so all that is left here is Media3's shape.
 *
 * **Blocking on purpose.** `DataSource` is a blocking interface and ExoPlayer calls it on its
 * own loader thread, never the main one, so `runBlocking` here is correct rather than a
 * shortcut — the alternative would be an extra thread hop to reach the same wait.
 *
 * **Not seekable to an arbitrary byte.** SABR is asked for a media TIME, not an offset, so a
 * reader that opens at a position we have not reached gets nothing. That is fine for playing
 * from the start and is the honest limit of this first version; seeking needs the position
 * translated into `player_time_ms`, which is written up in docs/todos/sabr-streaming.md.
 */
@UnstableApi
public class SabrDataSource(private val stream: SabrStream) : BaseDataSource(true) {

    private var uri: Uri? = null
    private var position = 0L
    private var pending: ByteArray = ByteArray(0)
    private var pendingAt = 0
    private var opened = false
    private var opens = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        position = dataSpec.position
        pending = ByteArray(0)
        pendingAt = 0
        opened = true
        opens++
        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        val length = stream.contentLength
        // A non-zero open position is a SEEK, and seeking is the known hole in this path: SABR
        // is asked for a media time, not a byte offset, so bytes before that offset were never
        // fetched and never will be. Said loudly and by name, because the symptom otherwise is
        // "the player froze after I scrubbed" with nothing to connect it to.
        if (position > 0 && position == stream.readTo) {
            // Not a seek at all: ExoPlayer's loader closes and reopens a source during ordinary
            // playback, and this one carries on exactly where the last read stopped. It used to be
            // reported as a stall risk, which was true only while every reopen built a cold stream.
            Diag.log("sabr", "continuing at byte $position (open #$opens)")
        } else if (position > 0) {
            Vitals.add("sabr.seekAttempts")
            Diag.warn(
                "sabr",
                "SEEK to byte $position — a real jump, not a continuation (the stream had read to " +
                    "${stream.readTo}). SABR is time-addressed, not byte-addressed, so expect this to " +
                    "stall. open #$opens, ${stream.describeProgress()}",
            )
        } else {
            Diag.log("sabr", "opened at $position of ${length ?: -1} bytes (open #$opens)")
        }
        return length?.minus(position) ?: C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (pendingAt >= pending.size) {
            pending = runBlocking { stream.read(position) }
            pendingAt = 0
            if (pending.isEmpty()) {
                // Stopping SHORT of the stated length is a FAULT, and it has to be raised rather than
                // logged. Reported from a real device (0.1.435, commit 3a31b58): itag 251 served 920030B
                // of 53458433B -- 1% of a 61-minute video -- and this returned plain end-of-input, so
                // ExoPlayer believed the video had finished, marked the whole duration loaded, and every
                // later seek "succeeded" instantly into a stream that was not there. Nothing failed, so
                // the recovery ladder never ran and never fell back to extraction, which CAN seek.
                //
                // Throwing is what makes it actionable: Media3 surfaces the IOException,
                // Media3PlaybackController raises a StreamFailure, and the ladder re-resolves.
                if (stream.endedPrematurely) {
                    Vitals.add("sabr.prematureEndRaised")
                    val served = stream.describeProgress()
                    Diag.warn("sabr", "stopped short at byte $position — failing so recovery can re-resolve; $served")
                    throw SabrPrematureEndException(
                        "SABR stopped short: $served — this is a stalled stream, not the end of the video",
                    )
                }
                // A genuine ending, or a stream of unknown length (a live one states none), where there
                // is nothing to fall short OF.
                return C.RESULT_END_OF_INPUT
            }
        }
        val taken = minOf(length, pending.size - pendingAt)
        pending.copyInto(buffer, offset, pendingAt, pendingAt + taken)
        pendingAt += taken
        position += taken
        bytesTransferred(taken)
        return taken
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
            // On close so a report has the whole picture per track even if playback was
            // abandoned: latency, how many reads had to wait, and how far it actually got.
            Diag.log("sabr", "closed at $position — ${stream.describeProgress()}")
        }
        pending = ByteArray(0)
        pendingAt = 0
    }
}
