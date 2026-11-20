package com.dewijones92.totum.data.search

import com.dewijones92.totum.data.jellyfin.JellyfinItem
import com.dewijones92.totum.data.jellyfin.JellyfinKind
import com.dewijones92.totum.domain.EpisodeCode
import com.dewijones92.totum.domain.FuzzyMatch
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.episodeCodeOf
import java.util.Locale

internal object YoursMerge {

    fun merge(
        phone: List<PlayableItem>,
        home: List<PlayableItem>,
        jellyfin: List<Pair<JellyfinItem, PlayableItem>>,
    ): List<SearchHit.Yours> {
        val rows = LinkedHashMap<MediaItemId, Row>()
        phone.forEach { rows.getOrPut(it.item.id) { Row(it) }.places += YourPlace.PHONE }
        home.forEach { rows.getOrPut(it.item.id) { Row(it) }.places += YourPlace.HOME }
        jellyfin.forEach { (item, playable) -> absorbIntoJellyfin(rows, item, playable) }
        return rows.values
            .map { SearchHit.Yours(it.playable, it.places.toSortedSet().toSet()) }
            .sortedWith(
                compareBy<SearchHit.Yours> { it.places.min().ordinal }
                    .thenBy { it.title.lowercase(Locale.ROOT) }
                    .thenBy { it.playable.item.id.value },
            )
    }

    private class Row(var playable: PlayableItem, val places: MutableSet<YourPlace> = mutableSetOf())

    private fun absorbIntoJellyfin(rows: MutableMap<MediaItemId, Row>, item: JellyfinItem, playable: PlayableItem) {
        val same = rows[playable.item.id]
        if (same != null) {
            same.places += YourPlace.JELLYFIN
            return
        }
        val copies = rows.values.filter { it.playable.item.id != playable.item.id && sameWork(item, it.playable) }
        val phoneCopy = copies.firstOrNull { YourPlace.PHONE in it.places }
        when {
            phoneCopy != null -> {
                phoneCopy.places += YourPlace.JELLYFIN
                copies.filter { it !== phoneCopy }.forEach { phoneCopy.places += it.places }
                rows.values.removeAll(copies.filter { it !== phoneCopy }.toSet())
            }
            copies.isNotEmpty() -> {
                val row = Row(playable, mutableSetOf(YourPlace.JELLYFIN))
                copies.forEach { row.places += it.places }
                rows.values.removeAll(copies.toSet())
                rows[playable.item.id] = row
            }
            else -> rows[playable.item.id] = Row(playable, mutableSetOf(YourPlace.JELLYFIN))
        }
    }

    private fun sameWork(item: JellyfinItem, other: PlayableItem): Boolean {
        val title = other.item.title
        return when {
            item.kind == JellyfinKind.MOVIE && item.year != null ->
                FuzzyMatch.matches("${item.name} ${item.year}", listOf(title))
            item.kind == JellyfinKind.EPISODE && item.seriesName != null &&
                item.seasonNumber != null && item.episodeNumber != null ->
                episodeCodeOf(title) == EpisodeCode(item.seasonNumber ?: 0, item.episodeNumber ?: 0) &&
                    FuzzyMatch.matches(item.seriesName ?: "", listOf(title))
            else -> false
        }
    }
}
