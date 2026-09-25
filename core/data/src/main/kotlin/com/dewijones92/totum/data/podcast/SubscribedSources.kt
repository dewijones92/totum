package com.dewijones92.totum.data.podcast

import com.dewijones92.totum.domain.MediaSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

public fun subscribedSources(
    podcasts: PodcastRepository,
    channels: Flow<List<MediaSource.VideoChannel>>,
): Flow<List<MediaSource>> = combine(podcasts.observeSubscriptions(), channels) { shows, subscribed ->
    shows.map { it.source } + subscribed
}
