package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.flow.first

public suspend fun DownloadManager.clearFailed(why: String): List<MediaItemId> {
    val failed = observeRecords().first().filter { it.state is DownloadState.Failed }.map { it.item.item.id }
    Diag.log("download", "clear failed ($why): ${failed.size} failed download(s) forgotten")
    failed.forEach { delete(it) }
    return failed
}
