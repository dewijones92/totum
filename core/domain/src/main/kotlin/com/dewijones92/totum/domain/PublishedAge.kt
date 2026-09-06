package com.dewijones92.totum.domain

import java.time.Duration
import java.time.Instant

/**
 * When a thing was published, as a relative age — the one rule that reads YouTube's wording and the
 * one that writes ours, so a row keeps ageing after it was listed.
 *
 * Dewi, 2026-09-06: *"some of the queue items don't have a date published label, e.g. 2 hours ago,
 * and this should obviously increase as time goes by."* It did not: a source's "2 hours ago" was
 * stored as text and shown verbatim for ever. Now the text is anchored to an instant the moment it
 * is observed ([anchoringPublishedAt]), the instant is what persists, and the label is re-derived
 * from it against a ticking clock ([publishedAgeText]). Months and years are the coarse units the
 * wording itself uses, so anchoring "11 years ago" is exact to the same year, not the same day.
 */
public object PublishedAge {

    /**
     * The instant a source's relative wording points at, or null for wording this does not know.
     *
     * Reads the long form ("2 hours ago", "Streamed 3 days ago", "Premiered 2 weeks ago", "1 year
     * ago") and the abbreviated one YouTube's TV surfaces use ("11y ago", "3d ago", "5mo ago").
     */
    public fun parse(publishedText: String, observedAt: Instant): Instant? {
        val match = RELATIVE.find(publishedText.trim()) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val unit = UNITS[match.groupValues[2].lowercase()] ?: return null
        return observedAt.minus(unit.multipliedBy(amount))
    }

    /** "2 hours ago" for [publishedAt] as seen from [now], in YouTube's own words and units. */
    public fun text(publishedAt: Instant, now: Instant): String {
        val elapsed = Duration.between(publishedAt, now).coerceAtLeast(Duration.ZERO)
        val seconds = elapsed.seconds
        return when {
            seconds < MINUTE -> "just now"
            seconds < HOUR -> plural(seconds / MINUTE, "minute")
            seconds < DAY -> plural(seconds / HOUR, "hour")
            seconds < WEEK -> plural(seconds / DAY, "day")
            seconds < MONTH -> plural(seconds / WEEK, "week")
            seconds < YEAR -> plural(seconds / MONTH, "month")
            else -> plural(seconds / YEAR, "year")
        }
    }

    private fun plural(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"} ago"

    private fun Duration.coerceAtLeast(floor: Duration) = if (this < floor) floor else this

    private const val MINUTE = 60L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val WEEK = 7 * DAY
    private const val MONTH = 30 * DAY
    private const val YEAR = 365 * DAY

    private const val UNIT_WORDS =
        "seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h|days?|d|weeks?|wks?|w|months?|mo|years?|yrs?|y"
    private val RELATIVE = Regex("""(\d+)\s*($UNIT_WORDS)\s+ago\b""", RegexOption.IGNORE_CASE)
    private val UNITS: Map<String, Duration> = buildMap {
        listOf("second", "seconds", "sec", "secs", "s").forEach { put(it, Duration.ofSeconds(1)) }
        listOf("minute", "minutes", "min", "mins", "m").forEach { put(it, Duration.ofSeconds(MINUTE)) }
        listOf("hour", "hours", "hr", "hrs", "h").forEach { put(it, Duration.ofSeconds(HOUR)) }
        listOf("day", "days", "d").forEach { put(it, Duration.ofSeconds(DAY)) }
        listOf("week", "weeks", "wk", "wks", "w").forEach { put(it, Duration.ofSeconds(WEEK)) }
        listOf("month", "months", "mo").forEach { put(it, Duration.ofSeconds(MONTH)) }
        listOf("year", "years", "yr", "yrs", "y").forEach { put(it, Duration.ofSeconds(YEAR)) }
    }
}

/**
 * The same item with its relative wording anchored to an instant, so it can age. A no-op for an
 * item that already carries an instant, or whose wording says nothing this can read.
 */
public fun MediaItem.anchoringPublishedAt(observedAt: Instant): MediaItem =
    if (publishedAt != null) this else copy(publishedAt = publishedText?.let { PublishedAge.parse(it, observedAt) })

/** The title a queued link wears until something resolves it — see `placeholderFor` in the app. */
public fun placeholderTitleFor(id: MediaItemId): String = "YouTube video ${id.value}"

public val MediaItem.hasPlaceholderTitle: Boolean get() = title == placeholderTitleFor(id)

/**
 * This item with every fact it lacks taken from [resolved] — how a queue row learns what it is
 * playing. Only silence is filled: a fact the row already had stands, and a placeholder title counts
 * as silence. Returns the same instance when there was nothing to learn, so a caller can tell.
 */
public fun MediaItem.fillingSilenceFrom(resolved: MediaItem): MediaItem {
    val filled = copy(
        title = if (hasPlaceholderTitle && resolved.title.isNotBlank()) resolved.title else title,
        publishedAt = publishedAt ?: resolved.publishedAt,
        publishedText = publishedText ?: resolved.publishedText,
        duration = duration ?: resolved.duration,
        author = author ?: resolved.author,
        thumbnailUrl = thumbnailUrl ?: resolved.thumbnailUrl,
        description = description ?: resolved.description,
        viewsText = viewsText ?: resolved.viewsText,
    )
    return if (filled == this) this else filled
}
