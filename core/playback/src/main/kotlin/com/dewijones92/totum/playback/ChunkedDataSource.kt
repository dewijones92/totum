package com.dewijones92.totum.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.Vitals

/**
 * Fetches a stream as a series of bounded range requests instead of one open-ended GET.
 *
 * **This is the fix for the buffering.** YouTube throttles a non-ranged `videoplayback`
 * request to roughly playback rate. Measured against one real stream: an open-ended GET
 * sustained 122–178 KB/s while ranged requests for the same bytes got 414 KB/s–1.1 MB/s.
 * The 1080p H.264 stream needs 166 KB/s — inside the throttled band — so the player was
 * being fed at almost exactly real time with no headroom. It covered about seven seconds
 * on the opening burst and then stalled for twenty-odd, over and over, on both a Pixel 7
 * and the emulator at the same position. Nothing about that is bandwidth: the connection
 * carried ten times what the stream needed the moment the request was bounded.
 *
 * Every serious YouTube client does this; it is why they do not stall.
 *
 * The wrapper is transparent. ExoPlayer opens once and reads to the end; each time a
 * chunk is exhausted the next range is opened underneath, so nothing above needs to know.
 *
 * Which range, and when to stop, is [ChunkedRead]'s — a state machine a unit test can hold.
 * This class is the socket around it, and keeps no bookkeeping of its own.
 */
// The count is DataSource's own interface surface plus the handful of helpers that decide a range's
// bounds; splitting it would scatter the one thing this class knows, which is how to range a request.
@Suppress("TooManyFunctions")
@UnstableApi
internal class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Long = DEFAULT_CHUNK_BYTES,
) : DataSource {

    private var spec: DataSpec? = null
    private var read: ChunkedRead? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        val cursor = ChunkedRead(remainingFor(dataSpec), chunkBytes)
        read = cursor
        openNextRange(cursor)
        return cursor.declaredLength
    }

    /**
     * Bytes to serve this caller, from whichever source can say.
     *
     * The three quantities are deliberately kept apart, because merging two of them is the bug
     * this had: `clen` describes the WHOLE resource and so has the caller's position taken off it,
     * while a probe answers from that position already and so does not. See [remainingFrom].
     */
    private fun remainingFor(dataSpec: DataSpec): Long {
        val clen = declaredLength(dataSpec.uri)
        if (dataSpec.length == UNKNOWN_LENGTH && clen == null) {
            return remainingFrom(dataSpec.position, probeLength(dataSpec), resourceLength = null)
        }
        return remainingFrom(dataSpec.position, dataSpec.length, clen)
    }

    /**
     * The length YouTube states in the URL's `clen`, or null when it says nothing.
     *
     * Free — no request at all — and it sidesteps the probe entirely for the streams we
     * actually play. It is the length of the whole resource, NOT of what remains from
     * wherever the caller is starting.
     */
    private fun declaredLength(uri: Uri): Long? =
        runCatching { uri.getQueryParameter("clen")?.toLongOrNull() }
            .getOrNull()
            ?.takeIf { it > 0 }

    /**
     * Asks how long the resource is, then closes without reading it.
     *
     * A bounded request answers 206 with only that range's size, so the total has to come
     * from an unbounded one. It is closed immediately, so the throttling that applies to
     * an open-ended GET never gets the chance to matter.
     *
     * The answer is bytes remaining FROM `dataSpec.position`, which is already what a caller
     * needs — unlike `clen`, which has to have the position taken off it.
     */
    private fun probeLength(dataSpec: DataSpec): Long {
        val length = runCatching { upstream.open(dataSpec) }.getOrElse { failure ->
            // Closed on EVERY way out, including this one. A failed open still leaves the source
            // holding one — Media3's DefaultDataSource assigns its delegate before opening it — and
            // `open()` then begins `checkState(dataSource == null)`. So skipping the close here made
            // the very next line, which opens the first range, throw a MESSAGELESS
            // IllegalStateException; ExoPlayer wrapped it as UnexpectedLoaderException and reported
            // "Source error", and the real reason survived only in the warning below. Twenty-two of
            // those in the CI run of 2026-09-20.
            closeRange()
            // An HTTP status is the resource answering, and answering "no" — rethrow it so
            // the player sees the real 403 rather than a second, identical failure from the
            // chunk that would follow. Recovery keys off that status; masking it behind a
            // fallback doubled every request on the dead-URL path and hid the reason.
            if (failure is HttpDataSource.InvalidResponseCodeException) throw failure
            Diag.warn("chunked", "could not measure length; falling back to one request", failure)
            return UNKNOWN_LENGTH
        }
        closeRange()
        return length
    }

    private fun openNextRange(cursor: ChunkedRead) {
        val current = spec ?: return
        if (cursor.finished) return
        val range = cursor.nextRange()
        cursor.opened(upstream.open(current.subrange(range.offset, range.bytes)))
    }

    /**
     * A loop, deliberately, where this used to call itself.
     *
     * The recursion was unbounded: every iteration re-opened a range and re-read it, so a resource
     * that answered a past-the-end range with nothing spun inside one `read()` until the stack or
     * the process gave out — no bytes, no error, and a load that never finished. [ChunkedRead] now
     * refuses to continue past a range that produced nothing, and the loop cannot outlive that.
     */
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val cursor = read ?: return C.RESULT_END_OF_INPUT
        while (!cursor.finished) {
            if (!cursor.rangeOpen) openNextRange(cursor)
            val got = upstream.read(buffer, offset, cursor.cap(length))
            if (got != C.RESULT_END_OF_INPUT) {
                cursor.served(got)
                if (!cursor.rangeOpen) closeRange()
                return got
            }
            closeRange()
            when (cursor.endOfRange()) {
                // This range is spent and there is more resource after it: open the next one.
                ChunkedRead.RangeEnd.Continue -> Unit
                ChunkedRead.RangeEnd.Ended -> return C.RESULT_END_OF_INPUT
                // Said out loud, and with the numbers, because a tail that never arrives had no
                // line of its own: 208 of 244 seconds of buffering in report 0.1.359 were spent
                // waiting for bytes the stream was never going to send, and the only trace was a
                // load that never ended. Counted too, so its rate is comparable between reports.
                ChunkedRead.RangeEnd.EndedEarly -> {
                    Diag.warn("chunked", "stream ended while it still owed bytes — ${cursor.describe()}")
                    Vitals.add("playback.streamsEndedEarly")
                    return C.RESULT_END_OF_INPUT
                }
            }
        }
        return C.RESULT_END_OF_INPUT
    }

    private fun closeRange() {
        runCatching { upstream.close() }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        closeRange()
        spec = null
        read = null
    }

    /** Wraps another factory so every source it makes fetches in ranges. */
    class Factory(
        private val upstream: DataSource.Factory,
        private val chunkBytes: Long = DEFAULT_CHUNK_BYTES,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ChunkedDataSource(upstream.createDataSource(), chunkBytes)
    }

    internal companion object {
        /**
         * Big enough that the per-request overhead is noise, small enough that the
         * throttle never engages. Roughly ten seconds of a 1080p stream.
         */
        const val DEFAULT_CHUNK_BYTES = 2L * 1024 * 1024
    }
}
