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

    /** Everything not yet sent, oldest first. */
    public suspend fun pending(): List<PendingAccountProgress>

    /**
     * Forgets [itemId]'s record — but only if it is still the one recorded at [recordedAtEpochMs].
     * A newer record written while the send was in flight must survive it.
     */
    public suspend fun sent(itemId: MediaItemId, recordedAtEpochMs: Long)

    /** How many records are waiting, live — what a diagnostics report and a settings row show. */
    public fun observePendingCount(): Flow<Int>
}

/** One item's latest unreported progress. Durations in milliseconds; [finished] means watched to the end. */
public data class PendingAccountProgress(
    val itemId: MediaItemId,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    val recordedAtEpochMs: Long,
) {
    init {
        require(positionMs >= 0) { "positionMs must not be negative" }
        require(durationMs > 0) { "durationMs must be known and positive to report a fraction watched" }
    }
}
