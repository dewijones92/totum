package com.dewijones92.totum.domain

import kotlin.time.Duration

public sealed interface SegmentSkip {
    public data object None : SegmentSkip

    public data class Seek(val toMs: Long, val endsItem: Boolean, val clampedFromMs: Long?) : SegmentSkip

    public data class GivenUp(val toMs: Long, val repeats: Int, val withinMs: Long) : SegmentSkip
}

public class SegmentSkipDecider {
    private val seeks = ArrayDeque<Pair<Long, Long>>()
    private var gaveUp = false

    public fun reset() {
        seeks.clear()
        gaveUp = false
    }

    public fun decide(segments: List<SkipSegment>, position: Duration, durationMs: Long?, nowMs: Long): SegmentSkip {
        if (gaveUp) return SegmentSkip.None
        val segmentEndMs = segments.skipTargetFor(position)?.inWholeMilliseconds ?: return SegmentSkip.None
        val length = durationMs?.takeIf { it > 0 }
        val toMs = length?.let { minOf(segmentEndMs, it) } ?: segmentEndMs
        while (seeks.isNotEmpty() && nowMs - seeks.first().second > WINDOW_MS) seeks.removeFirst()
        if (seeks.count { it.first == toMs } + 1 >= GIVE_UP_AT) {
            gaveUp = true
            return SegmentSkip.GivenUp(toMs, GIVE_UP_AT, WINDOW_MS)
        }
        seeks.addLast(toMs to nowMs)
        return SegmentSkip.Seek(
            toMs = toMs,
            endsItem = length != null && toMs >= length,
            clampedFromMs = segmentEndMs.takeIf { it != toMs },
        )
    }

    private companion object {
        const val WINDOW_MS = 10_000L
        const val GIVE_UP_AT = 3
    }
}
