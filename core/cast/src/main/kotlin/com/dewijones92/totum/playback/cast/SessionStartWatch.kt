package com.dewijones92.totum.playback.cast

public class SessionStartWatch(
    private val later: (Long, () -> Unit) -> Unit = castLater("cast-session-start"),
    private val waitMs: Long = WAIT_MS,
    private val onSilent: (device: String, waitedMs: Long) -> Unit,
) {
    private var generation = 0
    private var heard = true

    @Synchronized
    public fun routeSelected(device: String) {
        generation++
        heard = false
        val mine = generation
        later(waitMs) { check(mine, device) }
    }

    @Synchronized
    public fun sessionCallback() {
        heard = true
    }

    @Synchronized
    private fun check(armed: Int, device: String) {
        if (armed != generation || heard) return
        heard = true
        onSilent(device, waitMs)
    }

    public companion object {
        public const val WAIT_MS: Long = 5_000L
    }
}
