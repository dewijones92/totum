package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.PlayableItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class QueueMirror(
    private val scope: CoroutineScope,
    private val onQueuedByUser: suspend (PlayableItem) -> Unit,
) {
    fun mirrorAll(items: List<PlayableItem>) {
        items.singleOrNull()?.let(::mirror)
            ?: Diag.log(
                "queue",
                "not mirroring ${items.size} bulk-queued items to the account: a bulk add would bury Watch Later",
            )
    }

    /**
     * Fires the mirror without letting it affect queueing.
     *
     * Its own coroutine and its own try/catch: the queue must change instantly and locally
     * whatever the network does, so a slow or failed Watch Later write can never delay a tap or
     * lose the queue entry that the user actually asked for.
     */
    fun mirror(item: PlayableItem) {
        scope.launch {
            runCatching { onQueuedByUser(item) }
                .onFailure { Diag.warn("queue", "could not mirror \"${item.item.title}\" to the account", it) }
        }
    }
}
