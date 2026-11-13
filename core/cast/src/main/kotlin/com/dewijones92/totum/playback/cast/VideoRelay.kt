package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

public sealed interface RangeAsk {
    public data object Whole : RangeAsk

    public data class Closed(val first: Long, val last: Long) : RangeAsk {
        public val bytes: Long get() = last - first + 1
    }

    public data class Open(val first: Long) : RangeAsk

    public data class Other(val raw: String) : RangeAsk

    public companion object {
        public fun parse(header: String?): RangeAsk {
            if (header == null) return Whole
            val spec = header.substringAfter("bytes=", "").substringBefore(',').trim()
            val first = spec.substringBefore('-').toLongOrNull()
            val last = spec.substringAfter('-', "").toLongOrNull()
            return when {
                first == null -> Other(header)
                last == null -> Open(first)
                last < first -> Other(header)
                else -> Closed(first, last)
            }
        }
    }
}

public class RelayLimits(
    public val readAheadBytes: Int = READ_AHEAD_BYTES,
    public val prefetchMinBytes: Long = PREFETCH_MIN_BYTES,
    public val prefetchMaxBytes: Long = PREFETCH_MAX_BYTES,
    public val heldMaxBytes: Long = HELD_MAX_BYTES,
    public val waitForPrefetchMs: Long = WAIT_FOR_PREFETCH_MS,
    public val dropAfterMs: Long = DROP_AFTER_MS,
) {
    public companion object {
        private const val MB = 1024 * 1024
        public const val READ_AHEAD_BYTES: Int = 8 * MB
        public const val PREFETCH_MIN_BYTES: Long = 256L * 1024
        public const val PREFETCH_MAX_BYTES: Long = 16L * MB
        public const val HELD_MAX_BYTES: Long = 32L * MB
        public const val WAIT_FOR_PREFETCH_MS: Long = 20_000L
        public const val DROP_AFTER_MS: Long = 120_000L
    }
}

