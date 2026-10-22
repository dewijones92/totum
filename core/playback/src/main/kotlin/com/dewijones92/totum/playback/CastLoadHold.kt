package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag

internal class CastLoadHold(private val now: () -> Long, private val holdMs: Long = HOLD_MS) {

    private var last: PlaybackState? = null
    private var lastAt = 0L
    private var holding = false

    fun seen(state: PlaybackState): PlaybackState {
        if (holding) {
            Diag.log("cast", "${state.itemId.value} reached the receiver ${now() - lastAt}ms after it went quiet")
            holding = false
        }
        last = state
        lastAt = now()
        return state
    }

    fun nothingCurrent(onCastDevice: Boolean, castSession: Boolean): PlaybackState? {
        val held = last?.takeIf { onCastDevice && castSession && !it.hasEnded && now() - lastAt < holdMs }
        if (held == null) {
            if (holding) Diag.log("cast", "stopped holding ${last?.itemId?.value}: the receiver still reports nothing")
            holding = false
            last = null
            return null
        }
        if (!holding) {
            Diag.log(
                "cast",
                "${held.itemId.value}: the receiver reports nothing while it loads; " +
                    "still showing it as loading (up to ${holdMs}ms)",
            )
            holding = true
        }
        return held.copy(isPlaying = false, isBuffering = true, onCastDevice = true)
    }

    private companion object {
        const val HOLD_MS = 30_000L
    }
}
