package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState

public object TorrentProgress {

    public fun hashOf(link: TorrentLink): String? = when (link) {
        is TorrentLink.Magnet -> BTIH.find(link.uri)?.groupValues?.get(1)?.lowercase()
        is TorrentLink.ViaIndexer -> null
    }

    public fun itemId(hash: String, index: Int): MediaItemId = MediaItemId("torrent:${hash.lowercase()}:$index")

    public fun chips(
        title: String,
        link: TorrentLink,
        states: Map<MediaItemId, PlayState>,
        seen: Map<String, SeenTorrent>,
    ): List<String> {
        val hash = hashOf(link)
        val own = hash?.let { progressOf(it, states, seen[it]) }.orEmpty()
        if (own.isNotEmpty()) return own
        val key = keyOf(title) ?: return emptyList()
        val other = seen.values.firstOrNull {
            it.hash != hash && keyOf(it.name) == key && progressOf(it.hash, states, it).isNotEmpty()
        }
        return listOfNotNull(other?.let { "👀 You've played another copy of this" })
    }

    public fun resumeAt(items: List<MediaItemId>, states: Map<MediaItemId, PlayState>): MediaItemId? {
        if (items.isEmpty()) return null
        items.firstOrNull { states[it] is PlayState.InProgress }?.let { return it }
        val lastWatched = items.indexOfLast { states[it] is PlayState.Played }
        if (lastWatched < 0) return items.first()
        return items.drop(lastWatched + 1).firstOrNull { states[it] !is PlayState.Played }
            ?: items.firstOrNull { states[it] !is PlayState.Played }
            ?: items.first()
    }

    private fun progressOf(hash: String, states: Map<MediaItemId, PlayState>, record: SeenTorrent?): List<String> {
        val prefix = "torrent:$hash:"
        val mine = states.filterKeys { it.value.lowercase().startsWith(prefix) }
            .filterValues { it !is PlayState.Unplayed }
        if (mine.isEmpty()) return emptyList()
        val playable = record?.files?.size
        val watched = mine.count { it.value is PlayState.Played }
        val started = mine.entries.firstOrNull { it.value is PlayState.InProgress }
        if (playable == 1 || (playable == null && mine.size == 1)) {
            return listOf(
                when (val state = mine.values.first()) {
                    is PlayState.InProgress -> "▶️ ${minutesIn(state)}"
                    else -> "✅ Watched"
                },
            )
        }
        val whole = if (playable != null) "of $playable " else ""
        return listOfNotNull(
            "✅ $watched ${whole}watched".takeIf { watched > 0 },
            started?.let { (id, state) ->
                val index = id.value.substringAfterLast(':').toIntOrNull()
                val label = record?.files?.firstOrNull { it.index == index }?.label ?: "file ${index ?: "?"}"
                "▶️ $label: ${minutesIn(state as PlayState.InProgress)}"
            },
        )
    }

    private fun minutesIn(state: PlayState.InProgress): String {
        val at = state.positionMs / MS_PER_MINUTE
        return state.durationMs?.takeIf { it > 0 }?.let { "$at of ${it / MS_PER_MINUTE} min" } ?: "$at min in"
    }

    internal fun keyOf(name: String): String? {
        val words = name.lowercase().split(SEPARATORS).filter { it.isNotEmpty() }
        val stop = words.indexOfFirst { YEAR.matches(it) || EPISODE.matches(it) || it in QUALITY_WORDS }
        if (stop <= 0) return null
        val marker = words[stop].takeIf { YEAR.matches(it) || EPISODE.matches(it) }
        return (words.take(stop) + listOfNotNull(marker)).joinToString(" ")
    }

    private val BTIH = Regex("""btih:([0-9A-Fa-f]{40})""")
    private val SEPARATORS = Regex("""[\s.\-_\[\]()]+""")
    private val YEAR = Regex("""(19|20)\d\d""")
    private val EPISODE = Regex("""s\d{1,2}(e\d{1,3})?""")
    private val QUALITY_WORDS =
        setOf("2160p", "1080p", "720p", "480p", "4k", "bluray", "webrip", "web", "hdtv", "dvdrip")
    private const val MS_PER_MINUTE = 60_000L
}
