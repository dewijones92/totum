package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId

internal class HomeMoves(private val moving: KeptAtHome, private val deleteOnPhone: (MediaItemId) -> Unit) {

    fun refused(item: MediaItem) {
        if (item.id.value !in moving.ids.value) return
        moving.release(item.id.value)
        Diag.warn(TAG, "\"${item.title}\": not moved, the phone copy stays")
    }

    fun finish(now: Map<MediaItemId, HomeJob>) {
        moving.ids.value.forEach { raw ->
            val id = MediaItemId(raw)
            val job = now[id]
            when (job?.state) {
                HomeJobState.DONE -> {
                    moving.release(raw)
                    Diag.log(TAG, "\"${job.item.title}\": home has it; deleting the phone's copy, as asked")
                    deleteOnPhone(id)
                }
                HomeJobState.FAILED -> {
                    moving.release(raw)
                    Diag.warn(TAG, "$raw: the move home failed; the phone copy stays")
                }
                else -> Unit
            }
        }
    }
}
