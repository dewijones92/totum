package com.dewijones92.totum.data.download

import com.dewijones92.totum.domain.MediaItemId

/**
 * What [DownloadManager.recoverStranded] found and did: downloads recorded as running that had no
 * coroutine behind them.
 */
public data class StrandedDownloads(
    val restarted: List<MediaItemId> = emptyList(),
    val failed: List<MediaItemId> = emptyList(),
    val dropped: List<MediaItemId> = emptyList(),
) {
    val count: Int get() = restarted.size + failed.size + dropped.size

    public companion object {
        public val NONE: StrandedDownloads = StrandedDownloads()
    }
}
