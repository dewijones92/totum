package com.dewijones92.totum.queue

import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem

fun interface QueueDestination {
    fun take(items: List<PlayableItem>, mode: QueueMode, group: QueueGroup?, startAt: MediaItemId?): Boolean
}
