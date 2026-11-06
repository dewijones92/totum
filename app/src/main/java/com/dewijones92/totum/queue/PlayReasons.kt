package com.dewijones92.totum.queue

import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.domain.PlayReason
import com.dewijones92.totum.music.RADIO_GROUP_PREFIX
import com.dewijones92.totum.uploads.NewUploads

internal fun QueueEntry.arrivedNext(): PlayReason {
    val from = group
    return when {
        from == null -> PlayReason.Next(null)
        from.id == NewUploads.INBOX.id -> PlayReason.NewUpload
        from.id.startsWith(RADIO_GROUP_PREFIX) -> PlayReason.Radio(from.title)
        else -> PlayReason.Next(from.title)
    }
}

internal fun QueueEntry.wentBackTo(): PlayReason = PlayReason.Previous(group?.title)
