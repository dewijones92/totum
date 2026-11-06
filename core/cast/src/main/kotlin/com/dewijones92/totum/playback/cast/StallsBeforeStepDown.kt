package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.domain.QualityLabel
import java.util.concurrent.ConcurrentHashMap

public data class CastStep(val from: QualityLabel, val toHeight: Int)

public class StallsBeforeStepDown(
    private val now: () -> Long = System::currentTimeMillis,
    private val stalls: Int = STALLS,
    private val windowMs: Long = WINDOW_MS,
) {
    private val seen = ConcurrentHashMap<String, MutableList<Long>>()

    @Synchronized
    public fun stalled(mediaId: String): Boolean {
        val at = now()
        val recent = seen.getOrPut(mediaId) { mutableListOf() }
        recent.removeAll { at - it > windowMs }
        recent += at
        if (recent.size < stalls) return false
        recent.clear()
        return true
    }

    public fun countIn(mediaId: String): Int = seen[mediaId]?.size ?: 0

    @Synchronized
    public fun forget(mediaId: String) {
        seen.remove(mediaId)
    }

    public companion object {
        public const val STALLS: Int = 2
        public const val WINDOW_MS: Long = 90_000L
    }
}
