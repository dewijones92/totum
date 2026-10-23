package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.MediaItem
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
    private val onDownloaded: suspend (PlayableItem) -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) : DownloadManager {

    private val slots = Semaphore(MAX_AT_ONCE)

    override suspend fun learnFacts(resolved: MediaItem): Unit = store.learnFacts(resolved)

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

    private suspend fun fetchIn(
        item: PlayableItem,
        media: MediaItem,
        target: File,
        audioOnly: Boolean,
        maxHeight: Int?,
    ) {
        var lastProgressAt = Long.MIN_VALUE
        var heldBack = 0
        strategy.download(item, target, audioOnly, maxHeight).collect { state ->
            if (state is DownloadState.Downloading) {
                val now = nowMs()
                if (lastProgressAt != Long.MIN_VALUE && now - lastProgressAt < PROGRESS_WRITE_EVERY_MS) {
                    heldBack++
                    return@collect
                }
                lastProgressAt = now
            } else if (heldBack > 0) {
                Diag.log("download", "${media.title}: $heldBack progress tick(s) not written, at most one a second")
            }
            val recorded = settledUpgrade(media, state)
            store.put(item, recorded.first, recorded.second ?: audioOnly)
            _events.tryEmit(DownloadEvent(media, if (state is DownloadState.Failed) state else recorded.first))
            when (state) {
                is DownloadState.Failed -> Diag.warn("download", "failed ${media.title}: ${state.reason}")
                is DownloadState.Downloaded -> {
                    // Whatever produced the finished file, no part-fetched copy of it may
                    // survive: the next fetch of this id would resume from bytes belonging to
                    // a download that has already finished.
                    target.partialDownload().delete()
                    Diag.log("download", "done ${media.title} sponsorSegmentsCut=${state.sponsorSegmentsCut}")
                    onDownloaded(item)
                }
                else -> Unit
            }
        }
    }

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

    private data class Kept(val copy: DownloadState.Downloaded, val audioOnly: Boolean) {
        val word: String get() = if (audioOnly) "sound" else "full"
    }

    private val upgrading = ConcurrentHashMap<MediaItemId, Kept>()
    private val runningLock = Mutex()

    override suspend fun download(item: PlayableItem, audioOnly: Boolean, maxHeight: Int?) {
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
            running[media.id] = fetch(item, audioOnly, maxHeight)
        }
    }

    /** Starts the coroutine that fetches [item] and records it. Called only while holding the lock. */
    private suspend fun fetch(item: PlayableItem, audioOnly: Boolean, maxHeight: Int? = null): Job {
        val media = item.item
        val kept = (store.get(media.id) as? DownloadState.Downloaded)?.let { copy ->
            Kept(copy, store.request(media.id)?.audioOnly ?: copy.audioOnly)
        }
        kept?.let { upgrading[media.id] = it } ?: upgrading.remove(media.id)
        store.put(item, DownloadState.Downloading(0, null), audioOnly)
        _events.tryEmit(DownloadEvent(media, DownloadState.Downloading(0, null)))
        val name = if (kept != null) media.id.upgradeFileName() else media.id.fileName()
        val target = File(downloadDir.apply { mkdirs() }, name)
        Diag.log(
            "download",
            "start audioOnly=$audioOnly maxHeight=${maxHeight ?: "best"} ${media.title}" +
                (kept?.let { " (replacing the ${it.word} copy, kept until this lands)" } ?: ""),
        )
        val job = scope.launch {
            if (slots.availablePermits == 0) {
                Diag.log("download", "waiting for a free slot: ${media.title} (at most $MAX_AT_ONCE at once)")
            }
            slots.withPermit { fetchIn(item, media, target, audioOnly, maxHeight) }
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

    private fun settledUpgrade(media: MediaItem, state: DownloadState): Pair<DownloadState, Boolean?> {
        val kept = upgrading[media.id] ?: return state to null
        return when (state) {
            is DownloadState.Downloaded -> {
                upgrading.remove(media.id)
                val final = File(downloadDir, media.id.fileName())
                val fetched = File(state.localPath)
                when {
                    fetched.path == final.path -> state to null
                    !fetched.exists() -> state.also {
                        Diag.warn(
                            "download",
                            "${media.title}: finished, but ${fetched.name} is not on the disk; left as reported",
                        )
                    } to null
                    else -> {
                        final.delete()
                        if (!fetched.renameTo(final)) fetched.copyTo(final, overwrite = true).also { fetched.delete() }
                        Diag.log("download", "${media.title}: the new copy replaced the old one at ${final.name}")
                        state.copy(localPath = final.path) to null
                    }
                }
            }
            is DownloadState.Failed -> {
                upgrading.remove(media.id)
                File(downloadDir, media.id.upgradeFileName()).let {
                    it.delete()
                    it.partialDownload().delete()
                }
                Diag.warn(
                    "download",
                    "${media.title}: the new download failed (${state.reason}); the ${kept.word} copy is kept",
                )
                kept.copy to kept.audioOnly
            }
            else -> state to null
        }
    }

    override suspend fun cancel(id: MediaItemId) {
        val job = runningLock.withLock { running.remove(id) }
        upgrading.remove(id)?.let { kept ->
            job?.cancelAndJoin()
            File(downloadDir, id.upgradeFileName()).let {
                it.delete()
                it.partialDownload().delete()
            }
            val request = store.request(id)
            request?.let { store.put(it.item, kept.copy, kept.audioOnly) }
            request?.let { _events.tryEmit(DownloadEvent(it.item.item, kept.copy)) }
            Diag.log(
                "download",
                "cancelled the new download of ${request?.item?.item?.title ?: id.value}; the old copy is kept"
            )
            return
        }
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

    override suspend fun noteOpened(id: MediaItemId) {
        if (store.noteOpened(id)) Diag.log("download", "opened ${id.value}: its download is marked opened now")
    }

    override suspend fun markUnreadable(id: MediaItemId, why: String) {
        val request = store.request(id)
            ?: return Diag.warn("download", "${id.value} unreadable, but there is no record")
        val file = (store.get(id) as? DownloadState.Downloaded)?.localPath?.let(::File)
        val bytes = file?.length() ?: 0L
        file?.delete()
        store.put(request.item, DownloadState.Failed(why), request.audioOnly)
        Diag.warn(
            "download",
            "\"${request.item.item.title}\" (${bytes}B, audioOnly=${request.audioOnly}) " +
                "could not be read from the disk; " +
                "deleted and kept as a failed download to fetch again",
        )
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

    private fun MediaItemId.upgradeFileName(): String = "${value.hashCode().toUInt()}.upgrade.media"
}

private const val PROGRESS_WRITE_EVERY_MS = 1_000L
private const val MAX_AT_ONCE = 4
private const val NANOS_PER_MS = 1_000_000L
