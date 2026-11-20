package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.home.JellyfinSeriesRun
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.domain.ChosenWay
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.PlayReason
import com.dewijones92.totum.domain.jellyfinEpisode
import kotlinx.coroutines.withTimeoutOrNull

internal class SeriesRuns(
    private val read: suspend (MediaItem) -> JellyfinSeriesRun?,
    private val offline: () -> Boolean,
) {

    suspend fun forTap(
        item: MediaItem,
        group: QueueGroup?,
        queuedIn: QueueGroup?,
        byYou: Boolean,
        reason: PlayReason?,
    ): JellyfinSeriesRun? {
        val code = item.jellyfinEpisode ?: return null
        val skipped = when {
            !byYou -> "the app moved on by itself"
            group != null -> "it was handed a group (\"${group.title}\")"
            queuedIn != null -> "it is already queued in \"${queuedIn.title}\""
            reason is PlayReason.Chosen && reason.way == ChosenWay.BACK_OR_FORWARD -> "it is a back or forward step"
            offline() -> "there is no network to read the series with"
            else -> null
        }
        if (skipped != null) {
            Diag.log(
                "queue",
                "dewidebug series: ${item.id.value} (S${code.season}E${code.episode}) plays alone: $skipped"
            )
            return null
        }
        val run = withTimeoutOrNull(READ_TIMEOUT_MS) { read(item) }
        if (run == null) {
            Diag.log(
                "queue",
                "dewidebug series: ${item.id.value} (S${code.season}E${code.episode}) plays alone: " +
                    "its series was not read within ${READ_TIMEOUT_MS}ms or is unknown",
            )
        }
        return run
    }

    companion object {
        const val READ_TIMEOUT_MS = 6_000L
    }
}
