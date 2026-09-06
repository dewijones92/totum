package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.AccountProgressOutbox
import com.dewijones92.totum.domain.PendingAccountProgress
import com.dewijones92.totum.innertube.history.WatchHistoryResult
import com.dewijones92.totum.innertube.history.YouTubeWatchHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

/** Whether progress is currently reaching the account — the one fact a report needs about outbound sync. */
internal sealed interface OutboundSyncStatus {
    /** No send attempted yet this process. */
    data object Unknown : OutboundSyncStatus

    /** The last send worked; [sentThisProcess] is how many updates have reached the account. */
    data class Working(val sentThisProcess: Int) : OutboundSyncStatus

    /** The last send could not reach the account; [held] updates are waiting for it to work again. */
    data class Unavailable(val reason: String, val held: Int) : OutboundSyncStatus
}

/**
 * Sends whatever the [AccountProgressOutbox] holds, and keeps what it cannot send.
 *
 * The send used to happen inline with playback and its result was dropped, so a ping that could not
 * go — no network, no sign-in, or YouTube refusing this app a session, which is where things have
 * stood since 2026-08-18 — was simply lost. Now playback only records; this is the only thing that
 * talks to the account, and it can be kicked from anywhere a send might newly succeed: a new record,
 * app start, the network coming back.
 *
 * One drain at a time. A kick during a drain is not lost — it runs another pass when this one ends —
 * because the kick that arrives mid-drain is typically the one carrying the newest position.
 */
internal class ProgressOutboxDrain(
    private val outbox: AccountProgressOutbox,
    private val history: YouTubeWatchHistory,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow<OutboundSyncStatus>(OutboundSyncStatus.Unknown)
    val status: StateFlow<OutboundSyncStatus> = _status

    private val running = Mutex()
    private val kickedWhileRunning = AtomicBoolean(false)
    private var sentThisProcess = 0

    /**
     * Items whose tracking session has been opened this process. Opened before an item's FIRST
     * report and forgotten again on a `NoSession`, so a session that could not be acquired (offline,
     * or refused) is asked for again on the next drain rather than once per process — which is
     * exactly how the old inline sync stayed `NoSession` for a whole video after one failed start.
     */
    private val sessionsBegun = mutableSetOf<String>()

    fun kick() {
        scope.launch { drain() }
    }

    suspend fun drain() {
        if (!running.tryLock()) {
            kickedWhileRunning.set(true)
            return
        }
        try {
            do {
                kickedWhileRunning.set(false)
                pass()
            } while (kickedWhileRunning.get())
        } finally {
            running.unlock()
        }
    }

    private suspend fun pass() {
        val pending = outbox.pending()
        if (pending.isEmpty()) return
        var sent = 0
        var failures = 0
        var stop: String? = null
        for (row in pending) {
            // Each failure costs a round trip and the cause is almost always shared, so a few is
            // enough to learn the sender is down without asking on behalf of every held item. A
            // sign-out is account-wide and needs no second opinion at all.
            if (stop == "signed out" || failures >= MAX_FAILURES_PER_PASS) break
            when (val result = send(row)) {
                WatchHistoryResult.Success -> {
                    outbox.sent(row.itemId, row.recordedAtEpochMs)
                    sent++
                }
                WatchHistoryResult.SignedOut -> stop = "signed out"
                WatchHistoryResult.NoSession -> {
                    sessionsBegun -= row.itemId.value
                    failures++
                    stop = "YouTube gave this app no tracking session (the signed-in TV /player is refused)"
                }
                is WatchHistoryResult.Failure -> {
                    failures++
                    stop = result.detail
                }
            }
        }
        sentThisProcess += sent
        val held = outbox.pending().size
        val status = if (held == 0 || stop == null) {
            OutboundSyncStatus.Working(sentThisProcess)
        } else {
            OutboundSyncStatus.Unavailable(stop, held)
        }
        report(sent, held, status)
    }

    private suspend fun send(row: PendingAccountProgress): WatchHistoryResult {
        val id = row.itemId.value
        if (sessionsBegun.add(id)) history.beginSession(id)
        return history.reportProgress(
            videoId = id,
            positionSec = row.positionMs / MILLIS_PER_SEC,
            lengthSec = row.durationMs / MILLIS_PER_SEC,
            finished = row.finished,
        )
    }

    /** Successful passes since the last line — counted, not logged, until [LOG_EVERY]. */
    private var routinePasses = 0

    /**
     * Says what changed, and counts what did not.
     *
     * Logging every successful ping was once **31% of a whole diagnostics report** — 125 of 400
     * entries all saying "Success" again, i.e. 125 entries of something else evicted. A run of
     * identical outcomes says nothing after the first. What does: the sender starting to work, the
     * sender stopping (with why, and how many are held), and periodically that the run is still
     * going, so a silent stop stays distinguishable from everything being fine.
     */
    private fun report(sent: Int, held: Int, status: OutboundSyncStatus) {
        val before = _status.value
        val changed = status::class != before::class ||
            (status as? OutboundSyncStatus.Unavailable)?.reason != (before as? OutboundSyncStatus.Unavailable)?.reason
        _status.value = status
        when {
            changed && status is OutboundSyncStatus.Unavailable -> {
                routinePasses = 0
                Diag.warn("yt-sync", "outbound sync unavailable — ${status.reason}; holding $held update(s) for later")
            }
            changed && status is OutboundSyncStatus.Working -> {
                routinePasses = 0
                Diag.log("yt-sync", "outbound sync working — sent $sent, $held held")
            }
            sent > 0 -> {
                routinePasses++
                if (routinePasses % LOG_EVERY == 0) {
                    Diag.log(
                        "yt-sync",
                        "still sending — $routinePasses pass(es) reached the account since the last line, $held held"
                    )
                }
            }
            else -> Unit
        }
    }

    private companion object {
        const val MILLIS_PER_SEC = 1000f
        const val MAX_FAILURES_PER_PASS = 3

        /** One line per two minutes of unchanged syncing, against one per fifteen seconds. */
        const val LOG_EVERY = 8
    }
}
