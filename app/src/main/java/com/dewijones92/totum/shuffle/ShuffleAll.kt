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
import com.dewijones92.totum.domain.toPlayableOrNull
import com.dewijones92.totum.domain.youTubeChannelId
import com.dewijones92.totum.innertube.channel.ChannelVideos
import com.dewijones92.totum.innertube.channel.YouTubeChannel
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.common.toMediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlin.time.TimeSource

class AppSourcePages(
    private val innerTube: YouTubeChannel,
    private val channels: ChannelRepository,
    private val podcasts: PodcastRepository,
) : SourcePages {
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
        val id = source.youTubeChannelId ?: return when (part) {
            SourcePart.SHORTS -> Page.empty()
            SourcePart.UPLOADS -> when (val r = channels.fetchChannelVideos(source.channelUrl)) {
                is ChannelVideosResult.Success -> Page.last(r.videos)
                is ChannelVideosResult.Failure -> null.also { Diag.warn(TAG, "\"${source.title}\" via yt-dlp: $r") }
            }
        }
        val result = when (part) {
            SourcePart.UPLOADS -> innerTube.videos(id, after)
            SourcePart.SHORTS -> innerTube.shorts(id, after)
        }
        return when (result) {
            is ChannelVideos.Success -> result.page.map { it.toMediaItem(source.id) }
            is ChannelVideos.Failure -> null.also { Diag.warn(TAG, "\"${source.title}\" $part page: $result") }
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
}

class ShuffleAll(private val whole: WholeSource, private val queue: PlaybackQueue) {
    private val mutableLoading = MutableStateFlow<Map<String, Int>>(emptyMap())
    val loading: StateFlow<Map<String, Int>> = mutableLoading.asStateFlow()

    suspend fun shuffle(target: ShuffleTarget, includeShorts: Boolean): ShuffleOutcome {
        val (key, title, sources) = target
        if (key in mutableLoading.value) {
            Diag.log(TAG, "\"$title\" is already loading; ignoring a second tap")
            return ShuffleOutcome.Nothing
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
            queue.playAll(playable.shuffled(), QueueGroup(id = "shuffle:$key", title = title))
            return ShuffleOutcome.Shuffled(playable.size, gathered.unfinished)
        } finally {
            mutableLoading.update { it - key }
        }
    }

    private companion object {
        const val TAG = "shuffle"
    }
}
