package com.dewijones92.totum.playback.cast

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.ChunkedDataSource
import com.dewijones92.totum.playback.OpenTimes
import com.dewijones92.totum.playback.PlaybackCache
import com.dewijones92.totum.playback.homeServerAware
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(markerClass = [UnstableApi::class])
private fun plainHttp(context: Context): DataSource.Factory =
    ChunkedDataSource.Factory(homeServerAware(DefaultDataSource.Factory(context)))

@OptIn(markerClass = [UnstableApi::class])
internal fun cachedHttpFactory(context: Context): DataSource.Factory =
    PlaybackCache.relayFactory(context, plainHttp(context))

@OptIn(markerClass = [UnstableApi::class])
internal fun directHttpFactory(context: Context): DataSource.Factory = plainHttp(context)

@OptIn(markerClass = [UnstableApi::class])
internal class CachedUpstream(
    private val factory: DataSource.Factory,
    private val direct: DataSource.Factory = factory,
    private val budget: BudgetedOpen = BudgetedOpen(),
    private val heldFrom: (url: String, position: Long) -> Long = { _, _ -> UNKNOWN },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class Ask(val position: Long, val length: Long, val ranged: Boolean)

    private class Took(val ms: Long, val times: OpenTimes, val fellBack: Boolean)

    private sealed interface Attempt {
        class Opened(val source: DataSource, val length: Long) : Attempt
        class Refused(val code: Int) : Attempt
    }

    private val opens = AtomicInteger()
    private val window = OpenWindow()

    fun get(url: String, range: String?): UpstreamReply {
        val ask = askFor(RangeAsk.parse(range))
        val times = OpenTimes(clock)
        val spec = DataSpec.Builder().setUri(url.toUri()).setPosition(ask.position).setLength(ask.length)
            .setCustomData(times).build()
        val number = opens.incrementAndGet()
        val label = "relay open #$number range=$range"
        val began = clock()
        val outcome = budget.run(
            label = label,
            primary = { attempt(factory, spec) },
            fallback = { attempt(direct, spec) },
            upstreamAsked = { times.upstreamAsked },
            discard = { (it as? Attempt.Opened)?.source?.let { source -> runCatching { source.close() } } },
        )
        noteOpen(number, url, ask, range, Took(clock() - began, times, outcome.usedFallback))
        return when (val opened = outcome.value) {
            is Attempt.Refused -> {
                Diag.warn("cast", "the cache-backed relay got ${opened.code} from the source for range=$range")
                UpstreamReply(opened.code, "text/plain", null, 0L) { ByteArray(0).inputStream() }
            }
            is Attempt.Opened -> replyOf(url, ask, opened.source, opened.length)
        }
    }

    private fun attempt(from: DataSource.Factory, spec: DataSpec): Attempt {
        val source = from.createDataSource()
        return try {
            Attempt.Opened(source, source.open(spec))
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            runCatching { source.close() }
            Attempt.Refused(e.responseCode)
        } catch (e: IOException) {
            runCatching { source.close() }
            throw e
        }
    }

    private fun noteOpen(number: Int, url: String, ask: Ask, range: String?, took: Took) {
        val openMs = took.ms
        val fellBack = took.fellBack
        val upstreamMs = took.times.upstreamMs()
        val cacheMs = (openMs - upstreamMs).coerceAtLeast(0)
        window.add(openMs, fellBack)
        val held = runCatching { heldFrom(url, ask.position) }.getOrDefault(UNKNOWN)
        val line = "dewidebug relay open #$number range=$range took ${openMs}ms " +
            "(waiting on the cache ${cacheMs}ms, upstream first byte ${upstreamMs}ms), " +
            "cache holds ${if (held == UNKNOWN) "an unknown number of" else held} bytes from there, " +
            "read ${if (fellBack) "directly from upstream after the cache gave no answer" else "through the cache"}"
        when {
            openMs >= SLOW_OPEN_MS ->
                Diag.warn(
                    "cast",
                    "$line; SLOW: over ${SLOW_OPEN_MS}ms to open. Who holds a locked span is not known " +
                        "(SimpleCache does not say): the phone's own player loaders, the tee filler or an earlier " +
                        "request",
                )
            number <= LOG_FIRST || openMs >= NOTABLE_OPEN_MS -> Diag.log("cast", line)
        }
        window.summary(clock())?.let { Diag.log("cast", it) }
    }

    private fun askFor(ask: RangeAsk): Ask = when (ask) {
        is RangeAsk.Closed -> Ask(ask.first, ask.bytes, ranged = true)
        is RangeAsk.Open -> Ask(ask.first, C.LENGTH_UNSET.toLong(), ranged = true)
        else -> Ask(0L, C.LENGTH_UNSET.toLong(), ranged = false)
    }

    private fun replyOf(url: String, ask: Ask, source: DataSource, opened: Long): UpstreamReply {
        val bytes = opened.takeIf { it != C.LENGTH_UNSET.toLong() }
        val total = url.substringAfter("clen=", "").substringBefore('&').toLongOrNull()
        val last = bytes?.let { ask.position + it - 1 } ?: total?.let { it - 1 }
        val contentRange = if (ask.ranged && last != null) "bytes ${ask.position}-$last/${total ?: "*"}" else null
        return UpstreamReply(
            status = if (ask.ranged) PARTIAL else OK,
            contentType = source.responseHeaders["Content-Type"]?.firstOrNull() ?: mimeOf(url),
            contentRange = contentRange,
            contentLength = bytes,
        ) { Reading(source) }
    }

    private fun mimeOf(url: String): String =
        url.substringAfter("mime=", "").substringBefore('&').replace("%2F", "/", ignoreCase = true)
            .ifEmpty { "application/octet-stream" }

    private inner class OpenWindow {
        private val startedAt = AtomicLong(0)
        private var count = 0
        private var fellBack = 0
        private var longestMs = 0L
        private var totalMs = 0L

        @Synchronized
        fun add(openMs: Long, fellBackToUpstream: Boolean) {
            count++
            totalMs += openMs
            if (fellBackToUpstream) fellBack++
            if (openMs > longestMs) longestMs = openMs
            startedAt.compareAndSet(0, clock())
        }

        @Synchronized
        fun summary(now: Long): String? {
            val from = startedAt.get()
            if (from == 0L || now - from < SUMMARY_EVERY_MS || count == 0) return null
            val text = "dewidebug relay opens, last ${(now - from) / MS_PER_S}s: $count open(s), " +
                "average ${totalMs / count}ms, longest ${longestMs}ms, $fellBack read around the cache"
            startedAt.set(now)
            count = 0
            fellBack = 0
            longestMs = 0
            totalMs = 0
            return text
        }
    }

    private class Reading(private val source: DataSource) : InputStream() {
        private var closed = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val got = source.read(buffer, off, len)
            return if (got == C.RESULT_END_OF_INPUT) -1 else got
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { source.close() }
        }
    }

    internal companion object {
        const val UNKNOWN = -1L
        const val SLOW_OPEN_MS = 3_000L
        private const val NOTABLE_OPEN_MS = 1_000L
        private const val LOG_FIRST = 5
        private const val SUMMARY_EVERY_MS = 30_000L
        private const val MS_PER_S = 1_000L
        private const val OK = 200
        private const val PARTIAL = 206
        private const val BYTE_MASK = 0xff
    }
}
