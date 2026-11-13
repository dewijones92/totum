package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import java.io.File

internal class PhoneMoves(
    private val moving: KeptAtHome,
    private val deleteAtHome: (MediaItemId) -> Unit,
    private val sizeOf: (String) -> Long? = { path -> File(path).takeIf { it.isFile }?.length() },
) {
    private val begunNow = mutableSetOf<String>()

    fun begin(id: MediaItemId) {
        moving.keep(id.value)
        begunNow += id.value
    }

    fun settle(jobs: Map<MediaItemId, HomeJob>, local: Map<MediaItemId, DownloadState>) {
        moving.ids.value.forEach { raw -> settle(MediaItemId(raw), jobs[MediaItemId(raw)], local[MediaItemId(raw)]) }
    }

    private fun settle(id: MediaItemId, job: HomeJob?, onPhone: DownloadState?) {
        if (job == null) {
            moving.release(id.value)
            Diag.warn(TAG, "${id.value}: move to the phone dropped, home no longer has the file")
            return
        }
        if (job.state != HomeJobState.DONE) {
            moving.release(id.value)
            Diag.warn(TAG, "${id.value}: move to the phone dropped, the file at home is ${job.state.name.lowercase()}")
            return
        }
        when (onPhone) {
            is DownloadState.Downloaded -> verified(id, job, onPhone)
            is DownloadState.Failed -> {
                moving.release(id.value)
                Diag.warn(
                    TAG,
                    "\"${job.item.title}\": the copy to the phone failed (${onPhone.reason}); home keeps its file",
                )
            }
            is DownloadState.Downloading -> Unit
            DownloadState.NotDownloaded, null -> if (id.value !in begunNow) {
                moving.release(id.value)
                Diag.warn(TAG, "${id.value}: move to the phone dropped, it was left from an earlier run; home keeps it")
            }
        }
    }

    private fun verified(id: MediaItemId, job: HomeJob, copy: DownloadState.Downloaded) {
        if (copy.audioOnly != job.audioOnly) {
            Diag.log(
                TAG,
                "${id.value}: the phone holds a ${kind(copy.audioOnly)} copy, home's is ${kind(job.audioOnly)}; " +
                    "waiting for the matching copy before deleting at home",
            )
            if (id.value !in begunNow) moving.release(id.value)
            return
        }
        val expected = job.totalBytes ?: job.bytes
        val actual = sizeOf(copy.localPath)
        moving.release(id.value)
        if (actual == null || actual != expected) {
            Diag.warn(
                TAG,
                "\"${job.item.title}\": the phone's copy is ${actual ?: "missing"}B against ${expected}B at home; " +
                    "home keeps its file",
            )
            return
        }
        Diag.log(TAG, "\"${job.item.title}\": the phone has all ${actual}B; deleting the copy at home, as asked")
        deleteAtHome(id)
    }

    private fun kind(audioOnly: Boolean) = if (audioOnly) "sound" else "video"
}
