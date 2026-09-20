package com.dewijones92.totum.database

import com.dewijones92.totum.domain.AccountProgressOutbox
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PendingAccountProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room-backed [AccountProgressOutbox]; the mapping is the only thing it knows. */
public class RoomAccountProgressOutbox(private val dao: AccountProgressOutboxDao) : AccountProgressOutbox {

    /**
     * The attempt count belongs to the ITEM, not to the position, so a newer position carries the
     * old count forward. Playback rewrites the row every fifteen seconds, and resetting it there
     * meant the video being watched — the one generating all the traffic — could never reach the
     * give-up threshold and was re-asked on every drain for as long as it played. Thirty-seven
     * times in one session, in report 0.1.496.
     */
    override suspend fun record(progress: PendingAccountProgress) {
        val held = dao.get(progress.itemId.value)
        dao.upsert(
            AccountProgressOutboxEntity(
                mediaItemId = progress.itemId.value,
                positionMs = progress.positionMs,
                durationMs = progress.durationMs,
                finished = progress.finished,
                recordedAtEpochMs = progress.recordedAtEpochMs,
                attempts = maxOf(progress.attempts, held?.attempts ?: 0),
            ),
        )
    }

    override suspend fun pending(): List<PendingAccountProgress> =
        dao.pending().map(AccountProgressOutboxEntity::toPending)

    override suspend fun sent(itemId: MediaItemId, recordedAtEpochMs: Long): Unit =
        dao.deleteIfUnchanged(itemId.value, recordedAtEpochMs)

    override suspend fun attempted(itemId: MediaItemId, recordedAtEpochMs: Long): Unit =
        dao.countAttemptIfUnchanged(itemId.value, recordedAtEpochMs)

    override fun observePendingCount(): Flow<Int> = dao.observeCount()

    override fun observeStuck(after: Int, worst: Int): Flow<List<PendingAccountProgress>> =
        dao.observeStuck(after, worst).map { rows -> rows.map(AccountProgressOutboxEntity::toPending) }
}

private fun AccountProgressOutboxEntity.toPending() = PendingAccountProgress(
    MediaItemId(mediaItemId),
    positionMs,
    durationMs,
    finished,
    recordedAtEpochMs,
    attempts,
)
