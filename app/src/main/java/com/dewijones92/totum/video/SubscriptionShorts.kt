package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.youTubeChannelId
import com.dewijones92.totum.innertube.channel.ChannelVideos
import com.dewijones92.totum.innertube.channel.YouTubeChannel
import com.dewijones92.totum.ui.common.toMediaItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The Shorts belonging to the channels a feed page just showed.
 *
 * Dewi wants Shorts listed alongside videos and live streams rather than behind their own button
 * (2026-08-16). They cannot come from the feed: YouTube's TV subscriptions response carries none —
 * 45 tiles and no Shorts renderer anywhere, measured against his account — so the only route is the
 * per-channel Shorts tab, which is what SmartTube's `CHANNEL_SHORTS` params reach and what this app
 * already implements for the channel page. Verified live: HTTP 200, 49 Shorts, no auth needed.
 *
 * Two limits, because this is N requests where the feed was one:
 *
 *  - [maxChannels] — only the channels nearest the top of the page. A feed page names about forty,
 *    and fetching all of them would cost forty requests to surface Shorts nobody scrolls to.
 *  - [perChannel] — the newest few from each. The tab returns everything a channel has ever posted
 *    (49 for the first one tried), newest first, and the rest is history rather than a feed.
 *
 * Requests run together rather than one after another: they are independent, and serially they
 * would take as long as the sum of the slowest network on the day.
 */
class SubscriptionShorts(
    private val channels: YouTubeChannel,
    private val maxChannels: Int = MAX_CHANNELS,
    private val perChannel: Int = PER_CHANNEL,
) {
    /**
     * The Shorts for whichever channels [feed] came from, newest-channel-first.
     *
     * Takes the feed's own items rather than a channel list, because that is what decides *which*
     * channels are worth asking about — the ones the user is looking at right now.
     */
    suspend fun forFeed(feed: List<MediaItem>): List<MediaItem> {
        val wanted = feed.mapNotNull { item -> item.channel() }
            .distinctBy { it.id }
            .take(maxChannels)
        if (wanted.isEmpty()) return emptyList()

        val fetched = coroutineScope {
            wanted.map { channel -> async { shortsOf(channel) } }.map { it.await() }
        }.flatten()
        Diag.log(
            "shorts",
            "asked ${wanted.size} channel(s) for their Shorts, got ${fetched.size}",
        )
        return fetched
    }

    private suspend fun shortsOf(channel: Channel): List<MediaItem> =
        when (val result = runCatching { channels.shorts(channel.id) }.getOrNull()) {
            is ChannelVideos.Success -> result.page.items.take(perChannel).map { short ->
                // The author the tile could not tell us. We asked one channel, so we know it, and
                // without it a Short is the only row in the feed with no channel line.
                short.toMediaItem(channel.sourceId).copy(author = channel.title)
            }
            else -> {
                Diag.warn("shorts", "no Shorts for ${channel.title}: ${result ?: "request failed"}")
                emptyList()
            }
        }

    private fun MediaItem.channel(): Channel? {
        val id = sourceUrl?.youTubeChannelId ?: return null
        return Channel(id = id, title = author ?: return null, sourceId = sourceId)
    }

    private data class Channel(val id: String, val title: String, val sourceId: SourceId)

    private companion object {
        /** How many of a page's channels to ask. A feed page names roughly forty. */
        const val MAX_CHANNELS = 12

        /** How many of each channel's Shorts to take — the newest, not its whole history. */
        const val PER_CHANNEL = 2
    }
}
