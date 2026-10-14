package com.dewijones92.totum.shuffle

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.data.channel.ChannelRepository
import com.dewijones92.totum.data.channel.ChannelVideosResult
import com.dewijones92.totum.data.podcast.PodcastRepository
import com.dewijones92.totum.data.podcast.PreviewResult
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.shuffle.SourcePages
import com.dewijones92.totum.data.shuffle.SourcePart
import com.dewijones92.totum.data.shuffle.WholeSource
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.toPlayableOrNull
import com.dewijones92.totum.domain.youTubeChannelId
import com.dewijones92.totum.innertube.channel.YouTubeChannel
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.pins.PinnedSource
import com.dewijones92.totum.pins.RecentActions
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.queue.RandomStarts
import com.dewijones92.totum.stats.StatsLog
import com.dewijones92.totum.stats.UseKind
import com.dewijones92.totum.ui.common.toMediaItemPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.TimeSource

class AppSourcePages(
    private val innerTube: YouTubeChannel,
    private val channels: ChannelRepository,
    private val podcasts: PodcastRepository,
) : SourcePages {
    private val resolved = ConcurrentHashMap<SourceId, String>()

    override suspend fun page(source: MediaSource, part: SourcePart, after: PageToken?): Page<MediaItem>? =
        when (source) {
            is MediaSource.VideoChannel -> channelPage(source, part, after)
            is MediaSource.PodcastFeed -> if (part == SourcePart.SHORTS) Page.empty() else feed(source)
        }

    private suspend fun channelPage(
        source: MediaSource.VideoChannel,
        part: SourcePart,
        after: PageToken?,
    ): Page<MediaItem>? {
        val id = source.youTubeChannelId ?: resolvedId(source) ?: return null
        val result = when (part) {
            SourcePart.UPLOADS -> innerTube.videos(id, after)
            SourcePart.SHORTS -> innerTube.shorts(id, after)
        }
        return result.toMediaItemPage(source.id)
            ?: null.also { Diag.warn(TAG, "\"${source.title}\" $part page: $result") }
    }

    private suspend fun resolvedId(source: MediaSource.VideoChannel): String? {
        resolved[source.id]?.let { return it }
        return when (val r = channels.fetchChannelVideos(source.channelUrl)) {
            is ChannelVideosResult.Success -> r.channelId.also {
                resolved[source.id] = it
                Diag.log(TAG, "\"${source.title}\" known only by handle; resolved to $it, paging InnerTube")
            }
            is ChannelVideosResult.Failure -> {
                Diag.warn(TAG, "\"${source.title}\" could not be resolved: $r")
                null
            }
        }
    }

    private suspend fun feed(source: MediaSource.PodcastFeed): Page<MediaItem>? {
        val stored = podcasts.observeEpisodes().first().filter { it.sourceId == source.id }
        if (stored.isNotEmpty()) return Page.last(stored)
        return when (val preview = podcasts.preview(source.feedUrl)) {
            is PreviewResult.Loaded -> Page.last(preview.episodes)
            is PreviewResult.Failed -> null.also { Diag.warn(TAG, "\"${source.title}\" feed: ${preview.detail}") }
        }
    }

    private companion object {
        const val TAG = "shuffle"
    }
}

data class ShuffleTarget(val key: String, val title: String, val sources: List<MediaSource>)

sealed interface ShuffleOutcome {
    data class Shuffled(val count: Int, val unfinished: List<String>) : ShuffleOutcome
    data object Nothing : ShuffleOutcome
    data object AlreadyLoading : ShuffleOutcome
}

class ShuffleAll(
    private val whole: WholeSource,
    private val queue: PlaybackQueue,
    private val recent: RecentActions? = null,
    private val stats: StatsLog? = null,
    private val randomStarts: RandomStarts? = null,
) {
    private val mutableLoading = MutableStateFlow<Map<String, Int>>(emptyMap())
    val loading: StateFlow<Map<String, Int>> = mutableLoading.asStateFlow()

    suspend fun shuffle(target: ShuffleTarget, includeShorts: Boolean, startAtRandom: Boolean = false): ShuffleOutcome {
        val (key, title, sources) = target
        if (key in mutableLoading.value) {
            Diag.log(TAG, "\"$title\" is already loading; ignoring a second tap")
            return ShuffleOutcome.AlreadyLoading
        }
        val started = TimeSource.Monotonic.markNow()
        Diag.log(TAG, "shuffle all \"$title\": ${sources.size} source(s), shorts=$includeShorts")
        mutableLoading.update { it + (key to 0) }
        try {
            val gathered = whole.everything(
                sources,
                includeShorts
            ) { count -> mutableLoading.update { it + (key to count) } }
            val (membersOnly, open) = gathered.items.partition { it.membersOnly }
            val playable = open.mapNotNull { it.toPlayableOrNull() }
            Diag.log(
                TAG,
                "\"$title\": ${gathered.items.size} gathered, ${playable.size} playable, " +
                    "${membersOnly.size} members-only left out, " +
                    "${open.size - playable.size} without a playable link, " +
                    "in ${started.elapsedNow()}",
            )
            if (playable.isEmpty()) return ShuffleOutcome.Nothing
            val shuffled = playable.shuffled()
            if (startAtRandom) randomStarts?.assign(shuffled.map { it.item }, "shuffle all \"$title\"")
            queue.playAll(shuffled, QueueGroup(id = "shuffle:$key", title = title))
            stats?.use(UseKind.SHUFFLE, "shuffle all")
            recent?.record(
                Pin.ShuffleAllOf(
                    target = key,
                    title = title,
                    artUrl = sources.firstNotNullOfOrNull { it.artworkUrl }?.value,
                    sources = sources.map(PinnedSource::of),
                    includeShorts = includeShorts,
                ),
                from = "shuffle all",
            )
            return ShuffleOutcome.Shuffled(playable.size, gathered.unfinished)
        } finally {
            mutableLoading.update { it - key }
        }
    }

    private companion object {
        const val TAG = "shuffle"
    }
}
