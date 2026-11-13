package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.domain.QualityLabel
import java.util.concurrent.ConcurrentHashMap

public data class CastStep(val from: QualityLabel, val toHeight: Int)

public class StallsBeforeStepDown(
    private val now: () -> Long = System::currentTimeMillis,
    private val stalls: Int = STALLS,
    private val windowMs: Long = WINDOW_MS,
    private val advancedMs: Long = ADVANCED_MS,
    private val apartMs: Long = APART_MS,
) {
    public enum class Count { SAME_STALL, COUNTED, STEP_DOWN }

    private class Seen(val at: Long, val positionMs: Long)

    private val seen = ConcurrentHashMap<String, MutableList<Seen>>()

    @Synchronized
    public fun stalled(mediaId: String, positionMs: Long = UNKNOWN): Boolean =
        count(mediaId, positionMs) == Count.STEP_DOWN

    @Synchronized
    public fun count(mediaId: String, positionMs: Long = UNKNOWN): Count {
        val at = now()
        val recent = seen.getOrPut(mediaId) { mutableListOf() }
        recent.removeAll { at - it.at > windowMs }
        val last = recent.lastOrNull()
        if (last != null && isTheSameStall(last, at, positionMs)) return Count.SAME_STALL
        recent += Seen(at, positionMs)
        if (recent.size < stalls) return Count.COUNTED
        recent.clear()
        return Count.STEP_DOWN
    }

    private fun isTheSameStall(last: Seen, at: Long, positionMs: Long): Boolean {
        val advanced = if (positionMs == UNKNOWN || last.positionMs == UNKNOWN) 0L else positionMs - last.positionMs
        return at - last.at < apartMs && advanced < advancedMs
    }

    public fun countIn(mediaId: String): Int = seen[mediaId]?.size ?: 0

    @Synchronized
    public fun forget(mediaId: String) {
        seen.remove(mediaId)
    }

    public companion object {
        public const val STALLS: Int = 2
        public const val WINDOW_MS: Long = 90_000L
        public const val ADVANCED_MS: Long = 5_000L
        public const val APART_MS: Long = 10_000L
        public const val UNKNOWN: Long = Long.MIN_VALUE
    }
}
