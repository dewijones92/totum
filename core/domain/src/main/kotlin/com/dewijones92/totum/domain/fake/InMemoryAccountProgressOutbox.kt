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

    /** The attempt count belongs to the item, not the position — same rule as the Room store. */
    override suspend fun record(progress: PendingAccountProgress) {
        val held = rows.value[progress.itemId]
        rows.value = rows.value +
            (progress.itemId to progress.copy(attempts = maxOf(progress.attempts, held?.attempts ?: 0)))
    }

    override suspend fun pending(): List<PendingAccountProgress> =
        rows.value.values.sortedWith(compareBy({ it.attempts }, { it.recordedAtEpochMs }))

    override suspend fun sent(itemId: MediaItemId, recordedAtEpochMs: Long) {
        if (rows.value[itemId]?.recordedAtEpochMs == recordedAtEpochMs) rows.value = rows.value - itemId
    }

    override suspend fun attempted(itemId: MediaItemId, recordedAtEpochMs: Long) {
        val held = rows.value[itemId]?.takeIf { it.recordedAtEpochMs == recordedAtEpochMs } ?: return
        rows.value = rows.value + (itemId to held.copy(attempts = held.attempts + 1))
    }

    override fun observePendingCount(): Flow<Int> = rows.map { it.size }

    override fun observeStuck(after: Int, worst: Int): Flow<List<PendingAccountProgress>> =
        rows.map { held ->
            held.values.filter { it.attempts >= after }.sortedByDescending { it.attempts }.take(worst)
        }
}
