package com.dewijones92.totum.ai

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.Taste
import com.dewijones92.totum.data.ask.tasteOf
import com.dewijones92.totum.data.podcast.subscribedSources
import com.dewijones92.totum.data.spoiler.SpoilerRules
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.titleOf
import com.dewijones92.totum.innertube.playlists.PlaylistVideosResult
import com.dewijones92.totum.stars.LIKED_VIDEOS_BROWSE_ID
import com.dewijones92.totum.stats.StatEvent
import kotlinx.coroutines.flow.first

private const val TAG = "taste"
private const val OWNED_TITLES = 200
private const val LIKED_KEPT_MS = 15 * 60_000L

private var likedAt = 0L
private var likedTitles: List<String> = emptyList()

private fun List<String>.visible(): List<String> = filterNot { it == SpoilerRules.NEUTRAL }

internal suspend fun appTaste(c: AppContainer): Taste {
    c.musicFavourites.loadOnce()
    val shelf = c.musicFavourites.shelf.value
    val shield = c.aiFeatures.spoilerShield
    val listens = c.statsLog.all().filterIsInstance<StatEvent.Listen>().map {
        it.copy(title = shield.titleOf(MediaItemId(it.itemId), it.title, it.author))
    }
    return tasteOf(
        sources = subscribedSources(c.podcastRepository, c.accountSubscriptions.channels).first(),
        artists = shelf.artists.map { it.name },
        albums = shelf.albums.map { listOfNotNull(it.title, it.artist).joinToString(" — ") },
        listens = listens,
        now = System.currentTimeMillis(),
        starred = c.stars.starred.first().map { shield.titleOf(it.item) }.visible(),
        liked = likedTitlesOf(c).visible(),
    ).also {
        Diag.log(
            TAG,
            "taste: ${it.channels.size} channels, ${it.shows.size} shows, ${it.starred.size} starred, " +
                "${it.liked.size} liked; titles of shielded items go as their safe titles and a hidden one is left out",
        )
    }
}

private suspend fun likedTitlesOf(c: AppContainer): List<String> {
    val now = System.currentTimeMillis()
    if (now - likedAt < LIKED_KEPT_MS) return likedTitles
    val shield = c.aiFeatures.spoilerShield
    val fetched = (c.youTubePlaylists.videosIn(LIKED_VIDEOS_BROWSE_ID) as? PlaylistVideosResult.Success)
        ?.page?.items?.map { shield.titleOf(MediaItemId(it.videoId), it.title, it.author) }
    if (fetched != null) {
        likedTitles = fetched
        likedAt = now
    }
    return likedTitles
}

internal suspend fun ownedTitles(c: AppContainer): List<String> {
    val shield = c.aiFeatures.spoilerShield
    val downloaded = c.downloadManager.observeDownloaded().first().map { shield.titleOf(it.playable.item) }
    val played = c.statsLog.all().filterIsInstance<StatEvent.Listen>().sortedByDescending { it.at }
        .map { shield.titleOf(MediaItemId(it.itemId), it.title, it.author) }
    return (downloaded + played).visible().distinct().take(OWNED_TITLES)
}
