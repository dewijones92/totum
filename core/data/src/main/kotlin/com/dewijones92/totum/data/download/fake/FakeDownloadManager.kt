package com.dewijones92.totum.data.download.fake

import com.dewijones92.totum.data.download.DownloadEvent
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.data.download.DownloadRecord
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.fillingSilenceFrom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** In-memory [DownloadManager] for tests and previews; downloads complete instantly. */
// The count is DownloadManager's surface plus the hooks a test drives it with; a fake exists to be
// poked at, and hiding those behind fewer methods would only make the tests harder to read.
@Suppress("TooManyFunctions")
public class FakeDownloadManager : DownloadManager {

    override suspend fun learnFacts(resolved: MediaItem) {
        known.update { items ->
            items.mapValues { (id, item) ->
                if (id == resolved.id) item.copy(item = item.item.fillingSilenceFrom(resolved)) else item
            }
        }
        completed.update { copies ->
            copies.map { copy ->
                if (copy.item.id == resolved.id) {
                    copy.copy(playable = copy.playable.copy(item = copy.item.fillingSilenceFrom(resolved)))
                } else {
                    copy
                }
            }
        }
    }

    private val downloads = MutableStateFlow<Map<MediaItemId, DownloadState>>(emptyMap())

    private val completed = MutableStateFlow<List<DownloadedMedia>>(emptyList())

    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 64)

    override fun events(): Flow<DownloadEvent> = _events

    /**
     * Parks a download mid-flight. [download] completes instantly, which is what most
     * tests want, but anything that observes work *in progress* needs a download that
     * stays in progress.
     */
    public fun setDownloading(id: MediaItemId, downloadedBytes: Long, totalBytes: Long?) {
        downloads.update { it + (id to DownloadState.Downloading(downloadedBytes, totalBytes)) }
    }

    /**
     * Records a failure in the observable state, which [emit] deliberately does not.
     *
     * Needed because [emit] only fires an event, so a test that drives a consumer of
     * `observeDownloads()` — the auto-downloader deciding whether to retry — could not see a
     * failure at all, and every retry test silently passed for the wrong reason.
     */
    public fun setFailed(id: MediaItemId, reason: String) {
        downloads.update { it + (id to DownloadState.Failed(reason)) }
    }

    /** Emits an arbitrary transition, so a test can drive a consumer of [events]. */
    public fun emit(item: MediaItem, state: DownloadState) {
        _events.tryEmit(DownloadEvent(item, state))
    }

    override fun observeDownloads(): Flow<Map<MediaItemId, DownloadState>> = downloads

    override fun observeDownloaded(): Flow<List<DownloadedMedia>> = completed

    /** Items the fake knows about, so a record can name what it is about. */
    private val known = MutableStateFlow<Map<MediaItemId, PlayableItem>>(emptyMap())

    override fun observeRecords(): Flow<List<DownloadRecord>> =
        combine(downloads, known) { states, items ->
            states.mapNotNull { (id, state) -> items[id]?.let { DownloadRecord(it, state) } }
        }

    /** Registers an item so [observeRecords] can name it, without pretending it downloaded. */
    public fun know(item: PlayableItem) {
        known.update { it + (item.item.id to item) }
    }

    override fun observe(id: MediaItemId): Flow<DownloadState> =
        downloads.map { it[id] ?: DownloadState.NotDownloaded }

    /** The item handed to the most recent [download] — lets a test assert WHICH url was used. */
    public var lastItem: PlayableItem? = null
        private set

    override suspend fun download(item: PlayableItem, audioOnly: Boolean) {
        val media = item.item
        val path = "/fake/${media.id.value}.media"
        requested.add(media.id to audioOnly)
        lastItem = item
        known.update { it + (media.id to item) }
        downloads.update { it + (media.id to DownloadState.Downloaded(path, audioOnly = audioOnly)) }
        completed.update { it.filterNot { done -> done.item.id == media.id } + DownloadedMedia(item, path, audioOnly) }
        _events.tryEmit(DownloadEvent(media, DownloadState.Downloaded(path, audioOnly)))
    }

    /** Every item asked for, in order, with the variant requested — for assertions. */
    public val requested: MutableList<Pair<MediaItemId, Boolean>> = mutableListOf()

    /** Every id a caller asked to stop, in order — so a test can assert the tap reached the seam. */
    public val cancelled: MutableList<MediaItemId> = mutableListOf()

    /** Every id a caller asked to start over. */
    public val retried: MutableList<MediaItemId> = mutableListOf()

    override suspend fun cancel(id: MediaItemId) {
        cancelled += id
        downloads.update { it - id }
        completed.update { done -> done.filterNot { it.item.id == id } }
    }

    /**
     * Re-runs whatever was last asked for on this id.
     *
     * The fake keeps the request rather than looking one up, which is the same promise the real
     * one makes: a retry fetches the variant originally wanted, not the default.
     */
    override suspend fun retry(id: MediaItemId) {
        retried += id
        val item = lastItem?.takeIf { it.item.id == id } ?: return
        download(item, requested.lastOrNull { it.first == id }?.second ?: false)
    }

    override suspend fun delete(id: MediaItemId) {
        downloads.update { it - id }
        completed.update { done -> done.filterNot { it.item.id == id } }
    }
}
