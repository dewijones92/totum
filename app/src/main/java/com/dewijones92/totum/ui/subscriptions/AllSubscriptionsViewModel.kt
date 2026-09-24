package com.dewijones92.totum.ui.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.channel.ChannelCheckProgress
import com.dewijones92.totum.data.channel.ChannelLatestUploads
import com.dewijones92.totum.data.feed.FeedCache
import com.dewijones92.totum.data.podcast.PodcastRepository
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceActivity
import com.dewijones92.totum.domain.latestUploadFirst
import com.dewijones92.totum.domain.youTubeChannelId
import com.dewijones92.totum.innertube.feeds.AccountFeed
import com.dewijones92.totum.ui.videos.FeedChoice
import com.dewijones92.totum.ui.videos.cacheKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AllSubscriptionsViewModel(
    private val podcasts: PodcastRepository,
    channels: Flow<List<MediaSource.VideoChannel>>,
    private val feedCache: FeedCache,
    private val channelUploads: ChannelLatestUploads? = null,
    private val checkScope: CoroutineScope? = null,
    computation: CoroutineDispatcher = Dispatchers.Default,
    private val unsubscribeChannel: suspend (MediaSource.VideoChannel) -> Boolean = { false },
) : ViewModel() {

    fun unsubscribe(sources: List<MediaSource>) {
        (checkScope ?: viewModelScope).launch {
            val channelsFailed = mutableListOf<String>()
            sources.forEach { source ->
                when (source) {
                    is MediaSource.PodcastFeed -> podcasts.unsubscribe(source.id)
                    is MediaSource.VideoChannel -> if (!unsubscribeChannel(source)) channelsFailed += source.title
                }
            }
            val shows = sources.count { it is MediaSource.PodcastFeed }
            val named = channelsFailed.take(FAILED_NAMED).joinToString(prefix = " [", postfix = "]")
                .takeIf { channelsFailed.isNotEmpty() }.orEmpty()
            Diag.log(
                "subs",
                "bulk unsubscribe: ${sources.size} (shows=$shows channels=${sources.size - shows} " +
                    "channelsNotWrittenToAccount=${channelsFailed.size}$named)",
            )
        }
    }

    val checking: StateFlow<ChannelCheckProgress?> = channelUploads?.progress ?: MutableStateFlow(null)

    private val subscribedChannels = channels

    private val checkedUploads: Flow<List<MediaItem>> = channelUploads?.latest() ?: flowOf(emptyList())

    fun checkChannels(force: Boolean = false) {
        val uploads = channelUploads ?: return
        val scope = checkScope ?: return
        scope.launch {
            val ids = subscribedChannels.first { it.isNotEmpty() }.mapNotNull { it.youTubeChannelId }
            Diag.log("subs", "checking ${ids.size} channels for their latest upload (force=$force)")
            uploads.refresh(ids, force)
        }
    }

    private val cachedVideos: Flow<List<MediaItem>> = flow { emit(feedCache.items(SUBSCRIPTIONS_FEED.cacheKey())) }

    val sources: StateFlow<List<SourceActivity>?> = combine(
        podcasts.observeSubscriptions(),
        podcasts.observeEpisodes(),
        channels,
        combine(cachedVideos, checkedUploads) { cached, checked -> cached + checked },
    ) { shows, episodes, subscribedChannels, videos ->
        latestUploadFirst(shows.map { it.source } + subscribedChannels, episodes + videos).also { ranked ->
            describe(ranked, shows.size, subscribedChannels.size, videos.size)
        }
    }
        .flowOn(computation)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    private var lastDescription: String? = null

    private fun describe(ranked: List<SourceActivity>, shows: Int, channels: Int, videos: Int) {
        val dated = ranked.count { it.latest != null }
        val top = ranked.take(TOP_LOGGED).joinToString { "\"${it.source.title}\"@${it.latest?.publishedAt ?: "none"}" }
        val line = "all subscriptions: shows=$shows channels=$channels videoUploadsKnown=$videos " +
            "withADatedUpload=$dated/${ranked.size} top=[$top]"
        if (checking.value == null && line != lastDescription) {
            lastDescription = line
            Diag.log("subs", line)
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val TOP_LOGGED = 3
        private const val FAILED_NAMED = 5
        private val SUBSCRIPTIONS_FEED = FeedChoice.Account(AccountFeed.SUBSCRIPTIONS)

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AllSubscriptionsViewModel(
                    podcasts = container.podcastRepository,
                    channels = container.accountSubscriptions.channels,
                    feedCache = container.feedCache,
                    channelUploads = container.channelLatestUploads,
                    checkScope = container.applicationScope,
                    unsubscribeChannel = { container.accountSubscriptions.setSubscribed(it, subscribed = false) },
                )
            }
        }
    }
}
