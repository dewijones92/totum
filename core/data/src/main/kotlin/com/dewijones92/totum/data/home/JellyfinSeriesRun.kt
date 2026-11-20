package com.dewijones92.totum.data.home

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.jellyfinEpisode

public class JellyfinSeriesRun(public val group: QueueGroup, public val items: List<PlayableItem>)

public suspend fun JellyfinApi.seriesRunOf(item: MediaItem): JellyfinSeriesRun? {
    val code = item.jellyfinEpisode ?: return null
    val name = item.author ?: return null
    val series = findSeries(name)
    if (series == null) {
        Diag.log("jellyfin", "${item.id.value}: no series named \"$name\" was found, so it plays on its own")
        return null
    }
    val episodes = seasonsOf(series).flatMap { episodesOf(it) }.map { playable(it) }
    if (episodes.none { it.item.id == item.id }) {
        Diag.log(
            "jellyfin",
            "${item.id.value} (S${code.season}E${code.episode}) is not among the ${episodes.size} episode(s) read " +
                "for \"$name\", so it plays on its own",
        )
        return null
    }
    Diag.log(
        "jellyfin",
        "${item.id.value} (S${code.season}E${code.episode}) queued with its series \"$name\": " +
            "${episodes.size} episode(s)",
    )
    return JellyfinSeriesRun(QueueGroup("jellyfin:series:${series.id}", "📺 ${series.name}"), episodes)
}
