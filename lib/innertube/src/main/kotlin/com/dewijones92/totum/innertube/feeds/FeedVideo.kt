package com.dewijones92.totum.innertube.feeds

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.youTubeWatchUrl

/**
 * A video in an account feed (home, subscriptions, watch later, history).
 * [watchUrl] is the stable handle resolved to a stream on play; the same
 * shape backs every feed, so one parser and one row render them all.
 */
public data class FeedVideo(
    val videoId: String,
    val title: String,
    val author: String?,
    val durationSeconds: Long?,
    val thumbnailUrl: HttpUrl?,
    val watchUrl: HttpUrl,
    /** Normal video, live stream or Short — lets a unified feed tag each item. */
    val kind: Kind = Kind.VIDEO,
    /** How YouTube renders the published date (e.g. "2 days ago"); null if absent. */
    val publishedText: String? = null,
    /** How YouTube renders the view count ("1.2M views"); null if the tile omits it. */
    val viewsText: String? = null,
    /** Behind a channel membership — it will not play or download without one. */
    val membersOnly: Boolean = false,
    /**
     * The uploader's `UC…` channel id, when the tile carried it.
     *
     * Worth keeping because the alternative was catastrophic: with only [author] — a display
     * name — "go to channel" had to discover the channel by running a **full yt-dlp
     * extraction of the video**, which measured **12.5 seconds** on Dewi's phone (8s of
     * Python and JS-runtime startup, then a 4.4s extract) for a string YouTube had already
     * sent. Every tile in a live subscriptions feed carries it: 45 of 45, verified
     * 2026-07-31.
     */
    val channelId: String? = null,
) {
    public enum class Kind { VIDEO, LIVE, SHORT }

    public companion object {
        public fun watchUrlFor(videoId: String): HttpUrl? = youTubeWatchUrl(videoId)
    }
}
