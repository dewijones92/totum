package com.dewijones92.totum.innertube.related

import com.dewijones92.totum.innertube.feeds.FeedVideo

/**
 * Port: the "up next" / related videos shown alongside a watch page. Like
 * comments, these are public — no sign-in needed — so this is independent of
 * the account seam. A related video is just a [FeedVideo]: the same shape the
 * feeds use, so the app renders and plays them through the one media path.
 */
public interface YouTubeRelated {
    public suspend fun relatedTo(videoId: String): RelatedResult
}

public sealed interface RelatedResult {
    public data class Success(val videos: List<FeedVideo>, val description: String? = null) : RelatedResult
    public data class Failure(val detail: String) : RelatedResult
}
