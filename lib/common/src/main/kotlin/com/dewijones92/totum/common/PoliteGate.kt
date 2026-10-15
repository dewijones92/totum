package com.dewijones92.totum.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow

public data class SlowDown(val untilMs: Long, val reason: String)

public class PoliteGate(
    private val maxInFlight: Int = DEFAULT_IN_FLIGHT,
    private val perSecond: Double = DEFAULT_PER_SECOND,
    private val burst: Int = DEFAULT_BURST,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val jitter: () -> Double = Math::random,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val permits = Semaphore(maxInFlight, true)
    private val bucket = Any()
    private var tokens = burst.toDouble()
    private var refilledAt = now()
    private var level = 0
    private var waitsSinceLog = 0
    private var waitedSinceLogMs = 0L
    private var loggedAt = 0L
    private var slowSlotsSinceLog = 0
    private var slowSlotLoggedAt = Long.MIN_VALUE / 2
    private val holders = Holders()
    private val slowing = MutableStateFlow<SlowDown?>(null)
    public val slowDown: StateFlow<SlowDown?> = slowing.asStateFlow()

    public val inFlight: Int get() = maxInFlight - permits.availablePermits()
    public val queued: Int get() = permits.queueLength

    public fun <T> pass(what: String, block: () -> T): T {
        val queuedAt = now()
        if (!permits.tryAcquire(SLOW_SLOT_MS, TimeUnit.MILLISECONDS)) {
            noteSlowSlot(what, now() - queuedAt)
            permits.acquire()
        }
        val held = holders.hold(what, now())
        try {
            val slotMs = now() - queuedAt
            val waitedMs = waitOut(what) { sleep(it) }
            noteWait(what, slotMs, waitedMs)
            return block()
        } finally {
            holders.release(held)
            permits.release()
        }
    }

    public suspend fun <T> passing(what: String, block: suspend () -> T): T {
        val queuedAt = now()
        var noted = false
        while (!permits.tryAcquire()) {
            pause(SLOT_POLL_MS)
            if (!noted && now() - queuedAt >= SLOW_SLOT_MS) {
                noted = true
                noteSlowSlot(what, now() - queuedAt)
            }
        }
        val held = holders.hold(what, now())
        try {
            val slotMs = now() - queuedAt
            val waitedMs = waitOutSuspending(what)
            noteWait(what, slotMs, waitedMs)
            return block()
        } finally {
            holders.release(held)
            permits.release()
        }
    }

    private fun noteSlowSlot(what: String, waitedMs: Long) {
        val at = now()
        val line = synchronized(bucket) {
            slowSlotsSinceLog++
            if (at - slowSlotLoggedAt < SLOW_SLOT_LOG_EVERY_MS) return
            val unlogged = slowSlotsSinceLog - 1
            slowSlotsSinceLog = 0
            slowSlotLoggedAt = at
            "$what has waited ${waitedMs}ms for a slot; all $maxInFlight are held by " +
                "${holders.describe(at)}; $queued queued" +
                if (unlogged > 0) " ($unlogged more slow wait(s) since the last line)" else ""
        }
        Vitals.add("polite.slowSlots")
        Diag.warn("polite", line)
    }

    public fun tooManyRequests(what: String, retryAfterMs: Long?) {
        val (waitMs, until) = synchronized(bucket) {
            val at = now()
            val current = slowing.value?.untilMs?.takeIf { it > at }
            if (current == null) level = min(level + 1, MAX_LEVEL)
            val backOff = min(BASE_BACK_OFF_MS * 2.0.pow(level - 1), MAX_BACK_OFF_MS.toDouble())
            val wait = maxOf((backOff * (1 + JITTER * (2 * jitter() - 1))).toLong(), retryAfterMs ?: 0)
            val until = maxOf(current ?: 0, at + wait)
            slowing.value = SlowDown(until, "HTTP 429 on $what")
            wait to until
        }
        Vitals.add("polite.tooManyRequests")
        Diag.warn(
            "polite",
            "YouTube said slow down (429) on $what; every YouTube call waits until ${until - now()}ms from now " +
                "(level $level, this answer alone ${waitMs}ms" +
                "${retryAfterMs?.let { ", Retry-After ${it}ms" }.orEmpty()})",
        )
    }

    public fun served() {
        val current = slowing.value ?: return
        if (now() < current.untilMs) return
        synchronized(bucket) { level = 0 }
        slowing.value = null
        Diag.log("polite", "YouTube is serving again after a slow-down; back-off reset")
    }

    public fun diagnostics(): Map<String, String> = mapOf(
        "polite.inFlight" to "$inFlight of $maxInFlight",
        "polite.queued" to queued.toString(),
        "polite.level" to synchronized(bucket) { level }.toString(),
        "polite.waitsNotYetLogged" to synchronized(bucket) { "$waitsSinceLog call(s), ${waitedSinceLogMs}ms" },
    )

    private inline fun waitOut(what: String, wait: (Long) -> Unit): Long {
        var waited = 0L
        while (true) {
            val ms = nextWait(what)
            if (ms == 0L) return waited
            wait(ms)
            waited += ms
        }
    }

    private suspend fun waitOutSuspending(what: String): Long {
        var waited = 0L
        while (true) {
            val ms = nextWait(what)
            if (ms == 0L) return waited
            pause(ms)
            waited += ms
        }
    }

    private fun nextWait(what: String): Long = synchronized(bucket) {
        val at = now()
        slowing.value?.untilMs?.let { until ->
            if (until > at) {
                Diag.log("polite", "$what held ${until - at}ms: YouTube asked us to slow down")
                return until - at
            }
        }
        tokens = min(burst.toDouble(), tokens + (at - refilledAt) / MILLIS_PER_SECOND * perSecond)
        refilledAt = at
        if (tokens >= 1) {
            tokens -= 1
            return 0L
        }
        ((1 - tokens) / perSecond * MILLIS_PER_SECOND).toLong().coerceAtLeast(1)
    }

    private fun noteWait(what: String, slotMs: Long, rateMs: Long) {
        Vitals.add("polite.calls")
        if (slotMs + rateMs == 0L) return
        Vitals.add("polite.waitedMs", slotMs + rateMs)
        val summary = synchronized(bucket) {
            waitsSinceLog++
            waitedSinceLogMs += slotMs + rateMs
            if (now() - loggedAt < SUMMARY_EVERY_MS) return
            val line = "$waitsSinceLog call(s) waited ${waitedSinceLogMs}ms in all for the gate " +
                "($maxInFlight at once, $perSecond/s); latest $what: ${slotMs}ms for a slot, ${rateMs}ms for the rate"
            waitsSinceLog = 0
            waitedSinceLogMs = 0
            loggedAt = now()
            line
        }
        Diag.log("polite", summary)
    }

    public companion object {
        public const val DEFAULT_IN_FLIGHT: Int = 4
        public const val DEFAULT_PER_SECOND: Double = 2.0
        public const val DEFAULT_BURST: Int = 10
        private const val BASE_BACK_OFF_MS = 30_000.0
        private const val MAX_BACK_OFF_MS = 600_000L
        private const val MAX_LEVEL = 10
        private const val JITTER = 0.2
        private const val MILLIS_PER_SECOND = 1000.0
        private const val SUMMARY_EVERY_MS = 60_000L
        private const val SLOT_POLL_MS = 25L
        private const val SLOW_SLOT_MS = 2_000L
        private const val SLOW_SLOT_LOG_EVERY_MS = 5_000L
    }
}

private class Holders {
    private val next = AtomicLong()
    private val held = ConcurrentHashMap<Long, Pair<String, Long>>()

    fun hold(what: String, at: Long): Long = next.incrementAndGet().also { held[it] = what to at }

    fun release(token: Long) {
        held.remove(token)
    }

    fun describe(at: Long): String =
        held.values.sortedBy { it.second }.joinToString(
            prefix = "[",
            postfix = "]"
        ) { (what, since) -> "$what for ${at - since}ms" }
}
