package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.hasPlaceholderTitle
import com.dewijones92.totum.settings.NetworkStatus
import com.dewijones92.totum.video.VideoPlaybackLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.pow

internal class SharedMetadataRepair(
    private val queue: StateFlow<QueueSnapshot>,
    private val downloads: DownloadManager,
    private val resolve: suspend (PlayableItem) -> MediaItem?,
    private val onResolved: (MediaItem) -> Unit,
    private val awaitNetwork: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val retryDelayMs: Long = 30_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Tries(val count: Int, val nextAt: Long)

    private val tries = HashMap<MediaItemId, Tries>()

    fun start() {
        scope.launch {
            combine(queue, downloads.observeRecords()) { snapshot, records ->
                (snapshot.entries.map { it.item } + records.map { it.item })
                    .filter { it.item.hasPlaceholderTitle && it.handle is PlayHandle.Video }
                    .distinctBy { it.item.id }
            }.distinctUntilChanged().collectLatest { candidates -> repairAll(candidates) }
        }
    }

    private suspend fun repairAll(candidates: List<PlayableItem>) {
        val repaired = HashSet<MediaItemId>()
        while (true) {
            val left = candidates.filter { it.item.id !in repaired && (tries[it.item.id]?.count ?: 0) < MAX_TRIES }
            if (left.isEmpty()) return
            val due = left.filter { (tries[it.item.id]?.nextAt ?: 0) <= now() }
            if (due.isEmpty()) {
                delay(left.minOf { tries.getValue(it.item.id).nextAt } - now())
                continue
            }
            awaitNetwork()
            due.forEach { item ->
                if (repair(item)) {
                    repaired += item.item.id
                    tries.remove(item.item.id)
                } else {
                    failed(item.item.id)
                }
            }
        }
    }

    private fun failed(id: MediaItemId) {
        val count = (tries[id]?.count ?: 0) + 1
        val gap = retryDelayMs * BACK_OFF.pow(count - 1).toLong()
        tries[id] = Tries(count, now() + gap)
        if (count >= MAX_TRIES) {
            Diag.log("metadata", "${id.value}: no title after $count tries; not asking YouTube again this session")
        } else {
            Diag.log("metadata", "${id.value}: title still unavailable (try $count of $MAX_TRIES); next in ${gap}ms")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun repair(item: PlayableItem): Boolean {
        return try {
            val resolved = resolve(item)?.takeIf {
                it.id == item.item.id && it.title.isNotBlank() && !it.hasPlaceholderTitle
            }
            currentCoroutineContext().ensureActive()
            if (resolved == null) {
                false
            } else {
                downloads.learnFacts(resolved)
                onResolved(resolved)
                Diag.log("metadata", "${item.item.id.value}: queue and download learned title=\"${resolved.title}\"")
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Diag.warn("metadata", "${item.item.id.value}: metadata repair failed", failure)
            false
        }
    }
}

internal fun startSharedMetadataRepair(
    queue: PlaybackQueue,
    launcher: VideoPlaybackLauncher,
    downloads: DownloadManager,
    network: NetworkStatus,
    scope: CoroutineScope,
) {
    SharedMetadataRepair(
        queue = queue.state,
        downloads = downloads,
        resolve = { item ->
            (item.handle as? PlayHandle.Video)?.let { handle -> launcher.describe(handle.watchUrl, item.item.sourceId) }
        },
        onResolved = queue::adoptFacts,
        awaitNetwork = network::awaitOnline,
        scope = scope,
    ).start()
}

private const val MAX_TRIES = 5
private const val BACK_OFF = 4.0
