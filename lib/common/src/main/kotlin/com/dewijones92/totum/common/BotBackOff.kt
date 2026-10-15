package com.dewijones92.totum.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min
import kotlin.math.pow

/** After YouTube's "Sign in to confirm you're not a bot", stop asking anonymously for a while: 1, 2, 4 … 60 min. */
public class BotBackOff(private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private var refusals = 0
    private val pausedUntil = MutableStateFlow<Long?>(null)
    public val until: StateFlow<Long?> = pausedUntil.asStateFlow()

    public fun allows(what: String): Boolean {
        val until = pausedUntil.value ?: return true
        val left = until - now()
        if (left <= 0) return true
        Vitals.add("polite.botBackOffSkips")
        Diag.log(
            "polite",
            "$what skipped: anonymous YouTube backs off for ${left / MILLIS_PER_SECOND}s more after a bot check"
        )
        return false
    }

    public fun refused(what: String) {
        val minutes = synchronized(lock) {
            val m = min(MAX_MINUTES, 2.0.pow(refusals).toLong())
            refusals = min(refusals + 1, MAX_DOUBLINGS)
            m
        }
        pausedUntil.value = now() + minutes * MILLIS_PER_MINUTE
        Vitals.add("polite.botRefusals")
        Diag.warn("polite", "YouTube bot check on $what; anonymous YouTube backs off ${minutes}min")
    }

    public fun served(what: String) {
        val wasPaused = synchronized(lock) { (refusals > 0).also { refusals = 0 } }
        if (pausedUntil.value == null && !wasPaused) return
        pausedUntil.value = null
        Diag.log("polite", "anonymous YouTube served $what; bot back-off reset")
    }

    private companion object {
        const val MAX_MINUTES = 60L
        const val MAX_DOUBLINGS = 6
        const val MILLIS_PER_SECOND = 1000L
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
