package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Routes every download through one [strategy] and records progress in the
 * [store]. Both pillars share this: a podcast enclosure and a video are both just a
 * [PlayableItem], and the handle says which mechanics apply.
 */
// The count is DownloadManager's own interface surface plus three small helpers; splitting it would
// scatter the one thing this class knows, which is how a download is started, stopped and recorded.
@Suppress("TooManyFunctions")
public class DefaultDownloadManager(
    private val downloadDir: File,
    private val store: DownloadStore,
    private val strategy: DownloadStrategy,
    private val scope: CoroutineScope,
) : DownloadManager {

    init {
        // A "Downloading" record at startup means the process died mid-download. Its coroutine is
        // gone, so nothing will ever move it -- but DELETING it, which is what happened until
        // 2026-08-31, makes the download silently un-happen: the row disappears, the partial file
        // becomes bytes nothing points at, and whoever asked for it is told nothing. For a
        // backgrounded app that is often, because Android reclaims the process. Recorded as failed
        // instead, so Library offers Retry and the queue's automatic pass asks again. No event is
        // emitted: the row is the surface for this, and a failure notification for something about
        // to be retried automatically would only alarm.
        scope.launch {
            store.observeAll().first()
                .filterValues { it is DownloadState.Downloading }
                .keys.forEach { id ->
                    val request = store.request(id)
                    if (request == null) {
                        Diag.warn("download", "dropping interrupted download ${id.value}: no record of what it was")
                        store.remove(id)
                    } else {
                        Diag.warn(
                            "download",
                            "\"${request.item.item.title}\" was interrupted by the app stopping — it can be retried",
                        )
                        store.put(request.item, DownloadState.Failed(INTERRUPTED), request.audioOnly)
                    }
                }
        }
    }

    // Replay nothing and never suspend the producer: a download must not be held up by
    // whether anyone is listening to notifications.
    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = EVENT_BUFFER)

    override fun events(): Flow<DownloadEvent> = _events.asSharedFlow()

    override fun observeDownloads(): Flow<Map<MediaItemId, DownloadState>> = store.observeAll()

    override fun observeDownloaded(): Flow<List<DownloadedMedia>> = store.observeDownloaded()

    override fun observeRecords(): Flow<List<DownloadRecord>> = store.observeRecords()

    override fun observe(id: MediaItemId): Flow<DownloadState> =
        store.observeAll().map { it[id] ?: DownloadState.NotDownloaded }.distinctUntilChanged()

    /**
     * The coroutine fetching each item, so it can be stopped.
     *
     * Nothing held them before, which meant more than "no cancel button": [delete] on a download in
     * flight removed the record while the coroutine carried on and wrote it straight back, so the
     * row reappeared and the bytes kept coming. Guarded by a mutex because starts, cancels and
     * completions all touch this and they arrive from different coroutines.
     */
    private val running = mutableMapOf<MediaItemId, Job>()
    private val runningLock = Mutex()

    override suspend fun download(item: PlayableItem, audioOnly: Boolean) {
        val media = item.item
        if (store.get(media.id).satisfies(audioOnly)) return
        // What is already being fetched is answered from MEMORY, under the lock, never from the
        // store. The store is Room: its first Downloading row lands milliseconds after it is
        // written, so two callers arriving inside that window both read NotDownloaded, both claim
        // the item, and two coroutines then write the same file — a corrupt download for twice the
        // data. Seen on a device 2026-08-31, three queued videos producing six downloads, within a
        // minute of the queue first fetching several at once. Anything that asks twice in quick
        // succession does it: two queue edits, or several URLs shared into the app one after
        // another.
        runningLock.withLock {
            if (running.containsKey(media.id)) {
                Diag.log("download", "already fetching \"${media.title}\" — not starting a second")
                return
            }
            running[media.id] = fetch(item, audioOnly)
        }
    }

    /** Starts the coroutine that fetches [item] and records it. Called only while holding the lock. */
    private suspend fun fetch(item: PlayableItem, audioOnly: Boolean): Job {
        val media = item.item
        store.put(item, DownloadState.Downloading(0, null), audioOnly)
        _events.tryEmit(DownloadEvent(media, DownloadState.Downloading(0, null)))
        val target = File(downloadDir.apply { mkdirs() }, media.id.fileName())
        Diag.log("download", "start audioOnly=$audioOnly ${media.title}")
        val job = scope.launch {
            strategy.download(item, target, audioOnly).collect { state ->
                store.put(item, state, audioOnly)
                _events.tryEmit(DownloadEvent(media, state))
                when (state) {
                    is DownloadState.Failed -> Diag.warn("download", "failed ${media.title}: ${state.reason}")
                    is DownloadState.Downloaded -> {
                        // Whatever produced the finished file, no part-fetched copy of it may
                        // survive: the next fetch of this id would resume from bytes belonging to
                        // a download that has already finished.
                        target.partialDownload().delete()
                        Diag.log("download", "done ${media.title}")
                    }
                    else -> Unit
                }
            }
        }
        // Releasing its own claim on the way out, so the map tracks what is ACTUALLY running rather
        // than everything ever started — otherwise "cancel" would cancel an already-completed job,
        // a second request for the item could never start, and the map would grow for the life of
        // the process.
        job.invokeOnCompletion {
            scope.launch { runningLock.withLock { running.remove(media.id) } }
        }
        return job
    }

    override suspend fun cancel(id: MediaItemId) {
        val job = runningLock.withLock { running.remove(id) }
        // Read BEFORE the record goes: an event with no item cannot be rendered, and the
        // notification consumer takes the title off it.
        val cancelled = store.request(id)?.item?.item
        // A no-op rather than an error when nothing is running: by the time a tap arrives the
        // download may have finished, and that race must not throw at the person who tapped.
        if (job == null && store.get(id) !is DownloadState.Downloading) {
            Diag.log("download", "cancel $id: nothing was running")
            return
        }
        job?.cancelAndJoin()
        // The partial file goes too. Leaving it would be invisible bytes: no record points at it,
        // so nothing in the app could ever show it or delete it.
        val target = File(downloadDir, id.fileName())
        val partFetched = target.partialDownload()
        val removedBytes = (target.takeIf { it.exists() }?.length() ?: 0) +
            (partFetched.takeIf { it.exists() }?.length() ?: 0)
        target.delete()
        partFetched.delete()
        store.remove(id)
        cancelled?.let { _events.tryEmit(DownloadEvent(it, DownloadState.NotDownloaded)) }
        Diag.log(
            "download",
            "cancelled ${cancelled?.title ?: id.value}, dropped ${removedBytes}B of partial file",
        )
    }

    override suspend fun retry(id: MediaItemId) {
        val request = store.request(id) ?: run {
            Diag.warn("download", "retry $id: nothing to retry, there is no record of it")
            return
        }
        Diag.log("download", "retry audioOnly=${request.audioOnly} ${request.item.item.title}")
        // Cleared first, or `download` sees a record and decides the request is already satisfied.
        store.remove(id)
        download(request.item, request.audioOnly)
    }

    /**
     * Whether an existing record already covers a request. An audio-only file does
     * NOT cover a request for the full media — otherwise the queue's automatic audio
     * download would make "Download" look done when the video was never fetched.
     */
    private fun DownloadState.satisfies(audioOnly: Boolean): Boolean = when (this) {
        is DownloadState.Downloading -> true
        is DownloadState.Downloaded -> audioOnly || !this.audioOnly
        else -> false
    }

    override suspend fun delete(id: MediaItemId) {
        // Stop it first if it is still going. Without this the coroutine outlived the delete and
        // wrote its next progress update straight back, so the row reappeared seconds later and the
        // bytes kept arriving — a deletion that undid itself.
        runningLock.withLock { running.remove(id) }?.cancelAndJoin()
        (store.get(id) as? DownloadState.Downloaded)?.let { File(it.localPath).delete() }
        // Also whatever a cancelled-mid-flight download left behind, which has no record naming it —
        // including the part-fetched file, which would otherwise be resumed by a later download of
        // the same item long after this one was deleted.
        File(downloadDir, id.fileName()).let { target ->
            target.delete()
            target.partialDownload().delete()
        }
        store.remove(id)
    }

    private companion object {
        const val EVENT_BUFFER = 64

        /**
         * Why an in-flight download is failed at startup. Deliberately worded so that
         * `DownloadState.Failed.isPermanent` does not match it: the app stopping says nothing at all
         * about the media, so asking again is exactly the right thing to do.
         */
        const val INTERRUPTED = "the app stopped before the download finished"
    }

    /** Opaque, filesystem-safe name derived from the stable item id. */
    private fun MediaItemId.fileName(): String = "${value.hashCode().toUInt()}.media"
}
