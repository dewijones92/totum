package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.isYouTubeVideoId
import com.dewijones92.totum.domain.pillar
import com.dewijones92.totum.innertube.related.RelatedResult
import com.dewijones92.totum.ui.common.toMediaItem
import kotlinx.coroutines.flow.first

internal fun exploreModeFor(c: AppContainer) = ExploreMode(
    initiallyOn = c.appPreferences.settings.value.exploreByDefault,
    related = { item -> relatedForExplore(c, item) },
    peek = { c.playbackQueue.peek(it) },
    nowPlaying = { c.playbackQueue.nowPlaying.value },
    onTurnedOn = { c.playbackController.disarmNext("Explore turned on: a related video follows instead") },
    cap = { c.appPreferences.settings.value.exploreCap },
)

internal fun nextEpisodesOf(
    ended: MediaItem,
    episodes: List<MediaItem>,
    finished: (MediaItemId) -> Boolean,
): List<MediaItem> {
    val show = episodes.filter { it.sourceId == ended.sourceId && it.id != ended.id && !finished(it.id) }
    val at = ended.publishedAt ?: return show.sortedByDescending { it.publishedAt }
    val (later, earlier) = show.partition { it.publishedAt?.isAfter(at) == true }
    return later.sortedBy { it.publishedAt } + earlier.sortedByDescending { it.publishedAt }
}

private suspend fun nextEpisodesForExplore(c: AppContainer, item: MediaItem): List<MediaItem>? {
    val episodes = c.podcastRepository.observeEpisodes().first()
    val states = c.playbackProgressStore.observeStates().first()
    val next = nextEpisodesOf(item, episodes) { states[it] == PlayState.Played }
    Diag.log(
        "explore",
        "${item.id.value} is a podcast episode: ${next.size} unfinished episode(s) of the same show " +
            "out of ${episodes.count { it.sourceId == item.sourceId }} known",
    )
    return next.takeIf { it.isNotEmpty() }
}

private suspend fun relatedForExplore(c: AppContainer, item: MediaItem): List<MediaItem>? {
    if (item.pillar == MediaKind.PODCAST) return nextEpisodesForExplore(c, item)
    val id = item.id.value.takeIf(::isYouTubeVideoId) ?: return null.also {
        Diag.log("explore", "${item.id.value} is not a YouTube video, so it has no related list")
    }
    return when (val result = c.youTubeRelated.relatedTo(id)) {
        is RelatedResult.Success -> result.videos.map { it.toMediaItem(SourceId("ytrelated")) }
        is RelatedResult.Failure -> null.also {
            Diag.warn(
                "explore",
                "related to $id could not be read: ${result.detail}"
            )
        }
    }
}
