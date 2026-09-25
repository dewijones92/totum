package com.dewijones92.totum.ui.subscriptions

import com.dewijones92.totum.data.podcast.PodcastRepository
import com.dewijones92.totum.domain.MediaSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

fun subscribedSources(
    podcasts: PodcastRepository,
    channels: Flow<List<MediaSource.VideoChannel>>,
): Flow<List<MediaSource>> = combine(podcasts.observeSubscriptions(), channels) { shows, subscribed ->
    shows.map { it.source } + subscribed
}
