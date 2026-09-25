package com.dewijones92.totum.innertube.subscriptions

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.youTubeChannelUrl

/**
 * A channel the signed-in user subscribes to. [channelUrl] is derived from
 * [channelId] so it plugs straight into the app's existing channel-subscribe
 * seam (which keys sources by URL).
 */
public data class SubscribedChannel(
    val channelId: String,
    val title: String,
    val channelUrl: HttpUrl,
    val avatarUrl: HttpUrl?,
) {
    public companion object {
        /** Builds the canonical channel URL for a `UC…` id, or null if unusable. */
        public fun channelUrlFor(channelId: String): HttpUrl? =
            youTubeChannelUrl(channelId)
    }
}
