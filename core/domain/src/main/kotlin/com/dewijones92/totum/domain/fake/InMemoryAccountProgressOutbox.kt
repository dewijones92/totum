package com.dewijones92.totum.domain.fake

import com.dewijones92.totum.domain.AccountProgressOutbox
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PendingAccountProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [AccountProgressOutbox] for tests and previews; same latest-per-item rule as the real one. */
public class InMemoryAccountProgressOutbox : AccountProgressOutbox {

    private val rows = MutableStateFlow<Map<MediaItemId, PendingAccountProgress>>(emptyMap())

    override suspend fun record(progress: PendingAccountProgress) {
        rows.value = rows.value + (progress.itemId to progress)
    }

    override suspend fun pending(): List<PendingAccountProgress> = rows.value.values.sortedBy { it.recordedAtEpochMs }

    override suspend fun sent(itemId: MediaItemId, recordedAtEpochMs: Long) {
        if (rows.value[itemId]?.recordedAtEpochMs == recordedAtEpochMs) rows.value = rows.value - itemId
    }

    override fun observePendingCount(): Flow<Int> = rows.map { it.size }
}
