package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId

internal class HomeMoves(private val moving: KeptAtHome, private val deleteOnPhone: (MediaItemId) -> Unit) {

    private val seen = mutableSetOf<String>()
    private val begunNow = mutableSetOf<String>()

    fun begin(id: MediaItemId) {
        moving.keep(id.value)
        begunNow += id.value
    }

    fun copyOnly(id: MediaItemId) {
        if (id.value !in moving.ids.value) return
        moving.release(id.value)
        Diag.log(TAG, "${id.value}: asked to copy home, so the earlier move is dropped and the phone copy stays")
    }

    fun refused(item: MediaItem) {
        if (item.id.value !in moving.ids.value) return
        moving.release(item.id.value)
        Diag.warn(TAG, "\"${item.title}\": not moved, the phone copy stays")
    }

    fun finish(now: Map<MediaItemId, HomeJob>) {
        moving.ids.value.forEach { raw -> settle(raw, now[MediaItemId(raw)]) }
    }

    private fun settle(raw: String, job: HomeJob?) {
        if (job != null) seen += raw
        when (job?.state) {
            HomeJobState.DONE -> {
                moving.release(raw)
                Diag.log(TAG, "\"${job.item.title}\": home has it; deleting the phone's copy, as asked")
                deleteOnPhone(MediaItemId(raw))
            }
            HomeJobState.FAILED -> {
                moving.release(raw)
                Diag.warn(TAG, "$raw: the move home failed; the phone copy stays")
            }
            null -> dropIfGone(raw)
            else -> Unit
        }
    }

    private fun dropIfGone(raw: String) {
        if (raw !in seen && raw in begunNow) return
        moving.release(raw)
        val why = if (raw in seen) "its job went from home" else "it was left from an earlier run"
        Diag.warn(TAG, "$raw: move dropped, $why; the phone copy stays")
    }
}
