package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.AccountProgressOutbox
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PendingAccountProgress
import com.dewijones92.totum.domain.isYouTubeVideoId

internal interface PlayedByHand {
    suspend fun played(itemId: MediaItemId)

    suspend fun unplayed(itemId: MediaItemId)
}

internal object NoPlayedByHand : PlayedByHand {
    override suspend fun played(itemId: MediaItemId): Unit = Unit

    override suspend fun unplayed(itemId: MediaItemId): Unit = Unit
}

internal class PlayedByHandReporter(
    private val outbox: AccountProgressOutbox,
    private val drain: ProgressOutboxDrain,
    private val durationOf: suspend (MediaItemId) -> Long?,
    private val accountHolds: (MediaItemId) -> Boolean,
    private val held: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) : PlayedByHand {

    override suspend fun played(itemId: MediaItemId) {
        val length = lengthFor(itemId, "played") ?: return
        outbox.record(PendingAccountProgress(itemId, length, length, finished = true, recordedAtEpochMs = now()))
        Diag.log(
            "yt-sync-item",
            "${itemId.value} marked played by hand: a final at ${length}ms is queued for the account",
        )
        drain.kick()
    }

    override suspend fun unplayed(itemId: MediaItemId) {
        if (isYouTubeVideoId(itemId.value) && !accountHolds(itemId)) {
            Diag.log(
                "yt-sync-item",
                "${itemId.value} marked unplayed by hand: not told to the account, which holds no position for it",
            )
            return
        }
        val length = lengthFor(itemId, "unplayed") ?: return
        outbox.record(PendingAccountProgress(itemId, 0, length, finished = false, recordedAtEpochMs = now()))
        Diag.log(
            "yt-sync-item",
            "${itemId.value} marked unplayed by hand: a ping at 0ms is queued for the account " +
                "(YouTube's history shows 10% at the lowest, so it may still read as started)",
        )
        drain.kick()
    }

    private suspend fun lengthFor(itemId: MediaItemId, what: String): Long? {
        val id = itemId.value
        val why = when {
            !isYouTubeVideoId(id) -> return null
            held() -> "a test run on this device holds reports to the YouTube account"
            drain.refusalFor(id) != null -> "YouTube refuses reports for it for good"
            else -> null
        }
        if (why != null) {
            Diag.log("yt-sync-item", "$id marked $what by hand: not told to the account: $why")
            return null
        }
        val length = durationOf(itemId)?.takeIf { it > 0 }
        if (length == null) {
            Diag.log("yt-sync-item", "$id marked $what by hand: not told to the account: no length known for it")
        }
        return length
    }
}
