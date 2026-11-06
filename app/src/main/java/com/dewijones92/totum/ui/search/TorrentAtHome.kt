package com.dewijones92.totum.ui.search

import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.TorrentIds
import com.dewijones92.totum.domain.percentOf

internal sealed interface TorrentAtHome {
    data class Downloading(val percent: Int?) : TorrentAtHome
    data class Ready(val copyable: List<MediaItemId>) : TorrentAtHome
    data object AlsoOnPhone : TorrentAtHome
}

internal fun torrentAtHome(
    hash: String?,
    jobs: Map<MediaItemId, HomeJob>,
    local: (MediaItemId) -> DownloadState,
): TorrentAtHome? {
    hash ?: return null
    val prefix = "${TorrentIds.PREFIX}${hash.lowercase()}:"
    val mine = jobs.filterKeys { it.value.startsWith(prefix) }.values
    if (mine.isEmpty()) return null
    val done = mine.filter { it.state == HomeJobState.DONE }
    val copyable = done.map { MediaItemId(it.id) }.filter { id ->
        val there = local(id)
        there !is DownloadState.Downloaded && there !is DownloadState.Downloading
    }
    val running = mine.firstOrNull { it.state == HomeJobState.DOWNLOADING || it.state == HomeJobState.WAITING }
    return when {
        copyable.isNotEmpty() -> TorrentAtHome.Ready(copyable)
        running != null -> TorrentAtHome.Downloading(percentOf(running.bytes, running.totalBytes))
        done.isNotEmpty() -> TorrentAtHome.AlsoOnPhone
        else -> null
    }
}
