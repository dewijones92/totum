package com.dewijones92.totum.notforme

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.notforme.NotForMe
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.canonicalItemId
import com.dewijones92.totum.queue.PlaybackQueue

class NotForMeRejecter(
    private val queue: PlaybackQueue,
    private val notForMe: NotForMe,
    private val stopPlaying: () -> Unit,
) {
    data class Removed(val index: Int, val entry: QueueEntry)

    data class Rejection(val item: MediaItem, val removed: List<Removed>, val skippedAhead: Boolean)

    suspend fun reject(item: MediaItem, queuedIds: Set<MediaItemId>, from: String): Rejection {
        val playingRejected = queue.nowPlaying.value?.item?.id in queuedIds
        val removed = queue.state.value.entries
            .filter { it.item.item.id in queuedIds }
            .mapNotNull { entry -> queue.remove(entry)?.let { Removed(it, entry) } }
        notForMe.suppress(item, "$from; ${removed.size} left the queue")
        val skipped = playingRejected && queue.playNextInQueue()
        if (playingRejected && !skipped) stopPlaying()
        Diag.log(
            "notforme",
            "dewidebug rejected \"${item.title}\" (${item.id.value}): ${removed.size} removed from the queue; " +
                when {
                    !playingRejected -> "it was not what is playing, so playback carried on"
                    skipped -> "it was what is playing, so playback moved on to the next item"
                    else -> "it was what is playing and nothing follows it, so playback was stopped"
                },
        )
        return Rejection(item, removed, skippedAhead = skipped)
    }

    fun undo(rejection: Rejection) {
        notForMe.allow(canonicalItemId(rejection.item.id.value), "undo")
        rejection.removed.asReversed().forEach { queue.restoreAt(it.index, it.entry) }
        Diag.log(
            "notforme",
            "dewidebug undo for \"${rejection.item.title}\": ${rejection.removed.size} put back in the queue",
        )
    }
}
