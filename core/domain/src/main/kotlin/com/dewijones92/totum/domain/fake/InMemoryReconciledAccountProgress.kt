package com.dewijones92.totum.domain.fake

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.ReconciledAccountProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [ReconciledAccountProgress] for tests and previews; latest figure per item, like the real one. */
public class InMemoryReconciledAccountProgress : ReconciledAccountProgress {

    private val rows = MutableStateFlow<Map<MediaItemId, Long>>(emptyMap())

    override suspend fun reconciledMs(itemId: MediaItemId): Long? = rows.value[itemId]

    override suspend fun reconcile(itemId: MediaItemId, remoteMs: Long) {
        rows.value = rows.value + (itemId to remoteMs)
    }

    override suspend fun forgetAll() {
        rows.value = emptyMap()
    }

    override fun observeReconciled(): Flow<Map<MediaItemId, Long>> = rows
}
