package com.dewijones92.totum.common

/**
 * The one canonical form of a YouTube watch URL: `https://www.youtube.com/watch?v=<id>`.
 *
 * A video arrives under many URLs — `youtu.be/<id>?si=<tracking>` from the share sheet,
 * `m.youtube.com/watch?v=<id>` from mobile, `/shorts/<id>` from a reel — and the app uses
 * the URL as the video's IDENTITY: it is the `MediaItemId`, the resolve cache's key, and
 * what the queue dedupes on. So two spellings of one video are two different videos to
 * everything downstream.
 *
 * That is what a shared link hit (0.1.228): sharing a video already in the queue added a
 * second, unrelated copy rather than jumping to the one already there, and its resolve
 * could not hit a cache entry stored under the canonical spelling.
 *
 * Returns null for anything that is not a YouTube watch link, so a podcast enclosure or an
 * arbitrary URL passes through untouched.
 */
public fun HttpUrl.youTubeVideoId(): String? {
    val raw = value
    val id = when {
        "youtu.be/" in raw -> raw.substringAfter("youtu.be/")
        "/shorts/" in raw -> raw.substringAfter("/shorts/")
        "/watch?" in raw -> raw.substringAfter("/watch?").split('&')
            .firstOrNull { it.startsWith("v=") }?.removePrefix("v=") ?: return null
        else -> return null
    }
    return id.takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' }
        .takeIf { it.length == ID_LENGTH }
}

/**
 * [this] as the canonical watch URL, or unchanged when it is not a YouTube video link.
 *
 * Tracking parameters go, deliberately — `si` identifies the sharer, and carrying it into a
 * stored id would keep it in the queue and the play history forever. A `t=` start offset
 * goes too: the app does not honour it today, and a URL that means "this video at 2:30"
 * being a different video from "this video" is exactly the bug this exists to stop.
 */
public fun HttpUrl.canonicalWatchUrl(): HttpUrl =
    youTubeVideoId()?.let { HttpUrl.parse("https://www.youtube.com/watch?v=$it") } ?: this

/**
 * Whether [this] is a YouTube channel id — `UC` and 22 more id characters, exactly.
 *
 * Here, beside the video-id rule, because "what shape is a YouTube identifier" is one fact
 * and it had started to drift: `:core:data`'s subscription import kept a private copy of the
 * same regex. The predicate earns its place by being what *identifies* a channel browse among
 * a tile's menu entries, where the alternative is reading a fixed array index that varies by
 * tile.
 */
public fun String.isYouTubeChannelId(): Boolean = CHANNEL_ID.matches(this)

/** The first channel id appearing anywhere in [this] — a bare id, a channel URL, or a feed URL. */
public fun String.findYouTubeChannelId(): String? = CHANNEL_ID.find(this)?.value

private val CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")

/** YouTube video ids are always this long; anything else is a false match. */
private const val ID_LENGTH = 11
