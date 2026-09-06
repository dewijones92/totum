package com.dewijones92.totum.database

import com.dewijones92.totum.domain.AccountProgressOutbox
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PendingAccountProgress
import kotlinx.coroutines.flow.Flow

/** Room-backed [AccountProgressOutbox]; the mapping is the only thing it knows. */
public class RoomAccountProgressOutbox(private val dao: AccountProgressOutboxDao) : AccountProgressOutbox {

    override suspend fun record(progress: PendingAccountProgress) {
        dao.upsert(
            AccountProgressOutboxEntity(
                mediaItemId = progress.itemId.value,
                positionMs = progress.positionMs,
                durationMs = progress.durationMs,
                finished = progress.finished,
                recordedAtEpochMs = progress.recordedAtEpochMs,
            ),
        )
    }

    override suspend fun pending(): List<PendingAccountProgress> = dao.pending().map {
        PendingAccountProgress(
            MediaItemId(it.mediaItemId),
            it.positionMs,
            it.durationMs,
            it.finished,
            it.recordedAtEpochMs
        )
    }

    override suspend fun sent(itemId: MediaItemId, recordedAtEpochMs: Long): Unit =
        dao.deleteIfUnchanged(itemId.value, recordedAtEpochMs)

    override fun observePendingCount(): Flow<Int> = dao.observeCount()
}
