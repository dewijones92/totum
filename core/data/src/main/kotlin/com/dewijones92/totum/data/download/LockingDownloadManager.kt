package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

public interface DownloadLocks {
    public val locked: StateFlow<Set<MediaItemId>>

    public fun set(id: MediaItemId, locked: Boolean)
}

public class InMemoryDownloadLocks(initial: Set<MediaItemId> = emptySet()) : DownloadLocks {
    private val state = MutableStateFlow(initial)
    override val locked: StateFlow<Set<MediaItemId>> = state.asStateFlow()

    override fun set(id: MediaItemId, locked: Boolean) {
        state.update { if (locked) it + id else it - id }
    }
}

public class LockingDownloadManager(
    private val inner: DownloadManager,
    public val locks: DownloadLocks,
) : DownloadManager by inner {

    private val refused = MutableSharedFlow<MediaItemId>(extraBufferCapacity = REFUSALS_HELD)
    public val refusals: SharedFlow<MediaItemId> = refused.asSharedFlow()

    override suspend fun delete(id: MediaItemId) {
        if (id in locks.locked.value) {
            Diag.log("download", "kept ${id.value}: the download is locked; asking whether to unlock it")
            refused.emit(id)
            return
        }
        inner.delete(id)
    }

    public suspend fun unlockAndDelete(id: MediaItemId) {
        locks.set(id, false)
        Diag.log("download", "unlocked ${id.value} by hand and deleted it")
        inner.delete(id)
    }

    public fun setLocked(id: MediaItemId, locked: Boolean) {
        locks.set(id, locked)
        Diag.log("download", "${id.value} ${if (locked) "locked: kept from deletes and clean-up" else "unlocked"}")
    }

    private companion object {
        const val REFUSALS_HELD = 64
    }
}
