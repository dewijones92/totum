package com.dewijones92.totum.innertube.player

import com.dewijones92.totum.common.HttpUrl

/**
 * What a `/player` response says a video IS, as opposed to how to stream it.
 *
 * Here because it is the difference between InnerTube being a fallback and InnerTube being
 * able to resolve a video on its own. Streams alone were never enough: playing something
 * needs a title, an author, a length and a thumbnail, and until now those only ever came from
 * yt-dlp — which is why the fast path could only ever *supplement* the slow one.
 *
 * Everything here arrives in the same 150ms response as the streams, at no extra cost.
 */
public data class PlayerDetails(
    public val videoId: String,
    public val title: String,
    public val author: String?,
    /** The uploader's `UC…` id, so a resolved video knows its channel without a second ask. */
    public val channelId: String?,
    public val lengthSeconds: Long?,
    public val thumbnailUrl: HttpUrl?,
    /**
     * The description, kept for one reason: chapters live in it.
     *
     * yt-dlp parses them out of exactly this text, so reading them here is what stops the
     * fast path silently costing a feature. See [chaptersFromDescription].
     */
    public val description: String?,
    /** True for a live stream, which has no meaningful length and cannot be seeked normally. */
    public val isLive: Boolean = false,
    public val publishedOn: java.time.LocalDate? = null,
    public val publishedAgo: String? = null,
    public val publishedAt: java.time.Instant? = null,
)

/**
 * Chapter marks parsed out of a video description, earliest first.
 *
 * A YouTube description declares chapters as lines beginning with a timestamp, and yt-dlp
 * finds them the same way. Duplicated here rather than given up: losing chapters would be a
 * silent regression the moment the fast path became primary, and "it got quicker but lost a
 * feature" is not a trade anyone asked for.
 *
 * Deliberately strict about what counts, because a description is free text and a loose rule
 * turns any "1:1 with the author" into a chapter:
 *  - the timestamp must START the line, which is how YouTube itself requires them
 *  - it needs a title after it, or there is nothing to show
 *  - the first one must be at zero, which is YouTube's own rule for a chaptered video, and
 *    the cheapest way to tell a real chapter list from a few timestamps in prose
 */
public fun chaptersFromDescription(description: String?): List<Pair<Long, String>> {
    val marks = description.orEmpty().lineSequence()
        .mapNotNull { line -> TIMESTAMP_LINE.find(line.trim()) }
        .mapNotNull { match ->
            val groups = match.groupValues
            val cleaned = groups[TITLE].trim().trimStart('-', '–', ':', '|').trim()
            if (cleaned.isEmpty()) return@mapNotNull null
            val at = (groups[HOURS].ifEmpty { "0" }.toLong() * SECONDS_PER_HOUR) +
                (groups[MINUTES].toLong() * SECONDS_PER_MINUTE) + groups[SECONDS].toLong()
            at to cleaned
        }
        .toList()
    return if (marks.firstOrNull()?.first == 0L) marks else emptyList()
}

private val TIMESTAMP_LINE = Regex("""^(?:(\d{1,2}):)?(\d{1,2}):(\d{2})\s+(.+)$""")
private const val HOURS = 1
private const val MINUTES = 2
private const val SECONDS = 3
private const val TITLE = 4
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
