package com.dewijones92.totum.downloads

import android.content.Context
import androidx.core.content.edit
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.data.download.DownloadRecord
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.dewijones92.totum.domain.ItemShields
import com.dewijones92.totum.domain.titleOf

interface PendingDeleteStore {
    fun load(): Set<MediaItemId>
    fun save(ids: Set<MediaItemId>)
}

class PrefsPendingDeletes(context: Context) : PendingDeleteStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): Set<MediaItemId> = prefs.getStringSet(KEY, emptySet()).orEmpty().map(::MediaItemId).toSet()

    override fun save(ids: Set<MediaItemId>) {
        prefs.edit { putStringSet(KEY, ids.mapTo(mutableSetOf()) { it.value }) }
    }

    private companion object {
        const val PREFS = "totum_pending_download_deletes"
        const val KEY = "pending"
    }
}

data class DeleteOffer(val ids: List<MediaItemId>, val title: String?)

class PendingDownloadDeletes(
    private val inner: DownloadManager,
    private val scope: CoroutineScope,
    private val store: PendingDeleteStore,
    private val isLocked: (MediaItemId) -> Boolean,
    private val windowMs: Long = UNDO_WINDOW_MS,
    private val now: () -> Long = System::currentTimeMillis,
) : DownloadManager by inner {

    private val hidden = MutableStateFlow(store.load())
    val pending: StateFlow<Set<MediaItemId>> = hidden.asStateFlow()

    private val announced = MutableSharedFlow<DeleteOffer>(extraBufferCapacity = OFFERS_HELD)
    val offers: SharedFlow<DeleteOffer> = announced.asSharedFlow()

    private val batchLock = Any()
    private val batch = mutableListOf<Pair<MediaItemId, String?>>()
    private var announcing: Job? = null
    private var windowFreeAt = 0L

    init {
        if (hidden.value.isNotEmpty()) {
            Diag.log(
                "download",
                "dewidebug ${hidden.value.size} download delete(s) were pending when the app stopped; doing them now",
            )
            hidden.value.forEach { id -> scope.launch { commit(id, "start-up") } }
        }
    }

    override fun observeDownloads(): Flow<Map<MediaItemId, DownloadState>> =
        combine(inner.observeDownloads(), hidden) { states, gone -> states - gone }

    override fun observeDownloaded(): Flow<List<DownloadedMedia>> =
        combine(inner.observeDownloaded(), hidden) { list, gone -> list.filterNot { it.playable.item.id in gone } }

    override fun observeRecords(): Flow<List<DownloadRecord>> =
        combine(inner.observeRecords(), hidden) { list, gone -> list.filterNot { it.item.item.id in gone } }

    override fun observe(id: MediaItemId): Flow<DownloadState> =
        combine(inner.observe(id), hidden) { state, gone -> if (id in gone) DownloadState.NotDownloaded else state }

    override suspend fun download(item: PlayableItem, audioOnly: Boolean, maxHeight: Int?) {
        if (item.item.id in hidden.value) undo(listOf(item.item.id), "a new download of it")
        inner.download(item, audioOnly, maxHeight)
    }

    override suspend fun delete(id: MediaItemId) {
        inner.delete(id)
        if (id in hidden.value) hide(id, false)
    }

    override suspend fun deleteSoon(id: MediaItemId) {
        if (isLocked(id)) {
            Diag.log("download", "dewidebug ${id.value} is locked, so it is not hidden: asking whether to unlock it")
            inner.delete(id)
            return
        }
        val title = inner.observeDownloaded().first().firstOrNull { it.playable.item.id == id }?.playable?.item?.let(ItemShields::titleOf)
        hide(id, true)
        Diag.log("download", "dewidebug ${id.value} hidden, waiting for its batch's Undo offer to run its course")
        synchronized(batchLock) {
            batch += id to title
            announcing?.cancel()
            announcing = scope.launch { announceBatchAfterQuiet() }
        }
    }

    private suspend fun announceBatchAfterQuiet() {
        delay(ANNOUNCE_AFTER_MS)
        withContext(NonCancellable) {
            val (offer, commitAt) = synchronized(batchLock) {
                val offered = batch.toList().also { batch.clear() }
                val startsAt = maxOf(now(), windowFreeAt)
                windowFreeAt = startsAt + windowMs
                DeleteOffer(offered.map { it.first }, offered.singleOrNull()?.second) to windowFreeAt
            }
            announced.emit(offer)
            Diag.log(
                "download",
                "dewidebug Undo offered for ${offer.ids.size} deleted download(s); their files go together in " +
                    "${(commitAt - now()).coerceAtLeast(0)}ms unless it is taken",
            )
            scope.launch {
                delay((commitAt - now()).coerceAtLeast(0))
                offer.ids.forEach { commit(it, "its batch's undo window ended") }
            }
        }
    }

    fun undo(ids: List<MediaItemId>, why: String) {
        val taken = ids.filter { it in hidden.value }
        taken.forEach { hide(it, false) }
        Diag.log(
            "download",
            "dewidebug delete undone for ${taken.size} of ${ids.size} download(s) ($why); " +
                "${ids.size - taken.size} had already gone",
        )
    }

    private suspend fun commit(id: MediaItemId, why: String) {
        if (id !in hidden.value) return
        Diag.log("download", "dewidebug deleting ${id.value} for good ($why)")
        inner.delete(id)
        hide(id, false)
    }

    private fun hide(id: MediaItemId, on: Boolean) {
        hidden.update { if (on) it + id else it - id }
        store.save(hidden.value)
    }

    private companion object {
        const val UNDO_WINDOW_MS = 12_000L
        const val ANNOUNCE_AFTER_MS = 300L
        const val OFFERS_HELD = 16
    }
}
