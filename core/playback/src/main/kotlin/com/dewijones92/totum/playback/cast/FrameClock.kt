package com.dewijones92.totum.playback.cast

import kotlin.math.abs
import kotlin.math.roundToLong

internal class FrameClock private constructor(val timescale: Long, private val ticksPerFrame: Long) {

    fun ticks(ptsMs: Long): Long =
        if (ticksPerFrame == 0L) {
            ptsMs
        } else {
            (ptsMs * timescale.toDouble() / (MILLIS * ticksPerFrame)).roundToLong() * ticksPerFrame
        }

    fun frameTicks(frameMs: Long): Long = if (ticksPerFrame == 0L) frameMs else ticksPerFrame

    val describe: String
        get() = if (ticksPerFrame == 0L) "the millisecond clock" else "$ticksPerFrame/$timescale s a frame"

    companion object {
        val MILLISECONDS = FrameClock(MILLIS, 0)

        fun of(frameNs: Long?): FrameClock {
            if (frameNs == null || frameNs !in SLOWEST_FRAME_NS downTo FASTEST_FRAME_NS) return MILLISECONDS
            STANDARD.firstOrNull { (timescale, ticks) ->
                abs(frameNs - ticks * NANOS / timescale) * TOLERANCE_PARTS < frameNs
            }?.let { (timescale, ticks) -> return FrameClock(timescale, ticks) }
            return FrameClock((NANOS * GENERIC_TICKS / frameNs.toDouble()).roundToLong(), GENERIC_TICKS)
        }

        private const val MILLIS = 1000L
        private const val NANOS = 1_000_000_000L
        private const val GENERIC_TICKS = 1000L
        private const val TOLERANCE_PARTS = 1000L
        private const val SLOWEST_FRAME_NS = 125_000_000L
        private const val FASTEST_FRAME_NS = 8_000_000L
        private val STANDARD = listOf(
            24_000L to 1001L, 24_000L to 1000L, 25_000L to 1000L, 30_000L to 1001L, 30_000L to 1000L,
            48_000L to 1000L, 50_000L to 1000L, 60_000L to 1001L, 60_000L to 1000L,
        )
    }
}