public class VideoRelay(
    private val fetch: (String, String?) -> UpstreamReply,
    private val log: RelayLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pool: Executor = SHARED_POOL,
    private val limits: RelayLimits = RelayLimits(),
    private val note: (String) -> Unit = { Diag.log("cast", it) },
) : AutoCloseable {

    private class Held(val bytes: ByteArray, val type: String, val total: Long?)

    private class Prefetch(
        val url: String,
        val first: Long,
        val last: Long,
        val startedAt: Long,
        val result: CompletableFuture<Held?>,
    ) {
        val bytes: Long get() = last - first + 1
    }

    private val lock = Any()
    private val prefetches = mutableListOf<Prefetch>()

    public fun serve(url: String, range: String?, head: Boolean = false): HttpReply {
        val ask = RangeAsk.parse(range)
        val request = log.begin(range)
        val closed = ask as? RangeAsk.Closed
        val held = if (head) null else closed?.let { takeHeld(url, it) }
        if (closed != null && held != null) return fromMemory(url, closed, held, request)
        val reply = try {
            fetch(url, range)
        } catch (e: IOException) {
            request.done(0, aborted = true)
            throw e
        }
        request.firstByte()
        if (reply.failed || head) {
            if (reply.failed) Diag.warn("cast", "video upstream answered ${reply.status} for range=$range")
            val body = reply.body()
            if (head && !reply.failed) runCatching { body.close() }
            request.done(0, aborted = reply.failed, expected = reply.contentLength)
            return stream(reply) { body }
        }
        return stream(reply) { bodyOf(reply, request) { prefetchAfter(url, ask, reply.totalLength) } }
    }

    private fun bodyOf(reply: UpstreamReply, request: RelayLog.Request, sourceEnded: () -> Unit): InputStream {
        val ahead = ReadAheadStream(reply.body(), limits.readAheadBytes, pool, onSourceEnded = sourceEnded)
        return CountedStream(ahead) { bytes, ended ->
            val expected = reply.contentLength
            request.done(bytes, aborted = !ended && (expected == null || bytes < expected), expected = expected)
        }
    }

    private fun stream(reply: UpstreamReply, open: () -> InputStream): HttpReply.Stream {
        val extra = buildMap {
            put("Accept-Ranges", "bytes")
            reply.contentRange?.let { put("Content-Range", it) }
        }
        return HttpReply.Stream(reply.status, reply.contentType, reply.contentLength, extra, open)
    }

    private fun fromMemory(url: String, ask: RangeAsk.Closed, held: Held, request: RelayLog.Request): HttpReply {
        val wanted = ask.bytes.toInt().coerceAtMost(held.bytes.size)
        val slice = if (wanted == held.bytes.size) held.bytes else held.bytes.copyOf(wanted)
        request.firstByte(RelayLog.Source.MEMORY)
        request.done(slice.size.toLong(), aborted = false, expected = slice.size.toLong())
        prefetchAfter(url, ask, held.total)
        val extra = mapOf(
            "Accept-Ranges" to "bytes",
            "Content-Range" to "bytes ${ask.first}-${ask.first + slice.size - 1}/${held.total ?: "*"}",
        )
        return HttpReply.Bytes(Http.PARTIAL, held.type, slice, extra)
    }

    private fun takeHeld(url: String, ask: RangeAsk.Closed): Held? {
        val entry = synchronized(lock) {
            dropStale()
            prefetches.firstOrNull { it.url == url && it.first == ask.first && ask.last <= it.last }
                ?.also { prefetches.remove(it) }
        } ?: return null
        return try {
            entry.result.get(limits.waitForPrefetchMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            entry.result.cancel(true)
            note("read-ahead of ${entry.first}-${entry.last} not ready in ${limits.waitForPrefetchMs}ms: ${e.message}")
            null
        } catch (e: java.util.concurrent.ExecutionException) {
            note("read-ahead of ${entry.first}-${entry.last} failed: ${e.cause?.message}")
            null
        }
    }

    private fun prefetchAfter(url: String, ask: RangeAsk, total: Long?) {
        if (ask !is RangeAsk.Closed || ask.bytes < limits.prefetchMinBytes) return
        val first = ask.last + 1
        if (total != null && first >= total) return
        val size = minOf(ask.bytes, limits.prefetchMaxBytes)
        val last = first + size - 1
        val entry = synchronized(lock) {
            dropStale()
            if (prefetches.any { it.url == url && it.first == first }) return
            if (size > limits.heldMaxBytes) return
            while (prefetches.sumOf { it.bytes } + size > limits.heldMaxBytes && prefetches.isNotEmpty()) {
                prefetches.removeAt(0).also {
                    it.result.cancel(true)
                    note("read-ahead of ${it.first}-${it.last} dropped to stay under ${limits.heldMaxBytes / MB}MB")
                }
            }
            Prefetch(url, first, last, clock(), CompletableFuture()).also { prefetches += it }
        }
        pool.execute { run(entry) }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun run(entry: Prefetch) {
        try {
            val reply = fetch(entry.url, "bytes=${entry.first}-${entry.last}")
            if (reply.status != Http.PARTIAL) {
                runCatching { reply.body().close() }
                entry.result.complete(null)
                note("read-ahead of ${entry.first}-${entry.last}: the source answered ${reply.status}, not 206")
                return
            }
            val wanted = entry.bytes.toInt()
            val bytes = reply.body().use { readUpTo(it, wanted) }
            val total = reply.totalLength
            val complete = bytes.size == wanted || (total != null && entry.first + bytes.size >= total)
            entry.result.complete(if (complete) Held(bytes, reply.contentType, total) else null)
            if (!complete) note("read-ahead of ${entry.first}-${entry.last} came back ${bytes.size} of $wanted bytes")
        } catch (e: IOException) {
            entry.result.complete(null)
            note("read-ahead of ${entry.first}-${entry.last} failed: ${e.message}")
        } catch (e: RuntimeException) {
            entry.result.complete(null)
            note("read-ahead of ${entry.first}-${entry.last} failed: ${e.message}")
        }
    }

    private fun readUpTo(input: InputStream, wanted: Int): ByteArray {
        val out = ByteArray(wanted)
        var have = 0
        while (have < wanted) {
            val got = input.read(out, have, wanted - have)
            if (got < 0) break
            have += got
        }
        return if (have == wanted) out else out.copyOf(have)
    }

    private fun dropStale() {
        val now = clock()
        prefetches.removeAll {
            val stale = now - it.startedAt > limits.dropAfterMs
            if (stale) it.result.cancel(true)
            stale
        }
    }

    public fun heldBytes(): Long = synchronized(lock) { prefetches.sumOf { it.bytes } }

    override fun close() {
        synchronized(lock) {
            prefetches.forEach { it.result.cancel(true) }
            prefetches.clear()
        }
    }

    private class CountedStream(
        private val inner: InputStream,
        private val onClosed: (bytes: Long, ended: Boolean) -> Unit,
    ) : InputStream() {
        private var bytes = 0L
        private var ended = false
        private var closed = false

        override fun read(): Int = inner.read().also {
            if (it >= 0) bytes++ else ended = true
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int = inner.read(buffer, off, len).also {
            if (it > 0) bytes += it else if (it < 0) ended = true
        }

        override fun available(): Int = inner.available()

        override fun close() {
            if (closed) return
            closed = true
            runCatching { inner.close() }
            onClosed(bytes, ended)
        }
    }

    private companion object {
        const val MB = 1024 * 1024L
        val SHARED_POOL: Executor = Executors.newCachedThreadPool { r ->
            Thread(r, "cast-relay").apply { isDaemon = true }
        }
    }
}
