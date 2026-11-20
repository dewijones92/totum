package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag

public class ReceiverLog(
    private val sink: (warn: Boolean, line: String) -> Unit = { warn, line ->
        if (warn) Diag.warn("cast", line) else Diag.log("cast", line)
    },
) {
    private var last: Seen? = null
    private var requests = 0

    private data class Seen(val state: Int, val idle: Int, val current: Int, val loading: Int)

    @Synchronized
    public fun status(state: Int, idleReason: Int, currentItemId: Int, loadingItemId: Int, positionMs: Long) {
        val now = Seen(state, idleReason, currentItemId, loadingItemId)
        if (now == last) return
        val before = last
        last = now
        val error = state == PLAYER_STATE_IDLE && idleReason == IDLE_REASON_ERROR
        val line = "dewidebug the receiver says ${stateName(state)}" +
            (if (state == PLAYER_STATE_IDLE) ", idle because ${idleName(idleReason)}" else "") +
            " [item=$currentItemId loading=$loadingItemId position=${positionMs}ms; " +
            "was ${before?.let { stateName(it.state) } ?: "nothing yet"}]"
        sink(error, line)
    }

    @Synchronized
    public fun sent() {
        requests++
        if (requests <= LOG_FIRST_REQUESTS) sink(false, "dewidebug a request went to the receiver (#$requests)")
    }

    public fun error(type: String?, detailedCode: Int?, reason: String?, requestId: Long, custom: String?) {
        sink(
            true,
            "dewidebug the receiver reported an error: type=${type ?: "none"} " +
                "detailedErrorCode=${detailedCode ?: "none"} reason=${reason ?: "none"} " +
                "requestId=$requestId customData=${custom ?: "none"}",
        )
    }

    @Synchronized
    public fun reset() {
        last = null
        requests = 0
    }

    public companion object {
        public const val PLAYER_STATE_IDLE: Int = 1
        public const val IDLE_REASON_ERROR: Int = 4
        private const val LOG_FIRST_REQUESTS = 5

        private val STATES = listOf("unknown", "idle", "playing", "paused", "buffering", "loading")
        private val IDLE_REASONS = listOf(
            "no reason given",
            "it finished",
            "it was cancelled",
            "it was interrupted by another load",
            "of an ERROR",
        )

        public fun stateName(state: Int): String = STATES.getOrNull(state) ?: "state $state"

        public fun idleName(reason: Int): String = IDLE_REASONS.getOrNull(reason) ?: "reason $reason"
    }
}
