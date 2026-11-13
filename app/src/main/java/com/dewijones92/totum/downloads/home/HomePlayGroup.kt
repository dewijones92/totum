package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.torrent.TorrentEpisodes
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.TorrentIds
import com.dewijones92.totum.domain.isTorrent

class HomePlay(val items: List<PlayableItem>, val group: QueueGroup, val startAt: MediaItemId)

internal fun homePlayOf(tapped: PlayableItem, jobs: Collection<HomeJob>, extras: Set<MediaItemId>): HomePlay? {
    if (!tapped.item.id.isTorrent) return null
    val hash = tapped.item.id.value.removePrefix(TorrentIds.PREFIX).substringBefore(':')
    val prefix = "${TorrentIds.PREFIX}$hash:"
    val siblings = jobs.filter { it.state == HomeJobState.DONE && it.id.startsWith(prefix) }
        .sortedWith(
            compareBy<HomeJob>(
                { MediaItemId(it.id) in extras },
                { TorrentEpisodes.episodeOf(it.item.title)?.season ?: Int.MAX_VALUE },
                { TorrentEpisodes.episodeOf(it.item.title)?.episode ?: Int.MAX_VALUE },
                { it.id.substringAfterLast(':').toIntOrNull() ?: Int.MAX_VALUE },
            ),
        )
        .mapNotNull { it.item.toPlayable() }
    if (siblings.size < 2 || siblings.none { it.item.id == tapped.item.id }) return null
    return HomePlay(siblings, QueueGroup(id = hash, title = tapped.item.author ?: hash), tapped.item.id)
}
