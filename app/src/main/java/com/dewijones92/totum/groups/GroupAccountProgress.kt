package com.dewijones92.totum.groups

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.youTubeChannelId
import com.dewijones92.totum.innertube.feeds.FeedResult
import com.dewijones92.totum.innertube.feeds.SeenProgress
import com.dewijones92.totum.innertube.feeds.YouTubeFeeds

class GroupAccountProgress(private val feeds: YouTubeFeeds) {

    suspend fun read(channel: MediaSource.VideoChannel) {
        val channelId = channel.youTubeChannelId
        if (channelId == null) {
            Diag.log(TAG, "\"${channel.title}\": no channel id in ${channel.channelUrl}, account progress not asked")
            return
        }
        when (val result = feeds.channel(channelId)) {
            is FeedResult.Success -> {
                val ids = result.page.items.map { it.videoId }
                val seen = SeenProgress.positions.value
                val withProgress = ids.filter { it in seen }
                Diag.log(
                    TAG,
                    "\"${channel.title}\": account tiles=${ids.size} progressKnown=${withProgress.size} " +
                        withProgress.joinToString(" ") { id ->
                            seen.getValue(id).let { "$id=${it.positionMs / MS}s/${it.durationMs / MS}s" }
                        },
                )
            }
            FeedResult.SignedOut -> Diag.log(TAG, "\"${channel.title}\": signed out, account progress not asked")
            is FeedResult.Failure -> Diag.warn(TAG, "\"${channel.title}\": account progress failed: ${result.detail}")
        }
    }

    private companion object {
        const val TAG = "group"
        const val MS = 1_000
    }
}
