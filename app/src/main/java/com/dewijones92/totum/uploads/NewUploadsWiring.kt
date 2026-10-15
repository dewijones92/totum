package com.dewijones92.totum.uploads

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.channel.ChannelFeedParser
import com.dewijones92.totum.data.net.FetchResult
import com.dewijones92.totum.data.net.HttpTextFetcher
import com.dewijones92.totum.data.podcast.PodcastRepository
import com.dewijones92.totum.data.podcast.PreviewResult
import com.dewijones92.totum.data.podcast.subscribedSources
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.shuffle.SourcePages
import com.dewijones92.totum.data.shuffle.SourcePart
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.youTubeChannelId
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.queue.GroupSpot
import com.dewijones92.totum.queue.PlaybackQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File

class LatestUploads(
    private val fetcher: HttpTextFetcher,
    private val podcasts: PodcastRepository,
    private val pages: SourcePages,
) {
    suspend fun of(source: MediaSource): List<MediaItem>? = when (source) {
        is MediaSource.VideoChannel -> source.youTubeChannelId?.let { feed(source, it) }
            ?: pages.page(source, SourcePart.UPLOADS, after = null)?.items
        is MediaSource.PodcastFeed -> when (val preview = podcasts.preview(source.feedUrl)) {
            is PreviewResult.Loaded -> preview.episodes
            is PreviewResult.Failed -> null.also { Diag.warn(TAG, "\"${source.title}\" feed: ${preview.detail}") }
        }
    }

    private suspend fun feed(source: MediaSource.VideoChannel, channelId: String): List<MediaItem>? =
        when (val fetched = fetcher.fetch(ChannelFeedParser.feedUrlFor(channelId))) {
            is FetchResult.Failure -> null.also { Diag.warn(TAG, "\"${source.title}\" feed: ${fetched.detail}") }
            is FetchResult.Success -> ChannelFeedParser.uploads(fetched.body)
                ?: null.also { Diag.warn(TAG, "\"${source.title}\": the reply was not a channel feed") }
        }
}

class QueueForUploads(private val queue: PlaybackQueue) : UploadsQueue {
    override suspend fun ready() {
        if (withTimeoutOrNull(QUEUE_WAIT_MS) { queue.hydrated.first { it } } == null) {
            Diag.warn(TAG, "the saved queue had not loaded after ${QUEUE_WAIT_MS}ms; checking against what is there")
        }
    }

    override fun groupIds(): Set<String> = queue.state.value.entries.mapNotNullTo(mutableSetOf()) { it.group?.id }
    override fun add(
        group: QueueGroup,
        items: List<PlayableItem>,
        spot: GroupSpot
    ): Int = queue.addToGroup(group, items, spot)
}

class FileUploadsStore(private val file: File) : UploadsStore {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): UploadsState = withContext(Dispatchers.IO) {
        runCatching { if (file.exists()) json.decodeFromString(UploadsState.serializer(), file.readText()) else null }
            .onFailure { Diag.warn(TAG, "uploads state unreadable; starting afresh", it) }
            .getOrNull() ?: UploadsState()
    }

    override suspend fun save(state: UploadsState): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(UploadsState.serializer(), state))
            if (!temp.renameTo(file)) Diag.warn(TAG, "uploads state could not be moved into place; kept the old one")
        }.onFailure { Diag.warn(TAG, "uploads state could not be saved", it) }
        Unit
    }
}

fun newUploadsOf(c: AppContainer, fetcher: HttpTextFetcher, pages: SourcePages, file: File): NewUploads = NewUploads(
    latest = LatestUploads(fetcher, c.podcastRepository, pages)::of,
    queue = QueueForUploads(c.playbackQueue),
    inboxSources = { inboxSourcesOf(c) },
    store = FileUploadsStore(file),
    adopt = { groupId -> adoptShuffleGroup(c, groupId) },
)

private suspend fun adoptShuffleGroup(c: AppContainer, groupId: String): WatchedGroup? {
    val pin = c.recentActions.recent.value.filterIsInstance<Pin.ShuffleAllOf>()
        .firstOrNull { "shuffle:${it.target}" == groupId } ?: return null
    val queued = c.playbackQueue.state.value.entries.map { it.item.item.id.value }
    val played = c.playHistoryStore.observe().first().map { it.item.id.value }
    val started = c.playbackProgressStore.observeStates().first().keys.map { it.value }
    return WatchedGroup(groupId, pin, (queued + played + started).toSet())
}

private suspend fun inboxSourcesOf(c: AppContainer): List<MediaSource> {
    val settings = c.appPreferences.settings.value
    if (!settings.inboxAll && settings.inboxSources.isEmpty()) return emptyList()
    val loaded = withTimeoutOrNull(SUBSCRIPTIONS_WAIT_MS) { c.accountSubscriptions.loaded.first { it } } != null
    if (!loaded) {
        Diag.warn(
            TAG,
            "inbox: YouTube subscriptions not loaded after ${SUBSCRIPTIONS_WAIT_MS}ms; podcasts only this time"
        )
    }
    val subscribed = subscribedSources(c.podcastRepository, c.accountSubscriptions.channels).first()
    if (settings.inboxAll) return subscribed
    val chosen = subscribed.filter { inboxKeyOf(it) in settings.inboxSources || it.id.value in settings.inboxSources }
    val matched = chosen.flatMap { listOf(inboxKeyOf(it), it.id.value) }.toSet()
    val missing = settings.inboxSources - matched
    if (missing.isNotEmpty()) {
        val why = if (loaded) "are not among your subscriptions" else "could not be matched: subscriptions not loaded"
        Diag.log(TAG, "inbox: ${missing.size} chosen source(s) $why, not checked: $missing")
    }
    return chosen
}

fun inboxKeyOf(source: MediaSource): String =
    (source as? MediaSource.VideoChannel)?.youTubeChannelId?.let { "yt:$it" } ?: source.id.value

private const val SUBSCRIPTIONS_WAIT_MS = 30_000L
private const val QUEUE_WAIT_MS = 60_000L
private const val TAG = "uploads"
