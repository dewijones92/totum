package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.AccountProgressOutbox
import com.dewijones92.totum.domain.AccountRepair
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PendingAccountProgress
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.ReconciledAccountProgress
import com.dewijones92.totum.domain.accountRepairFor
import com.dewijones92.totum.domain.isWatchedToTheEnd
import com.dewijones92.totum.domain.isYouTubeVideoId
import com.dewijones92.totum.innertube.feeds.AccountProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

internal class PlayedStateRepair(
    private val watched: Flow<Map<String, AccountProgress>>,
    private val states: () -> Map<MediaItemId, PlayState>,
    private val outbox: AccountProgressOutbox,
    private val drain: ProgressOutboxDrain,
    private val reconciled: ReconciledAccountProgress,
    private val scope: CoroutineScope,
    private val held: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var lastReadBack: String? = null
    private val skipped = HashMap<MediaItemId, String>()
    private var lastHeldNote: String? = null

    fun start() {
        scope.launch { watched.conflate().collect { account -> scan(account) } }
    }

    suspend fun scan(account: Map<String, AccountProgress>) {
        val local = states()
        val listed = account.filterKeys(::isYouTubeVideoId)
        val playedHere = listed.filterKeys { local[MediaItemId(it)] == PlayState.Played }
        val below = playedHere.filterValues { !isWatchedToTheEnd(it.positionMs, it.durationMs) }
        val candidates = listed.mapNotNull { (id, remote) ->
            val itemId = MediaItemId(id)
            val state = local[itemId]
            if (accountRepairFor(state, remote.positionMs, remote.durationMs, null) == null) return@mapNotNull null
            accountRepairFor(state, remote.positionMs, remote.durationMs, reconciled.reconciledMs(itemId))
                ?.let { itemId to it }
        }
        val queued = if (candidates.isEmpty()) emptyList() else repair(candidates)
        readBack(listed.size, playedHere.size, below, candidates.size, queued.size)
    }

    private suspend fun repair(candidates: List<Pair<MediaItemId, AccountRepair>>): List<MediaItemId> {
        if (held()) {
            val note = "not repairing ${candidates.size} item(s) on the account: " +
                "a test run on this device holds reports"
            if (note != lastHeldNote) Diag.log("yt-sync-item", note)
            lastHeldNote = note
            return emptyList()
        }
        val waiting = outbox.pending().map { it.itemId }.toSet()
        val queued = mutableListOf<MediaItemId>()
        for ((itemId, repair) in candidates) {
            val skip = when {
                itemId in waiting -> "an update for it is already waiting in the outbox"
                drain.refusalFor(itemId.value) != null -> "YouTube refuses reports for it for good"
                queued.size >= MAX_REPAIRS_PER_SCAN -> "$MAX_REPAIRS_PER_SCAN are already queued; the rest wait"
                else -> null
            }
            if (skip != null) {
                if (skipped.put(itemId, skip) != skip) {
                    Diag.log("yt-sync-item", "repair of ${itemId.value} not queued: $skip")
                }
                continue
            }
            skipped.remove(itemId)
            queue(itemId, repair)
            queued += itemId
        }
        if (queued.isNotEmpty()) drain.kick()
        return queued
    }

    private suspend fun queue(itemId: MediaItemId, repair: AccountRepair) {
        val finished = repair is AccountRepair.Finish
        val (positionMs, durationMs) = when (repair) {
            is AccountRepair.Finish -> repair.durationMs to repair.durationMs
            is AccountRepair.Position -> repair.positionMs to repair.durationMs
        }
        outbox.record(PendingAccountProgress(itemId, positionMs, durationMs, finished, now()))
        reconciled.reconcile(itemId, repair.actedOnMs)
        val what = if (finished) "finished here (Played)" else "ahead here (in progress)"
        val resent = if (finished) "final at ${durationMs}ms" else "${positionMs}ms"
        Diag.log(
            "yt-sync-item",
            "repair ${itemId.value}: $what but the account says ${repair.accountFigureMs}ms of ${durationMs}ms; " +
                "re-sending $resent through the outbox",
        )
    }

    private fun readBack(
        listed: Int,
        playedHere: Int,
        below: Map<String, AccountProgress>,
        repairable: Int,
        queued: Int,
    ) {
        val reading = below.entries.take(LISTED).joinToString { (id, at) -> "$id=${at.percent()}%" }
        val line = "readback: the account lists $listed video(s); Totum holds $playedHere of them Played, " +
            "${playedHere - below.size} read 100% and ${below.size} read less" +
            (if (below.isEmpty()) "" else " ($reading)") +
            "; $repairable repairable, $queued queued"
        if (line == lastReadBack) return
        lastReadBack = line
        Diag.log("yt-sync-item", line)
    }

    private fun AccountProgress.percent(): Long = if (durationMs > 0) positionMs * PERCENT / durationMs else 0

    private companion object {
        const val MAX_REPAIRS_PER_SCAN = 25
        const val LISTED = 8
        const val PERCENT = 100L
    }
}
