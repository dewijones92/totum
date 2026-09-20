package com.dewijones92.totum.domain

import kotlinx.coroutines.flow.Flow

/**
 * Progress the ACCOUNT has not been told about yet — one row per item, the latest position.
 *
 * Reporting used to be fire-and-forget: a ping went out as playback advanced and the result was
 * dropped whatever it was. So anything listened to with no network — the whole point of the
 * offline queue — was never reported, and when YouTube stopped serving this app a session at all
 * (2026-08-18) three weeks of listening vanished with no trace that it had ever been tried.
 *
 * The outbox makes the report durable and the send separate: playback records what happened
 * here, unconditionally; a drain sends whatever is pending whenever a sender works, and keeps
 * what it could not send. Nothing is lost for lack of a network, a sign-in, or a working route.
 *
 * Latest-per-item on purpose: an account holds one position per video, so an older report for the
 * same item is superseded, never queued behind the newer one.
 */
public interface AccountProgressOutbox {

    /** Records [progress], replacing any earlier record for the same item. */
    public suspend fun record(progress: PendingAccountProgress)

    /**
     * Everything not yet sent: fewest failed attempts first, then oldest.
     *
     * Not simply oldest-first, because that is what let three unsendable rows at the head starve
     * everything behind them. A row that keeps failing sinks, so every other row gets a turn and
     * the failing one is still retried — for ever, and for free once it starts working again.
     */
    public suspend fun pending(): List<PendingAccountProgress>

    /**
     * Forgets [itemId]'s record — but only if it is still the one recorded at [recordedAtEpochMs].
     * A newer record written while the send was in flight must survive it.
     */
    public suspend fun sent(itemId: MediaItemId, recordedAtEpochMs: Long)

    /**
     * Counts a failed attempt for [itemId]'s record. Same staleness guard as [sent].
     *
     * Nothing is ever DROPPED for failing — the count only sinks a troublesome row down the
     * order, so it can never crowd out the rows behind it. Report 0.1.496 had three videos
     * YouTube would give no tracking session for holding 120 sendable updates behind them, and
     * the obvious fix — write a row off after a few tries — was measured and abandoned: probing
     * those same three ids a fortnight later, **all three answered with tracking**. Their refusal
     * had been temporary, so giving up would have destroyed real listening to solve a queueing
     * problem that ordering solves for nothing.
     */
    public suspend fun attempted(itemId: MediaItemId, recordedAtEpochMs: Long)

    /** How many records are waiting, live — what a diagnostics report and a settings row show. */
    public fun observePendingCount(): Flow<Int>

    /**
     * The rows that have failed at least [after] times, worst first, capped at [worst].
     *
     *
     * Since nothing is ever dropped, "is anything permanently stuck, and which?" is the one
     * question the design's own risk raises, and a bare count cannot answer it. Report 0.1.496
     * needed a code read to work out that three ids were behind everything; this is that answer
     * in the report itself.
     */
    public fun observeStuck(after: Int, worst: Int): Flow<List<PendingAccountProgress>>
}

/** One item's latest unreported progress. Durations in milliseconds; [finished] means watched to the end. */
public data class PendingAccountProgress(
    val itemId: MediaItemId,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    val recordedAtEpochMs: Long,
    /** Failed sends so far. Sinks the row down the order; never a reason to drop it. */
    val attempts: Int = 0,
) {
    init {
        require(positionMs >= 0) { "positionMs must not be negative" }
        require(durationMs > 0) { "durationMs must be known and positive to report a fraction watched" }
    }
}
