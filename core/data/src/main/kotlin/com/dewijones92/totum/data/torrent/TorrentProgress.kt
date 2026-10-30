package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState

public object TorrentProgress {

    public fun hashOf(link: TorrentLink): String? = when (link) {
        is TorrentLink.Magnet -> BTIH.find(link.uri)?.groupValues?.get(1)?.lowercase()
        is TorrentLink.ViaIndexer -> null
    }

    public fun itemId(hash: String, index: Int): MediaItemId = MediaItemId("torrent:${hash.lowercase()}:$index")

    public fun indexOf(states: Map<MediaItemId, PlayState>): Map<String, Map<Int, PlayState>> {
        val byHash = mutableMapOf<String, MutableMap<Int, PlayState>>()
        states.forEach { (id, state) ->
            if (state is PlayState.Unplayed || !id.value.startsWith(PREFIX)) return@forEach
            val parts = id.value.split(':')
            val index = parts.getOrNull(2)?.toIntOrNull() ?: return@forEach
            byHash.getOrPut(parts[1].lowercase()) { mutableMapOf() }[index] = state
        }
        return byHash
    }

    public fun keysOf(seen: Map<String, SeenTorrent>): Map<String, String> =
        seen.mapNotNull { (hash, record) -> keyOf(record.name)?.let { hash to it } }.toMap()

    public fun chips(
        title: String,
        link: TorrentLink,
        states: Map<MediaItemId, PlayState>,
        seen: Map<String, SeenTorrent>,
    ): List<String> = chips(title, link, indexOf(states), seen, keysOf(seen))

    public fun chips(
        title: String,
        link: TorrentLink,
        played: Map<String, Map<Int, PlayState>>,
        seen: Map<String, SeenTorrent>,
        seenKeys: Map<String, String>,
    ): List<String> {
        val hash = hashOf(link)
        val own = hash?.let { played[it] }?.let { progressOf(it, seen[hash]) }.orEmpty()
        if (own.isNotEmpty()) return own
        val key = keyOf(title) ?: return emptyList()
        val other = seenKeys.entries.firstOrNull { (seenHash, seenKey) ->
            seenHash != hash && seenKey == key && seenHash in played
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

    private fun progressOf(mine: Map<Int, PlayState>, record: SeenTorrent?): List<String> {
        if (mine.isEmpty()) return emptyList()
        val playable = record?.files?.size
        val watched = mine.count { it.value is PlayState.Played }
        val started = mine.entries.filter { it.value is PlayState.InProgress }.minByOrNull { it.key }
        if (playable == 1) {
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
            started?.let { (index, state) ->
                val label = record?.files?.firstOrNull { it.index == index }?.label ?: "file $index"
                "▶️ $label: ${minutesIn(state as PlayState.InProgress)}"
            },
        )
    }

    private fun minutesIn(state: PlayState.InProgress): String {
        val minutes = (state.positionMs / MS_PER_MINUTE).takeIf { it > 0 }
        val of = state.durationMs?.takeIf { it > 0 }?.let { it / MS_PER_MINUTE }
        return of?.let { "${minutes ?: "under a minute"} of $it min" }
            ?: minutes?.let { "$it min in" }
            ?: "under a minute in"
    }

    internal fun keyOf(name: String): String? = ReleaseName(name).key

    private val BTIH = Regex("""btih:([0-9A-Fa-f]{40})""")
    private const val MS_PER_MINUTE = 60_000L
    private const val PREFIX = "torrent:"
}
