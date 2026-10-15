package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.isYouTubeVideoId
import com.dewijones92.totum.innertube.related.RelatedResult
import com.dewijones92.totum.ui.common.toMediaItem

internal fun exploreModeFor(c: AppContainer) = ExploreMode(
    initiallyOn = c.appPreferences.settings.value.exploreByDefault,
    related = { item -> relatedForExplore(c, item) },
    peek = { c.playbackQueue.peek(it) },
    nowPlaying = { c.playbackQueue.nowPlaying.value },
    onTurnedOn = { c.playbackController.disarmNext("Explore turned on: a related video follows instead") },
)

private suspend fun relatedForExplore(c: AppContainer, item: MediaItem): List<MediaItem>? {
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
