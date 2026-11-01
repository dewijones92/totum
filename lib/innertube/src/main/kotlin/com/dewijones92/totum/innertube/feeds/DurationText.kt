package com.dewijones92.totum.innertube.feeds

import com.dewijones92.totum.common.ClockText

/**
 * Parses a YouTube clock string — "s", "m:ss" or "h:mm:ss" — into total
 * seconds. Each field carries into the next at base 60. Returns null if any
 * field isn't a number or there are more than three fields. Shared by every
 * InnerTube parser that reads a duration off a thumbnail overlay (feed tiles,
 * related-video lockups), so the format lives in one place.
 */
internal fun parseClockToSeconds(clock: String): Long? = ClockText.seconds(clock)?.toLong()

// YouTube renders a tile's published date as relative text ("2 days ago",
// "Streamed 3 hours ago") or, for premieres, "Premiered ...". This picks that
// metadata part out from views/other lines. Shared by the feed and related parsers.
private val PUBLISHED_HINT = Regex("""\bago\b|Streamed|Premiered""", RegexOption.IGNORE_CASE)

internal fun String.looksLikePublished(): Boolean = PUBLISHED_HINT.containsMatchIn(this)

// The view-count metadata part: "1.2M views", "No views", and the live variant
// "12K watching". Matched by shape rather than position, because YouTube reorders the
// metadata parts between tile types and a positional read silently picks the wrong one.
private val VIEWS_HINT = Regex("""\bviews?\b|\bwatching\b""", RegexOption.IGNORE_CASE)

internal fun String.looksLikeViews(): Boolean = VIEWS_HINT.containsMatchIn(this)
