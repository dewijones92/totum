package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.asPlayable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

public sealed interface LockRefusal {
    public val id: MediaItemId

    public data class Delete(override val id: MediaItemId) : LockRefusal

    public data class Replace(
        val item: PlayableItem,
        val audioOnly: Boolean,
        val maxHeight: Int? = null,
    ) : LockRefusal {
        override val id: MediaItemId get() = item.item.id
    }
}

public class LockingDownloadManager(
    private val inner: DownloadManager,
    public val locks: DownloadLocks,
) : DownloadManager by inner {

    private val refused = MutableSharedFlow<LockRefusal>(extraBufferCapacity = REFUSALS_HELD)
    public val refusals: SharedFlow<LockRefusal> = refused.asSharedFlow()

    override suspend fun delete(id: MediaItemId) {
        if (lockedCopy(id)) {
            Diag.log("download", "kept ${id.value}: the download is locked; asking whether to unlock it")
            refused.emit(LockRefusal.Delete(id))
            return
        }
        inner.delete(id)
    }

    override suspend fun download(item: PlayableItem, audioOnly: Boolean, maxHeight: Int?) {
        val id = item.item.id
        if (lockedCopy(id)) {
            Diag.log(
                "download",
                "kept ${id.value} as it is: the download is locked, so a new ${if (audioOnly) "audio" else "video"} " +
                    "download would replace it; asking whether to unlock it",
            )
            refused.emit(LockRefusal.Replace(item, audioOnly, maxHeight))
            return
        }
        inner.download(item, audioOnly, maxHeight)
    }

    override suspend fun download(item: MediaItem, audioOnly: Boolean, maxHeight: Int?): Unit =
        download(item.asPlayable(), audioOnly, maxHeight)

    public suspend fun unlockAndDownload(item: PlayableItem, audioOnly: Boolean, maxHeight: Int? = null) {
        locks.set(item.item.id, false)
        Diag.log("download", "unlocked ${item.item.id.value} by hand to download it again (audioOnly=$audioOnly)")
        inner.download(item, audioOnly, maxHeight)
    }

    private suspend fun lockedCopy(id: MediaItemId): Boolean =
        id in locks.locked.value && inner.observe(id).first() is DownloadState.Downloaded

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
