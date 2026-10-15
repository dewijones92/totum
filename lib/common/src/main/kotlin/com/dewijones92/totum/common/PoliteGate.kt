package com.dewijones92.totum.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.Semaphore
import kotlin.math.min
import kotlin.math.pow

/** YouTube told us to slow down: until when, and what said so. */
public data class SlowDown(val untilMs: Long, val reason: String)

public class PoliteGate(
    private val maxInFlight: Int = DEFAULT_IN_FLIGHT,
    private val perSecond: Double = DEFAULT_PER_SECOND,
    private val burst: Int = DEFAULT_BURST,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val jitter: () -> Double = Math::random,
) {
    private val permits = Semaphore(maxInFlight, true)
    private val bucket = Any()
    private var tokens = burst.toDouble()
    private var refilledAt = now()
    private var level = 0
    private val slowing = MutableStateFlow<SlowDown?>(null)
    public val slowDown: StateFlow<SlowDown?> = slowing.asStateFlow()

    public fun <T> pass(what: String, block: () -> T): T {
        enter(what)
        try {
            return block()
        } finally {
            permits.release()
        }
    }

    public suspend fun <T> passing(what: String, block: suspend () -> T): T {
        runInterruptible(Dispatchers.IO) { enter(what) }
        try {
            return block()
        } finally {
            permits.release()
        }
    }

    public fun tooManyRequests(what: String, retryAfterMs: Long?) {
        val waitMs = synchronized(bucket) {
            val backOff = BASE_BACK_OFF_MS * 2.0.pow(level)
            level = min(level + 1, MAX_LEVEL)
            val jittered = (min(backOff, MAX_BACK_OFF_MS.toDouble()) * (1 + JITTER * (2 * jitter() - 1))).toLong()
            maxOf(jittered, retryAfterMs ?: 0)
        }
        val until = now() + waitMs
        slowing.value = SlowDown(until, "HTTP 429 on $what")
        Vitals.add("polite.tooManyRequests")
        Diag.warn(
            "polite",
            "YouTube said slow down (429) on $what; every YouTube call waits ${waitMs / MILLIS_PER_SECOND}s " +
                "(level $level${retryAfterMs?.let { ", Retry-After ${it / MILLIS_PER_SECOND}s" }.orEmpty()})",
        )
    }

    public fun served() {
        val current = slowing.value ?: return
        if (now() < current.untilMs) return
        synchronized(bucket) { level = 0 }
        slowing.value = null
        Diag.log("polite", "YouTube is serving again after a slow-down; back-off reset")
    }

    private fun enter(what: String) {
        awaitSlowDown(what)
        val queuedAt = now()
        permits.acquire()
        val waitedForSlot = now() - queuedAt
        val waitedForRate = awaitToken()
        noteWait(what, waitedForSlot, waitedForRate)
    }

    private var waitsSinceLog = 0
    private var waitedSinceLogMs = 0L
    private var loggedAt = 0L

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

    private fun awaitSlowDown(what: String) {
        val until = slowing.value?.untilMs ?: return
        val left = until - now()
        if (left <= 0) return
        Diag.log("polite", "$what held ${left / MILLIS_PER_SECOND}s: YouTube asked us to slow down")
        sleep(left)
    }

    private fun awaitToken(): Long {
        var waited = 0L
        while (true) {
            val pause = synchronized(bucket) {
                val at = now()
                tokens = min(burst.toDouble(), tokens + (at - refilledAt) / MILLIS_PER_SECOND.toDouble() * perSecond)
                refilledAt = at
                if (tokens >= 1) {
                    tokens -= 1
                    return waited
                }
                ((1 - tokens) / perSecond * MILLIS_PER_SECOND).toLong().coerceAtLeast(1)
            }
            sleep(pause)
            waited += pause
        }
    }

    public companion object {
        public const val DEFAULT_IN_FLIGHT: Int = 4
        public const val DEFAULT_PER_SECOND: Double = 2.0
        public const val DEFAULT_BURST: Int = 10
        private const val BASE_BACK_OFF_MS = 30_000.0
        private const val MAX_BACK_OFF_MS = 600_000L
        private const val MAX_LEVEL = 10
        private const val JITTER = 0.2
        private const val MILLIS_PER_SECOND = 1000L
        private const val SUMMARY_EVERY_MS = 60_000L
    }
}
