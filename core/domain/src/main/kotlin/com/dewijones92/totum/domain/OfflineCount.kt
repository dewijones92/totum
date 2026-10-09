package com.dewijones92.totum.domain

public data class OfflineCount(public val total: Int, public val downloaded: Int, public val inProgress: Int) {
    public val complete: Boolean get() = total > 0 && downloaded == total
}

public fun offlineCount(ids: List<MediaItemId>, states: Map<MediaItemId, DownloadState>): OfflineCount = OfflineCount(
    total = ids.size,
    downloaded = ids.count { states[it] is DownloadState.Downloaded },
    inProgress = ids.count { states[it] is DownloadState.Downloading },
)
