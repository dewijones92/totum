package com.dewijones92.totum.ui.ask

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.Taste
import com.dewijones92.totum.data.ask.squashed
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.asPlayable
import com.dewijones92.totum.stats.ListenKind
import com.dewijones92.totum.stats.StatEvent

fun tasteOf(
    sources: List<MediaSource>,
    artists: List<String>,
    albums: List<String>,
    listens: List<StatEvent.Listen>,
    now: Long,
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
    )
}

fun sourceNamed(name: String, sources: List<MediaSource>): MediaSource? {
    val wanted = name.squashed()
    if (wanted.isEmpty()) return null
    return sources.firstOrNull { it.title.squashed() == wanted }
        ?: sources.filter { it.title.squashed().let { title -> title.contains(wanted) || wanted.contains(title) } }
            .minByOrNull { it.title.length }
}

class AskLatest(
    private val sources: suspend () -> List<MediaSource>,
    private val latest: suspend (MediaSource) -> List<MediaItem>?,
) {
    suspend fun find(name: String): List<PlayableItem> {
        val all = sources()
        val source = sourceNamed(name, all)
        if (source == null) {
            Diag.log("ask", "latest from \"$name\": no followed channel or show by that name among ${all.size}")
            return emptyList()
        }
        val items = latest(source).orEmpty()
            .sortedByDescending { it.publishedAt }
            .take(LATEST_SHOWN)
            .map { it.asPlayable() }
        Diag.log("ask", "latest from \"$name\" -> \"${source.title}\": ${items.size} item(s)")
        return items
    }
}

private const val PLAYED_WINDOW_MS = 90L * 24 * 60 * 60 * 1000
private const val TOP_SONGS = 100
private const val TOP_PLAYED = 60
private const val LATEST_SHOWN = 5
