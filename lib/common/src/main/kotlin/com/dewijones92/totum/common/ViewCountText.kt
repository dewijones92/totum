package com.dewijones92.totum.common

/**
 * "1.2M views" from a raw count — YouTube's own shape, so a yt-dlp-sourced row and an
 * InnerTube-sourced one read identically in the same list.
 */
public fun formatViewCount(views: Long): String = when {
    views < THOUSAND -> "$views views"
    views < MILLION -> "${(views / THOUSAND.toDouble()).trimmed()}K views"
    views < BILLION -> "${(views / MILLION.toDouble()).trimmed()}M views"
    else -> "${(views / BILLION.toDouble()).trimmed()}B views"
}

/** One decimal place, but not a trailing ".0" — YouTube writes "12K", not "12.0K". */
private fun Double.trimmed(): String {
    val oneDecimal = kotlin.math.floor(this * DECIMAL_PLACE) / DECIMAL_PLACE
    return if (oneDecimal == kotlin.math.floor(oneDecimal)) oneDecimal.toLong().toString() else oneDecimal.toString()
}

/** Truncating to one decimal: never rounds a count UP, so "1M views" is never a lie. */
private const val DECIMAL_PLACE = 10.0

private const val THOUSAND = 1_000L
private const val MILLION = 1_000_000L
private const val BILLION = 1_000_000_000L

/**
 * The count in YouTube's long wording ("24,123 views", "1,234,567 views"), or null for anything
 * else ("No views", "24K views", "12 watching"). Short wording is kept as written by its callers.
 */
public fun viewCountFromLongText(text: String): Long? {
    val match = LONG_COUNT.matchEntire(text.trim()) ?: return null
    return match.groupValues[1].replace(",", "").replace(".", "").toLongOrNull()
}

private val LONG_COUNT = Regex("""(\d{1,3}(?:[,.]\d{3})*|\d+)\s+views?""", RegexOption.IGNORE_CASE)
