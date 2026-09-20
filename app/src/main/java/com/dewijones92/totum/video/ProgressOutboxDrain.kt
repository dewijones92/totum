package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.AccountProgressOutbox
import com.dewijones92.totum.domain.PendingAccountProgress
import com.dewijones92.totum.innertube.history.SessionResult
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
 * go — no network, no sign-in, or YouTube refusing this app a session — was simply lost. Now
 * playback only records; this is the only thing that talks to the account, and it can be kicked
 * from anywhere a send might newly succeed: a new record, app start, the network coming back.
 *
 * One drain at a time. A kick arriving mid-drain runs another pass when this one ends, because it
 * is typically the one carrying the newest position — up to [MAX_PASSES_PER_DRAIN] of them, after
 * which the work waits for the next kick rather than chaining passes back to back.
 *
 * **No row is ever dropped, and no row may starve another.** Report 0.1.496: three videos YouTube
 * would give no tracking for sat at the head of the queue, one video's verdict stopped the whole
 * pass, and the same three ids were asked thirty-seven times in one session while 120 sendable
 * updates behind them never got a turn — which is also why the account's figure for those videos
 * could never move, and so why rewinding them was impossible. Every failure now sinks its row and
 * the pass is bounded; the two rules together make that starvation impossible rather than unlikely.
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

    fun kick() {
        scope.launch { drain() }
    }

    suspend fun drain() {
        if (!running.tryLock()) {
            kickedWhileRunning.set(true)
            return
        }
        try {
            // The re-entry is bounded too, not only the pass. A kick arrives every fifteen
            // seconds of playback and a pass can take longer than that, so `while (kicked)` on
            // its own would chain passes back-to-back and undo the whole point of the row cap.
            var passesThisDrain = 0
            do {
                kickedWhileRunning.set(false)
                pass()
            } while (kickedWhileRunning.get() && ++passesThisDrain < MAX_PASSES_PER_DRAIN)
        } finally {
            running.unlock()
        }
    }

    private suspend fun pass() {
        val pending = outbox.pending()
        if (pending.isEmpty()) return
        val tally = Tally()
        // Bounded, because nothing else bounds it now that one video's verdict no longer stops
        // the pass. A queue of 123 would otherwise fire hundreds of requests back-to-back at an
        // unofficial endpoint from a phone, and the kick that arrives every fifteen seconds of
        // playback would do it again. The rest go on the next kick; the queue still drains.
        // Once per pass, NOT once per row: `filter { it.attempts < stubbornCutoff() }` would call
        // it for every row and advance the counter each time, so the cutoff would flip mid-filter
        // and the one-pass-in-ten would be a one-row-in-ten of an arbitrary ten.
        val cutoff = stubbornCutoff()
        val eligible = pending.filter { it.attempts < cutoff }.take(MAX_ROWS_PER_PASS)
        // A pass that attempts nothing has LEARNED nothing, and must not say anything about the
        // sender. Once every row is stubborn this is nine passes in ten, and reporting them would
        // put `outbound sync working — sent 0, 123 held` in the trail every few minutes while not
        // one update was reaching the account — the old misdiagnosis, inverted.
        if (eligible.isEmpty()) return
        for (row in eligible) {
            // A SENDER failure's cause is usually shared, so a few is enough to learn it is down
            // without a round trip on behalf of every held row. A sign-out needs no second
            // opinion at all. This budget is only safe to spend because every failure SINKS its
            // row — otherwise the same few rows would consume it at the head of every pass, which
            // is exactly what stranded 123 updates behind three of them in report 0.1.496.
            if (tally.stop()) break
            handle(row, tally)
        }
        sentThisProcess += tally.sent
        val held = pending.size - tally.sent
        report(tally.sent, held, tally.status(sentThisProcess, held))
    }

    /**
     * How many failures a row may have and still be tried this pass — every pass but one in
     * [RETRY_STUBBORN_EVERY].
     *
     * Nothing is ever dropped, so without this a permanently stuck backlog is retried in full for
     * the life of the install: 500 rows at twenty a pass, a pass every few seconds of playback,
     * for ever. Keeping the row and slowing it down gets the same guarantee for a tenth of the
     * traffic, and it still goes the moment YouTube changes its mind — which, measured, it does.
     */
    private fun stubbornCutoff(): Int =
        if (passes++ % RETRY_STUBBORN_EVERY == 0) Int.MAX_VALUE else STUBBORN_AFTER

    private var passes = 0

    private suspend fun handle(row: PendingAccountProgress, tally: Tally) {
        when (val result = send(row)) {
            WatchHistoryResult.Success -> {
                outbox.sent(row.itemId, row.recordedAtEpochMs)
                tally.sent++
            }
            WatchHistoryResult.SignedOut -> tally.signedOut = true
            // This ONE video, not the sender: YouTube answered about it and had no tracking to
            // give. Carry on down the queue — reading a single video's verdict as the sender's is
            // what left 123 updates stranded behind three of them, every pass, for ever.
            WatchHistoryResult.NotTrackable -> failed(row, tally, reason = null)
            WatchHistoryResult.NoSession, is WatchHistoryResult.Failure -> failed(
                row,
                tally,
                reason = (result as? WatchHistoryResult.Failure)?.detail
                    ?: "YouTube gave this app no tracking session",
            )
        }
    }

    /**
     * Records that this row did not go — and **sinks it**, whatever the reason.
     *
     * Sinking on every kind of failure, not only the ones judged to be the video's own fault, is
     * what makes the head-of-line block impossible rather than merely unlikely: whichever rows are
     * failing, the next pass tries the others first. A [reason] means the sender may be at fault
     * and spends one of the pass's few failures; a null one is about the video alone.
     *
     * The cost, accepted: a few drains with no signal push perfectly healthy rows past
     * [STUBBORN_AFTER], so they spend a while on the slow path for nothing. It self-heals within
     * [RETRY_STUBBORN_EVERY] passes, and the alternative — not sinking a sender failure — lets a
     * genuinely per-video `UNPLAYABLE` (private, members-only) spend the budget at the head of
     * every pass, which is the block itself.
     */
    private suspend fun failed(row: PendingAccountProgress, tally: Tally, reason: String?) {
        outbox.attempted(row.itemId, row.recordedAtEpochMs)
        if (reason == null) {
            tally.untrackable++
        } else {
            tally.senderFailures++
            tally.senderStop = reason
        }
    }

    /** What one pass has learned so far — kept together so the stop rule is stated in one place. */
    private class Tally {
        var sent = 0
        var senderFailures = 0
        var senderStop: String? = null
        var signedOut = false
        var untrackable = 0

        fun stop(): Boolean = signedOut || senderFailures >= MAX_FAILURES_PER_PASS

        /**
         * Unavailable whenever NOTHING got through. Anything sent proves the sender is up,
         * whatever individual videos said about themselves — and nothing sent while every video
         * refused is not working either, however healthy each refusal looked on its own.
         * Reporting `Working(0)` there would be the old fault inverted: a report saying all is
         * well while not one update reaches the account.
         */
        fun status(sentThisProcess: Int, held: Int): OutboundSyncStatus = when {
            signedOut -> OutboundSyncStatus.Unavailable("signed out", held)
            sent > 0 -> OutboundSyncStatus.Working(sentThisProcess)
            senderStop != null -> OutboundSyncStatus.Unavailable(senderStop.orEmpty(), held)
            untrackable > 0 ->
                OutboundSyncStatus.Unavailable("YouTube gives this app no tracking for any of them", held)
            else -> OutboundSyncStatus.Working(sentThisProcess)
        }
    }

    private suspend fun send(row: PendingAccountProgress): WatchHistoryResult {
        val id = row.itemId.value
        // Asked every time. `beginSession` answers `Opened` from its own cache when it already
        // holds a session, so a shadow "already begun" set here bought one function call and
        // cost correctness: it survived `forgetSessions()` on sign-out, and the next drain then
        // skipped `beginSession` for a session that no longer existed and reported the sender
        // down — the exact false diagnosis this whole change was paid to remove.
        when (val opened = history.beginSession(id)) {
            SessionResult.Opened -> Unit
            SessionResult.NotTrackable -> return WatchHistoryResult.NotTrackable
            is SessionResult.Unavailable -> return WatchHistoryResult.Failure(opened.reason)
        }
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

    companion object {
        private const val MILLIS_PER_SEC = 1000f
        const val MAX_FAILURES_PER_PASS = 3

        /**
         * Rows attempted in one pass. Sized so the burst stays in the tens of requests rather
         * than the hundreds; the remainder goes on the next kick, oldest-first as always.
         */
        const val MAX_ROWS_PER_PASS = 20

        /** Failures after which a row is only tried on the occasional pass — see [stubbornCutoff]. */
        const val STUBBORN_AFTER = 5

        /** How often a stubborn row gets another go regardless. */
        const val RETRY_STUBBORN_EVERY = 10

        /** Passes one drain may chain when kicks keep arriving; the rest wait for the next kick. */
        const val MAX_PASSES_PER_DRAIN = 3

        /** One line per two minutes of unchanged syncing, against one per fifteen seconds. */
        private const val LOG_EVERY = 8
    }
}
