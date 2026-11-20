package com.dewijones92.totum.data.home

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.jellyfin.JellyfinItem
import com.dewijones92.totum.data.jellyfin.JellyfinKind
import com.dewijones92.totum.domain.EpisodeCode
import com.dewijones92.totum.domain.PlayableItem

public suspend fun JellyfinApi.episodesAfter(seriesName: String, after: EpisodeCode): List<PlayableItem>? {
    val series = findSeries(seriesName)
    if (series == null) {
        Diag.log("jellyfin", "next after S${after.season}E${after.episode}: no series named \"$seriesName\" was found")
        return null
    }
    val seasons = seasonsOf(series)
    for (season in seasons) {
        val later = children(season.id)
            .filter { it.kind == JellyfinKind.EPISODE && it.isAfter(after) }
            .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
        if (later.isNotEmpty()) {
            Diag.log(
                "jellyfin",
                "next after S${after.season}E${after.episode} of \"$seriesName\": ${later.size} in \"${season.name}\""
            )
            return later.map { playable(it) }
        }
    }
    Diag.log(
        "jellyfin",
        "next after S${after.season}E${after.episode} of \"$seriesName\": none in ${seasons.size} season(s)"
    )
    return emptyList()
}

private fun JellyfinItem.isAfter(code: EpisodeCode): Boolean {
    val season = seasonNumber ?: return false
    val episode = episodeNumber ?: return false
    return season > code.season || (season == code.season && episode > code.episode)
}

internal suspend fun JellyfinApi.findSeries(name: String): JellyfinItem? {
    val isIt = { item: JellyfinItem -> item.kind == JellyfinKind.SERIES && item.name == name }
    val searched = (search(name) as? HomeAnswer.Ok)?.value?.takeIf { it.search != null }
    searched?.items?.firstOrNull(isIt)?.let {
        Diag.log("jellyfin", "series \"$name\" found by one search")
        return it
    }
    return find(name, isIt)
}

internal suspend fun JellyfinApi.seasonsOf(series: JellyfinItem): List<JellyfinItem> =
    children(series.id).filter { it.kind == JellyfinKind.SEASON }

internal suspend fun JellyfinApi.episodesOf(season: JellyfinItem): List<JellyfinItem> =
    children(season.id).filter { it.kind == JellyfinKind.EPISODE }
        .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))

private suspend fun JellyfinApi.find(name: String, matches: (JellyfinItem) -> Boolean): JellyfinItem? {
    var start = 0
    while (true) {
        val page = (browse(null, start) as? HomeAnswer.Ok)?.value ?: return null.also {
            Diag.warn("jellyfin", "looking for \"$name\": the top level could not be read from $start")
        }
        page.items.firstOrNull(matches)?.let { return it }
        start = page.next?.takeIf { it > start } ?: return null
    }
}

private suspend fun JellyfinApi.children(parent: String): List<JellyfinItem> {
    val all = mutableListOf<JellyfinItem>()
    var start = 0
    while (true) {
        val page = (browse(parent, start) as? HomeAnswer.Ok)?.value ?: return all.also {
            Diag.warn("jellyfin", "children of $parent could not be read from $start; using the ${all.size} read")
        }
        all += page.items
        start = page.next?.takeIf { it > start } ?: return all
    }
}
