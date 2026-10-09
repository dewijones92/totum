package com.dewijones92.totum.ui.library

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.StorageUsage
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.common.TrackedViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shows everything available offline — downloaded items across both pillars.
 *
 * The download records are the whole source. This used to combine podcast episodes with
 * download states, so a downloaded video sat on disk and never appeared here.
 */
class LibraryViewModel(
    private val queue: PlaybackQueue,
    private val downloads: DownloadManager,
    private val fileSize: (String) -> Long = { File(it).length() },
    private val freeSpace: () -> Long? = { null },
    /** Injected so a test can drive the sizing pass; it is real disk IO in the app. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TrackedViewModel("library") {

    /** A download with the space it actually occupies on disk. */
    data class Entry(val media: DownloadedMedia, val sizeBytes: Long) {
        val item get() = media.item
    }

    private val sort = MutableStateFlow(DownloadSort.DEFAULT)
    val sortOrder: StateFlow<DownloadSort> = sort.asStateFlow()

    fun setSort(order: DownloadSort) {
        sort.value = order
    }

    /**
     * What is being fetched RIGHT NOW, newest progress first.
     *
     * The Library listed only finished downloads, so the moment anything was actually happening
     * there was nowhere in the app that said so — Dewi, 2026-08-02: *"its not clear from gui what
     * is downloading atm"*. The queue's own rows now show progress too, but a download started
     * from anywhere else had no home at all, and "is it doing something?" is the question this
     * screen exists to answer.
     */
    /**
     * Every download row, whatever state it is in — the one stream the three sections come from.
     *
     * Shared rather than three separate queries so the sections cannot disagree, and because it is
     * the only stream that carries the ITEM for a row that has not finished. Without it an
     * in-progress row had no title to show and printed the raw media id instead.
     */
    private val records = downloads.observeRecords()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val inProgress: StateFlow<List<InProgress>> = records
        .map { rows ->
            rows.mapNotNull { row ->
                (row.state as? DownloadState.Downloading)?.let { InProgress(row.item.item, it) }
            }.sortedByDescending { it.state.fraction ?: 0f }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /** One download in flight: which item, and how far it has got. */
    data class InProgress(val item: MediaItem, val state: DownloadState.Downloading) {
        val id: MediaItemId get() = item.id
    }

    /**
     * A download that stopped without finishing, and why.
     *
     * These had nowhere to be shown at all: the Library listed finished downloads and in-flight
     * ones, so a failure simply vanished from the UI while its row sat in the database. Someone
     * waiting for an episode on a plane would find no episode and no explanation.
     */
    data class Failed(val item: MediaItem, val reason: String) {
        val id: MediaItemId get() = item.id
    }

    val failed: StateFlow<List<Failed>> = records
        .map { rows ->
            rows.mapNotNull { row ->
                (row.state as? DownloadState.Failed)?.let { Failed(row.item.item, it.reason) }
            }.sortedBy { it.item.title.lowercase() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val downloaded: StateFlow<List<Entry>> = combine(
        downloads.observeDownloaded(),
        sort,
    ) { items, order ->
        // Sized off the main thread: this stats a file per download, which is cheap but
        // is still disk IO and there is no reason for it to be on the frame path.
        withContext(io) {
            // Sized FIRST, then ordered: a sort by size cannot work on a list that does not know
            // its sizes yet, and doing it the other way round silently produced source order.
            val sized = items.map { Entry(it, fileSize(it.localPath)) }
            order.sortedBy(sized, item = { it.item }, size = { it.sizeBytes })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /**
     * What the downloads are costing. Derived from the same list rather than counted
     * separately, so the header can never disagree with the rows under it.
     */
    val storage: StateFlow<StorageUsage> = downloaded
        .map { entries -> StorageUsage(entries.size, entries.sumOf { it.sizeBytes }, freeSpace()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), StorageUsage.Empty)

    /** Songs on the phone gathered by album, for playing or removing a whole album at once. */
    val albums: StateFlow<List<AlbumOnPhone>> = downloads.observeDownloaded()
        .map(::albumsOnThisPhone)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    fun playAlbum(album: AlbumOnPhone) {
        Diag.log(
            "library",
            "play album on the phone \"${album.album.title}\": ${album.songs.size} songs from their files"
        )
        queue.playAll(album.songs.map { it.offline }, QueueGroup("album:${album.key}", album.album.title))
    }

    fun deleteAlbum(album: AlbumOnPhone) {
        Diag.log("library", "remove album from the phone \"${album.album.title}\": ${album.songs.size} files")
        viewModelScope.launch { album.songs.forEach { downloads.delete(it.item.id) } }
    }

    /** Plays the local file, through the queue like every other tap. */
    fun play(entry: Entry) {
        viewModelScope.launch { queue.playNow(entry.media.offline) }
    }

    fun delete(entry: Entry) {
        viewModelScope.launch { downloads.delete(entry.item.id) }
    }

    /** Stops one download that is still running, dropping its partial file. */
    fun cancel(id: MediaItemId) {
        viewModelScope.launch { downloads.cancel(id) }
    }

    /**
     * Stops everything in flight.
     *
     * A snapshot rather than a loop over the live list: cancelling mutates the very flow being
     * iterated, and on a queue that auto-downloads it would otherwise be a race between this and
     * the next item starting.
     */
    fun cancelAll() {
        val running = inProgress.value.map { it.id }
        viewModelScope.launch { running.forEach { downloads.cancel(it) } }
    }

    /** Starts a failed download over, fetching the variant originally asked for. */
    fun retry(id: MediaItemId) {
        viewModelScope.launch { downloads.retry(id) }
    }

    /** Forgets a failed download without retrying it. */
    fun dismiss(id: MediaItemId) {
        viewModelScope.launch { downloads.delete(id) }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LibraryViewModel(
                    queue = container.playbackQueue,
                    downloads = container.downloadManager,
                    freeSpace = container::freeDownloadSpaceBytes,
                )
            }
        }
    }
}
