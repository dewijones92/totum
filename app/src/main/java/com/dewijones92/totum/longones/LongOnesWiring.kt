package com.dewijones92.totum.longones

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.youTubeVideoId
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.youTubeChannelId
import com.dewijones92.totum.innertube.feeds.AccountFeed
import com.dewijones92.totum.ui.videos.FeedChoice
import com.dewijones92.totum.ui.videos.cacheKey
import com.dewijones92.totum.video.LengthCheck
import com.dewijones92.totum.video.LiveTag
import com.dewijones92.totum.video.liveTagOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.time.Duration

fun longOnesOf(
    c: AppContainer,
    filesDir: File,
    unmetered: () -> Boolean,
    playStates: () -> Map<MediaItemId, PlayState>,
): LongOnes {
    val lengths = VideoLengths(File(filesDir, "video_lengths.json"), ask = { item -> askLength(c, item) })
    val sources = LongOnesSources(
        pool = { longOnesPoolOf(c) },
        playStates = { playStates() },
        withoutNotForMe = { items -> c.notForMe.without(items, "long ones") },
        liveNow = { item ->
            when (liveTagOf(item.contentKind, c.liveStates.of(item.id.value))) {
                is LiveTag.Live, is LiveTag.StartsSoon -> true
                LiveTag.WasLive, null -> false
            }
        },
        freeLength = { item -> freeLength(c, item) },
    )
    return LongOnes(sources, lengths, unmetered)
}

private suspend fun askLength(c: AppContainer, item: MediaItem): LengthCheck {
    val watch = item.mediaUrl?.takeIf { it.youTubeVideoId() == item.id.value }
        ?: return LengthCheck.SaysNone("not a YouTube video")
    return c.videoResolver.lengthCheck(watch)
}

private suspend fun freeLength(c: AppContainer, item: MediaItem): Duration? {
    val watch = item.mediaUrl?.takeIf { it.youTubeVideoId() == item.id.value } ?: return null
    return withContext(Dispatchers.IO) { c.videoResolver.knownLength(watch) }
}

private suspend fun longOnesPoolOf(c: AppContainer): LongOnesPool {
    val channels = withTimeoutOrNull(CHANNELS_WAIT_MS) { c.accountSubscriptions.channels.first { it.isNotEmpty() } }
        .orEmpty()
    val channelIds = channels.mapNotNull { it.youTubeChannelId }.toSet()
    val shows = c.podcastRepository.observeSubscriptions().first().map { it.source.id.value }.toSet()
    val feed = c.feedCache.items(FeedChoice.Account(AccountFeed.SUBSCRIPTIONS).cacheKey())
        .filter { channelIds.isEmpty() || it.sourceUrl?.youTubeChannelId?.let { id -> id in channelIds } != false }
    val uploads = c.channelLatestUploads.latest().first()
        .filter { it.sourceUrl?.youTubeChannelId in channelIds }
    val episodes = c.podcastRepository.observeEpisodes().first().filter { it.sourceId.value in shows }
    Diag.log(
        TAG,
        "dewidebug pool read: subscribedChannels=${channelIds.size} subscribedShows=${shows.size} " +
            "feed=${feed.size} uploads=${uploads.size} episodes=${episodes.size}",
    )
    return LongOnesPool(feed = feed, uploads = uploads, episodes = episodes)
}

private const val CHANNELS_WAIT_MS = 10_000L
private const val TAG = "longones"
