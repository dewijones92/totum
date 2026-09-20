package com.dewijones92.totum.database

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.ReconciledAccountProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room-backed [ReconciledAccountProgress]; the mapping is the only thing it knows. */
public class RoomReconciledAccountProgress(
    private val dao: ReconciledAccountProgressDao,
    private val now: () -> Long = System::currentTimeMillis,
) : ReconciledAccountProgress {

    override suspend fun reconciledMs(itemId: MediaItemId): Long? = dao.get(itemId.value)?.positionMs

    override suspend fun reconcile(itemId: MediaItemId, remoteMs: Long) {
        dao.upsert(ReconciledAccountProgressEntity(itemId.value, remoteMs, now()))
    }

    override suspend fun forgetAll(): Unit = dao.deleteAll()

    override fun observeReconciled(): Flow<Map<MediaItemId, Long>> =
        dao.observeAll().map { rows -> rows.associate { MediaItemId(it.mediaItemId) to it.positionMs } }
}
