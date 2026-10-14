package com.dewijones92.totum.stats

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.flow.Flow
import java.io.File

suspend fun recordFinishedDownloads(downloads: Flow<Map<MediaItemId, DownloadState>>, log: StatsLog) {
    var seen: Set<MediaItemId>? = null
    downloads.collect { states ->
        val done = states.filterValues { it is DownloadState.Downloaded }
        val before = seen
        seen = done.keys
        if (before == null) return@collect
        (done.keys - before).forEach { id ->
            val path = (done.getValue(id) as DownloadState.Downloaded).localPath
            val bytes = File(path).length()
            Diag.log(StatsLog.TAG, "download finished: ${id.value} $bytes bytes")
            log.use(UseKind.DOWNLOAD, id.value, bytes)
        }
    }
}
