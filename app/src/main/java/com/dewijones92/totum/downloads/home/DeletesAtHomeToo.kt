package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.flow.first

class DeletesAtHomeToo(
    private val inner: DownloadManager,
    private val home: () -> HomeDownloads,
) : DownloadManager by inner {

    override suspend fun delete(id: MediaItemId) {
        inner.delete(id)
        val kept = inner.observe(id).first() is DownloadState.Downloaded
        if (kept) {
            Diag.log("home", "${id.value} kept on the phone (locked?); its copy at home is kept too")
            return
        }
        if (home().jobs.value[id] != null) {
            Diag.log("home", "${id.value} deleted on the phone; deleting its copy at home too")
            home().cancel(id)
        }
    }
}
