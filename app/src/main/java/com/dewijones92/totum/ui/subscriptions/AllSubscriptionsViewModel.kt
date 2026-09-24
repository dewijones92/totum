package com.dewijones92.totum.ui.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.feed.FeedCache
import com.dewijones92.totum.data.podcast.PodcastRepository
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceActivity
import com.dewijones92.totum.domain.latestUploadFirst
import com.dewijones92.totum.innertube.feeds.AccountFeed
import com.dewijones92.totum.ui.videos.FeedChoice
import com.dewijones92.totum.ui.videos.cacheKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AllSubscriptionsViewModel(
    podcasts: PodcastRepository,
    channels: Flow<List<MediaSource.VideoChannel>>,
    private val feedCache: FeedCache,
) : ViewModel() {

    private val cachedVideos = MutableStateFlow<List<MediaItem>>(emptyList())

    val sources: StateFlow<List<SourceActivity>> = combine(
        podcasts.observeSubscriptions(),
        podcasts.observeEpisodes(),
        channels,
        cachedVideos,
    ) { shows, episodes, subscribedChannels, videos ->
        latestUploadFirst(shows.map { it.source } + subscribedChannels, episodes + videos).also { ranked ->
            describe(ranked, shows.size, subscribedChannels.size, videos.size)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private var lastDescription: String? = null

    init {
        viewModelScope.launch {
            cachedVideos.value = feedCache.items(SUBSCRIPTIONS_FEED.cacheKey())
        }
    }

    private fun describe(ranked: List<SourceActivity>, shows: Int, channels: Int, videos: Int) {
        val dated = ranked.count { it.latest != null }
        val top = ranked.take(TOP_LOGGED).joinToString { "\"${it.source.title}\"@${it.latest?.publishedAt ?: "none"}" }
        val line = "all subscriptions: shows=$shows channels=$channels cachedVideos=$videos " +
            "withADatedUpload=$dated/${ranked.size} top=[$top]"
        if (line != lastDescription) {
            lastDescription = line
            Diag.log("subs", line)
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val TOP_LOGGED = 3
        private val SUBSCRIPTIONS_FEED = FeedChoice.Account(AccountFeed.SUBSCRIPTIONS)

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AllSubscriptionsViewModel(
                    podcasts = container.podcastRepository,
                    channels = container.accountSubscriptions.channels,
                    feedCache = container.feedCache,
                )
            }
        }
    }
}
