package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import java.time.Instant
import kotlin.time.Duration

/** Stable identity of a [MediaItem] within its source; never blank. */
@JvmInline
public value class MediaItemId(public val value: String) {
    init {
        require(value.isNotBlank()) { "MediaItemId must not be blank" }
    }
}

/**
 * One playable thing — a video or a podcast episode. Which pillar it belongs
 * to is determined by the [MediaSource] behind [sourceId], not by this type:
 * playback, queueing, and downloads are identical for both.
 */
public data class MediaItem(
    val id: MediaItemId,
    val sourceId: SourceId,
    val title: String,
    val publishedAt: Instant?,
    /**
     * Human published date as the source renders it (e.g. YouTube's "2 days
     * ago"), for sources that give relative text rather than an absolute
     * [publishedAt]. The UI prefers this when set, else formats [publishedAt].
     */
    val publishedText: String? = null,
    val duration: Duration?,
    /** Who made it: podcast/feed name or channel/uploader. Shown as the artist line. */
    val author: String? = null,
    val description: String? = null,
    val thumbnailUrl: HttpUrl? = null,
    /** Where the playable media lives (podcast enclosure, resolved stream); null until known. */
    val mediaUrl: HttpUrl? = null,
    /**
     * How many have watched/listened, as the source renders it ("1.2M views"), or null when
     * the source does not say. Text rather than a number because YouTube only ever gives
     * text, and re-formatting a parsed approximation would say the same thing less
     * accurately; sources that DO give a number format it with [formatViewCount] so every
     * list reads the same either way.
     */
    val viewsText: String? = null,
    /**
     * Behind a channel membership. Worth showing rather than discovering at play time: a
     * members-only video looks identical in a list and then fails with "Join this channel
     * to get access" — three of them sat unexplained in a real download queue.
     */
    val membersOnly: Boolean = false,
    /** Whether this is a normal video, a live stream or a Short — for feed tagging. */
    val contentKind: MediaContentKind = MediaContentKind.STANDARD,
    /** Named points along the media (video/podcast chapters), earliest first; empty if none. */
    val chapters: List<Chapter> = emptyList(),
    /**
     * Where this item's SOURCE lives — the uploader's channel page, or a podcast's feed —
     * when the listing that produced the item said so. Null when it did not.
     *
     * Pillar-neutral on purpose: [sourceId] is the *listing* an item arrived in, which for a
     * video from an account feed is `ytfeed:SUBSCRIPTIONS`, not the channel. So "go to
     * channel" / "go to podcast" had nothing to navigate to and had to *discover* the source
     * — for a video, by running a full yt-dlp extraction of it, which measured **12.5
     * seconds** on Dewi's phone. Every YouTube feed tile carries its channel id already
     * (45 of 45, verified 2026-07-31); it was simply thrown away.
     */
    val sourceUrl: HttpUrl? = null,
) {
    init {
        require(duration == null || duration.isPositive()) { "duration must be positive when present" }
    }
}

/**
 * The same item, now playable: whatever [stream] actually says, and the listing's facts kept.
 *
 * **A resolution answers "how do I play this", not "what is this"** — and until this existed the
 * two were conflated. Resolving a video builds a fresh [MediaItem] from what the extractor says,
 * in three separate places, and the extractor says nothing about view counts and nothing about
 * when a thing was published (`publishedAt = null` in all three). So an item that carried
 * "1.2M views · 2 days ago" in every list arrived at the player with both gone, and the video page
 * could not have shown them however it was written.
 *
 * Which facts belong to which side is the whole content of this function, so it is here — one
 * pillar-neutral rule with tests — rather than spelled out at each call site where the next
 * resolution path would forget one.
 *
 * Substituted once, at the moment of resolution, so every downstream path (a quality switch,
 * Listen mode, a stall rescue) carries the listing's facts without knowing it has to.
 */
public fun MediaItem.withStreamFrom(stream: MediaItem): MediaItem = copy(
    // The resolution wins wherever it actually says something, so nothing about how a resolved
    // item plays or reads changes. This is deliberately the SMALLEST rule that fixes the loss:
    // an early version also preferred the listing's title, which changed shipped behaviour for no
    // reason anybody had asked for, and `SearchViewModelTest` caught it.
    mediaUrl = stream.mediaUrl ?: mediaUrl,
    duration = stream.duration ?: duration,
    description = stream.description ?: description,
    title = stream.title.ifBlank { title },
    author = stream.author ?: author,
    thumbnailUrl = stream.thumbnailUrl ?: thumbnailUrl,
    sourceUrl = stream.sourceUrl ?: sourceUrl,
    chapters = stream.chapters.ifEmpty { chapters },
    // The one fact that runs the other way: yt-dlp knows an upload date, and a link shared by its id
    // knows nothing, so the resolution fills a silent listing here (reported 2026-09-06 as queue rows
    // with no date). The listing's own date still stands when it has one.
    publishedAt = publishedAt ?: stream.publishedAt,
    // Everything not named above keeps the listing's value — viewsText, publishedText,
    // membersOnly, contentKind — because a resolution has nothing to say about any of them. A null
    // from something that never had an opinion is silence, not news, and treating it as news is
    // exactly how these facts were being thrown away.
)

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
