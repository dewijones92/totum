package com.dewijones92.totum.exsurge

import java.time.Duration
import java.time.Instant

public enum class RingKind { RISE_IN, BREAK_LEFT, STEPS, SNOOZED, PAUSED, SUMMONED, ASLEEP, OFF }

public data class RingReading(val fraction: Float, val until: Instant?, val kind: RingKind)

public fun ringOf(line: BannerLine, at: Instant): RingReading = when (line) {
    BannerLine.Off -> RingReading(0f, null, RingKind.OFF)
    is BannerLine.Sleeping -> RingReading(0f, line.backAt, RingKind.ASLEEP)
    is BannerLine.Paused -> RingReading(0f, line.until, RingKind.PAUSED)
    is BannerLine.Sitting -> RingReading(sittingFraction(line, at), line.summonsAt, RingKind.RISE_IN)
    is BannerLine.Summoned -> RingReading(1f, null, RingKind.SUMMONED)
    is BannerLine.Snoozed -> RingReading(1f, line.until, RingKind.SNOOZED)
    is BannerLine.Rising -> RingReading(share(line.steps.toLong(), line.needed.toLong()), null, RingKind.STEPS)
    is BannerLine.OnBreak -> RingReading(
        1f - share(Duration.between(at, line.endsAt).toMillis(), minutesInMillis(line.lengthMinutes)),
        line.endsAt,
        RingKind.BREAK_LEFT,
    )
}

public fun countdownText(until: Instant, at: Instant): String {
    val seconds = Duration.between(at, until).seconds.coerceAtLeast(0)
    val hours = seconds / SECONDS_PER_HOUR
    val minutes = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val rest = seconds % SECONDS_PER_MINUTE
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

private fun sittingFraction(line: BannerLine.Sitting, at: Instant): Float {
    val limit = minutesInMillis(line.limitMinutes)
    val left = line.summonsAt?.let { Duration.between(at, it).toMillis() }
        ?: return share(line.satMinutes, line.limitMinutes.toLong())
    return 1f - share(left, limit)
}

private fun share(part: Long, whole: Long): Float =
    if (whole <= 0) 0f else (part.toFloat() / whole).coerceIn(0f, 1f)

private fun minutesInMillis(minutes: Int): Long = minutes * MILLIS_PER_MINUTE

private const val MILLIS_PER_MINUTE = 60_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
