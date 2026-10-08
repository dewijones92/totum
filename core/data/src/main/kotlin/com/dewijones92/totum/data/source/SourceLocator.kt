package com.dewijones92.totum.data.source

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.podcast.PodcastRepository
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.pillar
import com.dewijones92.totum.domain.statedChannel
import com.dewijones92.totum.domain.statedFeed
import com.dewijones92.totum.domain.youTubeChannelId
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.YtDlpEngine
import kotlinx.coroutines.flow.first
import kotlin.time.TimeSource

/**
 * Finds the [MediaSource] a [MediaItem] came from, so a row can navigate to its
 * source page ("go to channel" / "go to podcast"). One port for both pillars.
 *
 * Rows don't carry their source: a podcast episode's `sourceId` is its feed, but a
 * video from an account feed carries `ytfeed:<FEED>` — the feed, not the channel —
 * and `author` is only a display name.
 */
public interface SourceLocator {

    /** Null when the source can't be determined (unknown feed, unresolvable video). */
    public suspend fun locate(item: MediaItem): MediaSource?

    public fun canLocate(item: MediaItem): Boolean
}

/**
 * Resolves against data rather than sniffing URLs: a subscribed podcast feed is a
 * local lookup, and anything else is resolved through the engine, which reports the
 * uploader's own page. So the pillar is decided by what the item *is*, not by
 * pattern-matching its media URL in a second place.
 */
public class DefaultSourceLocator(
    private val podcasts: PodcastRepository,
    private val engine: YtDlpEngine,
) : SourceLocator {

    override fun canLocate(item: MediaItem): Boolean = when (item.pillar) {
        MediaKind.PODCAST -> MediaSource.PodcastFeed.feedUrlOf(item.sourceId) != null
        MediaKind.VIDEO -> item.sourceUrl != null || item.mediaUrl != null
    }

    override suspend fun locate(item: MediaItem): MediaSource? {
        val started = TimeSource.Monotonic.markNow()
        var route = "subscribed feed"
        val source = subscribedFeed(item) ?: when (item.pillar) {
            MediaKind.PODCAST -> item.statedFeed().also { route = "feed named by the episode" }
            MediaKind.VIDEO -> statedSource(item)?.also { route = "channel named by the listing" }
                ?: uploaderChannel(item).also { route = "yt-dlp extraction of the video" }
        }
        Diag.log(
            "nav",
            "located \"${item.title}\" via $route in ${started.elapsedNow().inWholeMilliseconds}ms -> " +
                (source?.let { describe(it) } ?: "nothing"),
        )
        return source
    }

    private fun describe(source: MediaSource): String = when (source) {
        is MediaSource.PodcastFeed -> "feed ${source.feedUrl.value}"
        is MediaSource.VideoChannel ->
            "channel ${source.channelUrl.value} " +
                if (source.youTubeChannelId != null) "(UC id known)" else "(handle only: its page will use yt-dlp)"
    }

    /**
     * The source the listing already named — free, instant, and the answer almost every time.
     *
     * Tried before [uploaderChannel] because that one runs a **full yt-dlp extraction of the
     * video** to read one string. Measured on Dewi's phone 2026-07-31: tapping "go to channel"
     * took **12.5 seconds** — eight of them starting the Python interpreter and the JS runtime,
     * then a 4.4s extract — for a channel id YouTube had already sent with the tile.
     */
    private fun statedSource(item: MediaItem): MediaSource? = item.statedChannel()

    private suspend fun subscribedFeed(item: MediaItem): MediaSource? =
        podcasts.observeSubscriptions().first()
            .map { it.source }
            .firstOrNull { it.id == item.sourceId }

    private suspend fun uploaderChannel(item: MediaItem): MediaSource? {
        val url = item.mediaUrl ?: return null
        val metadata = (engine.extract(url) as? ExtractionResult.Success)?.metadata ?: return null
        val channelUrl = metadata.uploaderUrl?.let(HttpUrl::parse) ?: return null
        return MediaSource.VideoChannel(
            // The channel page addresses itself by URL; its id only needs to be
            // stable and distinct, and the canonical URL is both.
            id = SourceId(channelUrl.value),
            title = metadata.uploader ?: item.author.orEmpty().ifBlank { channelUrl.value },
            channelUrl = channelUrl,
        )
    }
}
