package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag

internal class ForegroundHold(
    private val windowMs: Long,
    private val now: () -> Long,
) {
    private var lastPlayingAtMs: Long? = null
    private var holding = false

    fun playing(isPlaying: Boolean) {
        lastPlayingAtMs = now()
        if (isPlaying) holding = false
    }

    fun msUntilRelease(): Long = lastPlayingAtMs?.let { (it + windowMs - now()).coerceAtLeast(0) } ?: 0

    fun startInForeground(mediaWants: Boolean, hasItem: Boolean, stopped: Boolean): Boolean {
        if (mediaWants) return true
        val hold = hasItem && !stopped && msUntilRelease() > 0
        if (hold != holding) {
            Diag.log(
                "playback",
                if (hold) {
                    "keeping the service in the foreground while paused for another " +
                        "${msUntilRelease() / MS_PER_MINUTE}m " +
                        "(Media3 alone leaves after 10m, and Android may refuse to restart it from the background)"
                } else {
                    "letting the service leave the foreground [item=$hasItem stopped=$stopped " +
                        "sinceLastPlayed=${lastPlayingAtMs?.let { "${(now() - it) / MS_PER_MINUTE}m" } ?: "never"}]"
                },
            )
        }
        holding = hold
        return hold
    }

    private companion object {
        const val MS_PER_MINUTE = 60_000L
    }
}
