package com.dewijones92.totum.ui.player

/**
 * Finds chapter-style timestamps (e.g. `0:00`, `1:23`, `1:02:03`) in a
 * description and where each seeks to. Works for a video's chapters and a
 * podcast's show notes alike, so it backs the one description surface both
 * pillars share.
 */
internal object Timestamps {

    /** A timestamp in the text: where it sits, and where it seeks to. */
    data class Match(val range: IntRange, val positionMs: Long)

    private const val MILLIS_PER_SECOND = 1000L
    private const val SECONDS_PER_MINUTE = 60
    private const val MINUTES_PER_HOUR = 60

    // m:ss / mm:ss / h:mm:ss — minutes and seconds bounded so scores and ratios don't match.
    private val REGEX = Regex("""(?<![\d:])(?:(\d{1,2}):)?([0-5]?\d):([0-5]\d)(?![\d:])""")

    fun find(text: String): List<Match> =
        REGEX.findAll(text).map { match ->
            val (hours, minutes, seconds) = match.destructured
            val totalSeconds = (hours.toIntOrNull() ?: 0) * MINUTES_PER_HOUR * SECONDS_PER_MINUTE +
                minutes.toInt() * SECONDS_PER_MINUTE +
                seconds.toInt()
            Match(match.range, totalSeconds * MILLIS_PER_SECOND)
        }.toList()
}
