package com.dewijones92.totum.database

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.playback.Chosen
import com.dewijones92.totum.playback.PlaybackProgressStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Room-backed [PlaybackProgressStore]. Ignores trivially small positions (so a
 * quick tap doesn't create a resume point) unless they move an existing position
 * BACK, which is a rewind, or were [Chosen]; and treats a position near the end as
 * finished, unless it is one being reinstated rather than reached.
 *
 * A finished item keeps its row, marked completed, rather than being deleted: that
 * row is what lets a list say "played" instead of inferring it from an absence. It
 * still restarts from the beginning, because [resumePositionMs] reports no position
 * for a completed item — restarting is a playback rule, not a storage side effect.
 */
public class RoomPlaybackProgressStore(
    private val dao: PlaybackProgressDao,
    private val now: () -> Long = System::currentTimeMillis,
) : PlaybackProgressStore {

    override suspend fun resumePositionMs(itemId: MediaItemId): Long? =
        (playState(itemId) as? PlayState.InProgress)?.positionMs

    override suspend fun playState(itemId: MediaItemId): PlayState =
        dao.get(itemId.value)?.playState() ?: PlayState.Unplayed

    /**
     * Saving is a read-modify-write — it compares against what is held to spot a rewind, and reads
     * the row to keep its completion — so two overlapping saves could otherwise decide on stale
     * state and let the older position win. The guard lives here rather than in a caller because
     * there are six of them (the position ticker, a pause, a seek, adopting the account's figure,
     * restoring a backup, and marking played or unplayed) and the invariant belongs to the thing
     * it protects.
     *
     * A `Mutex` is NOT reentrant: nothing in here may call another locked method, and today
     * nothing does.
     */
    private val writes = Mutex()

    override suspend fun save(
        itemId: MediaItemId,
        positionMs: Long,
        durationMs: Long?,
        chosen: Chosen,
    ): Unit = writes.withLock {
        val finished = durationMs != null && positionMs >= durationMs - tailFor(durationMs)
        when {
            // FIRST, ahead of the near-the-end rule: a REINSTATED position says where the item
            // is, never whether it is finished. Inferring completion from it marked an item
            // played the moment the account's 99% was adopted, and clearing it un-played
            // everything the account was merely behind on — which is everything YouTube has not
            // heard about since the outbound half stopped working.
            chosen == Chosen.AS_A_RECORD -> reinstate(itemId, positionMs, durationMs)
            finished -> markPlayed(itemId, durationMs)
            // A seek is the person watching from here, so it reopens a finished item exactly as
            // the next ordinary save would — it just does not have to clear the floor first.
            chosen == Chosen.BY_SEEKING ->
                dao.upsert(PlaybackProgressEntity(itemId.value, positionMs, durationMs, now()))
            // Too early to be worth resuming. Deliberately does not clear an existing
            // state: replaying a played item would otherwise mark it unplayed at once.
            positionMs < MIN_SAVE_MS && !movesAnExistingPositionBack(itemId, positionMs) ->
                // Said out loud, because a dropped save is the fault that made a rewind
                // un-rewindable and it was completely silent for two months. The next report
                // that carries a surprising resume can now show what was thrown away.
                Diag.log("progress", "not saving ${itemId.value} at ${positionMs}ms — under the ${MIN_SAVE_MS}ms floor")
            else -> dao.upsert(PlaybackProgressEntity(itemId.value, positionMs, durationMs, now()))
        }
    }

    /** A reinstated position, keeping the row's completion and duration exactly as they were. */
    private suspend fun reinstate(itemId: MediaItemId, positionMs: Long, durationMs: Long?) {
        val held = dao.get(itemId.value)
        dao.upsert(
            PlaybackProgressEntity(
                mediaItemId = itemId.value,
                positionMs = positionMs,
                durationMs = durationMs ?: held?.durationMs,
                updatedAtEpochMs = now(),
                completedAtEpochMs = held?.completedAtEpochMs,
            ),
        )
    }

    /**
     * Whether a position under the floor is a REWIND rather than a first few seconds.
     *
     * The floor exists so a quick tap does not create a resume point, and it silently dropped every
     * save that would have moved one back to the start instead — so an item's stored position could
     * never go below five seconds once it had one. Report 0.1.496: Dewi rewound the same video six
     * times, paused at 3790ms, 2595ms and 1144ms, and all three were discarded, leaving `local=11273`
     * from a pause ninety seconds earlier for `resumeFrom` to be judged on.
     *
     * A completed item is excluded on purpose: replaying one and stopping after three seconds must
     * not mark it unplayed, which is what the floor was protecting in the first place.
     */
    private suspend fun movesAnExistingPositionBack(itemId: MediaItemId, positionMs: Long): Boolean {
        val held = dao.get(itemId.value) ?: return false
        return held.completedAtEpochMs == null && positionMs < held.positionMs
    }

    override fun observeStates(): Flow<Map<MediaItemId, PlayState>> =
        dao.observeAll().map { rows -> rows.associate { MediaItemId(it.mediaItemId) to it.playState() } }

    override suspend fun setPlayed(itemId: MediaItemId, played: Boolean): Unit = writes.withLock {
        // Read-then-write like [save], and it races the position ticker without the guard: marking
        // something unplayed mid-playback used to have the row reappear a few seconds later.
        if (played) markPlayed(itemId, dao.get(itemId.value)?.durationMs) else dao.delete(itemId.value)
    }

    private suspend fun markPlayed(itemId: MediaItemId, durationMs: Long?) {
        val at = now()
        dao.upsert(
            PlaybackProgressEntity(
                mediaItemId = itemId.value,
                positionMs = durationMs ?: 0,
                durationMs = durationMs,
                updatedAtEpochMs = at,
                completedAtEpochMs = at,
            ),
        )
    }

    /**
     * How close to the end still counts as finished. A flat 15s is right for anything of
     * normal length but absurd for a Short: it marked a 30-second video played at the
     * halfway point, and a 60-second one at 75%. The tail is capped at a share of the
     * item instead, so "nearly finished" means the same thing at any duration.
     */
    private fun tailFor(durationMs: Long): Long =
        minOf(NEAR_END_MS, (durationMs * TAIL_FRACTION_PERCENT) / PERCENT)

    private fun PlaybackProgressEntity.playState(): PlayState =
        if (completedAtEpochMs != null) PlayState.Played else PlayState.InProgress(positionMs, durationMs)

    private companion object {
        const val MIN_SAVE_MS = 5_000L
        const val NEAR_END_MS = 15_000L
        const val TAIL_FRACTION_PERCENT = 10L
        const val PERCENT = 100L
    }
}
