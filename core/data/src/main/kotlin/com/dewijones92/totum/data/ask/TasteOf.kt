package com.dewijones92.totum.data.ask

import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.stats.ListenKind
import com.dewijones92.totum.stats.StatEvent

@Suppress("LongParameterList")
public fun tasteOf(
    sources: List<MediaSource>,
    artists: List<String>,
    albums: List<String>,
    listens: List<StatEvent.Listen>,
    now: Long,
    starred: List<String> = emptyList(),
    liked: List<String> = emptyList(),
): Taste {
    val recent = listens.filter { now - it.at <= PLAYED_WINDOW_MS }
    val songs = recent.filter { it.kind == ListenKind.MUSIC }
        .groupBy { listOfNotNull(it.title, it.author).joinToString(" — ") }
        .mapValues { (_, heard) -> heard.sumOf { it.heardMs } }
        .entries.sortedByDescending { it.value }.map { it.key }.take(TOP_SONGS)
    val played = recent.filter { it.kind != ListenKind.MUSIC }
        .groupBy { it.author ?: it.source ?: it.title }
        .mapValues { (_, heard) -> heard.sumOf { it.heardMs } }
        .entries.sortedByDescending { it.value }.map { it.key }.take(TOP_PLAYED)
    return Taste(
        channels = sources.filterIsInstance<MediaSource.VideoChannel>().map { it.title },
        shows = sources.filterIsInstance<MediaSource.PodcastFeed>().map { it.title },
        artists = artists,
        albums = albums,
        songs = songs,
        played = played,
        starred = starred.distinct().take(TOP_STARRED),
        liked = liked.distinct().take(TOP_LIKED),
    )
}

private const val PLAYED_WINDOW_MS = 90L * 24 * 60 * 60 * 1000
private const val TOP_SONGS = 100
private const val TOP_PLAYED = 60
private const val TOP_STARRED = 60
private const val TOP_LIKED = 60
