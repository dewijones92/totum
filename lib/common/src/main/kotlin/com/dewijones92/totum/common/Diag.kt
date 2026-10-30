package com.dewijones92.totum.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * A rolling in-memory trail of what the app just did, sent with a crash so the failure
 * arrives with the story that led to it.
 *
 * It lives here — in the platform-neutral module every other module already depends on —
 * rather than in `:app`, because the most valuable breadcrumbs come from the lower
 * layers: playback transitions, codec rejections, extraction failures. A trail that only
 * the UI could write would miss exactly the lines that diagnose a bug.
 *
 * Bounded by **time first, count second**: "what was the app doing when it went wrong" is
 * a question about the last half hour, not the last N events, and a chatty minute of
 * position ticks must not push out the thing that actually broke twenty minutes ago.
 */
public object Breadcrumbs {

    /**
     * Nothing younger than this is ever dropped. Chosen to match how a problem is
     * actually reported — noticed now, sent a few minutes later, after the interesting
     * part has already scrolled past.
     */
    private const val RETENTION_MS = 30 * 60 * 1_000L

    /**
     * Kept regardless of age, so a quiet session still has context. Well above the "last
     * 30 events" originally asked for: 30 can be a single second of position ticks.
     */
    private const val MIN_ENTRIES = 400

    /** A ceiling on memory, for a session chatty enough that 30 minutes is a lot of lines. */
    private const val MAX_ENTRIES = 5_000

    private val entries = ConcurrentLinkedDeque<Entry>()
    private val count = AtomicInteger()

    public data class Entry(val atEpochMs: Long, val tag: String, val message: String)

    public fun record(tag: String, message: String) {
        val now = System.currentTimeMillis()
        entries.addLast(Entry(now, tag, message))
        count.incrementAndGet()
        trim(now)
    }

    /**
     * Drops an entry only when it is **both** beyond the count floor and older than the
     * retention window — so age protects a line the count would have evicted, and the
     * count protects a line that age would have.
     */
    private fun trim(now: Long) {
        while (count.get() > MAX_ENTRIES) dropOldest()
        while (count.get() > MIN_ENTRIES) {
            val oldest = entries.peekFirst() ?: return
            if (now - oldest.atEpochMs <= RETENTION_MS) return
            dropOldest()
        }
    }

    private fun dropOldest() {
        if (entries.pollFirst() != null) count.decrementAndGet() else count.set(0)
    }

    /** Oldest first, so a report reads top-to-bottom as a story. */
    public fun snapshot(): List<Entry> = entries.toList()

    public fun clear() {
        entries.clear()
        count.set(0)
    }

    public fun formatTime(epochMs: Long): String =
        TIME_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
}

/**
 * The one call for "log this and remember it" — used from every module in place of
 * `android.util.Log`, so anything worth logging is also crash context.
 *
 * Printing is delegated to a [Sink] the platform installs, which is what lets this
 * object live in a pure-JVM module: `:app` installs an `android.util.Log` sink at
 * startup, tests leave the silent default in place.
 */
public object Diag {

    public enum class Level { INFO, WARN }

    public fun interface Sink {
        public fun write(level: Level, tag: String, message: String, error: Throwable?)
    }

    /** Silent by default so unit tests and pure-JVM callers need no setup. */
    public var sink: Sink = Sink { _, _, _, _ -> }

    public fun log(tag: String, message: String) {
        Breadcrumbs.record(tag, message)
        sink.write(Level.INFO, tag, message, null)
    }

    /** Records a throwable that was handled, so swallowed failures still leave a trail. */
    public fun warn(tag: String, message: String, error: Throwable? = null) {
        val text = if (error == null) message else "$message — ${error.javaClass.name}: ${error.message}"
        Breadcrumbs.record(tag, text)
        sink.write(Level.WARN, tag, text, error)
    }

    /** The single logcat tag everything goes out under, so `logcat -s dewidebug` sees it all. */
    public const val LOGCAT_TAG: String = "dewidebug"
}
