package com.dewijones92.totum

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.canonicalWatchUrl
import com.dewijones92.totum.common.youTubeVideoId
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.placeholderTitleFor

/**
 * The watch URL a share should play, from the text it carried — or null to ignore it.
 *
 * Pulled out of `MainActivity` so it can be tested without an `Intent`. The rules here decide
 * whether a shared link plays at all, and two of them were paid for by real reports:
 *
 * - **Already handled means ignore.** `setIntent(Intent())` clears the ACTIVITY's intent but the
 *   TASK keeps the one it was launched with, so reopening from recents after the process is killed
 *   redelivers the original share. Report 0.1.346: one link fired five times over five hours,
 *   putting a TED talk over whatever was playing each time.
 * - **Canonicalised at the door.** The URL becomes the video's identity everywhere — MediaItemId,
 *   the resolve cache key, what the queue dedupes on — so a share sheet's `?si=` tracking parameter
 *   would make it a DIFFERENT video from the same one already queued.
 */
internal fun sharedWatchUrl(rawText: String?, alreadyHandled: Boolean): HttpUrl? {
    if (alreadyHandled) return null
    val raw = rawText ?: return null
    val match = URL_PATTERN.find(raw)?.value ?: return null
    val url = HttpUrl.parse(match) ?: return null
    return url.takeIf { candidate -> WATCH_MARKERS.any { it in candidate.value } }
        ?.canonicalWatchUrl()
}

/** A share is usually a sentence with a link in it, not a bare URL. */
private val URL_PATTERN = Regex("""https?://\S+""")

private val WATCH_MARKERS = listOf("youtube.com/watch", "youtu.be/", "youtube.com/shorts/")

/**
 * A queue entry for a shared link that could not be resolved — so the link is not lost.
 *
 * Report 0.1.477 (30 Aug): a link shared with no network spent 53 seconds in yt-dlp retries, was
 * declared unavailable, and vanished. The common cause is a bad connection, not a bad link, and the
 * queue already re-resolves from the watch URL when it plays — so the entry only needs the URL. The
 * title is the id until then; the player shows the real title, because it plays the resolved item.
 * Null for a URL that is not a YouTube video, where there is nothing to queue.
 */
internal fun placeholderFor(url: HttpUrl, sourceId: SourceId): MediaItem? = url.youTubeVideoId()?.let { id ->
    MediaItem(
        id = MediaItemId(id),
        sourceId = sourceId,
        title = placeholderTitleFor(MediaItemId(id)),
        publishedAt = null,
        duration = null,
        mediaUrl = url,
    )
}
