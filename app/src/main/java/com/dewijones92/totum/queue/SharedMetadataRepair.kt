package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItem
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

internal class SharedMetadataRepair(
    private val queue: StateFlow<QueueSnapshot>,
    private val downloads: DownloadManager,
    private val resolve: suspend (PlayableItem) -> MediaItem?,
    private val onResolved: (MediaItem) -> Unit,
    private val awaitNetwork: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val retryDelayMs: Long = 30_000,
) {
    fun start() {
        scope.launch {
            combine(queue, downloads.observeRecords()) { snapshot, records ->
                (snapshot.entries.map { it.item } + records.map { it.item })
                    .filter { it.item.hasPlaceholderTitle && it.handle is PlayHandle.Video }
                    .distinctBy { it.item.id }
            }.distinctUntilChanged().collectLatest { candidates ->
                var pending = candidates
                while (pending.isNotEmpty()) {
                    awaitNetwork()
                    pending = pending.filter { item -> !repair(item) }
                    if (pending.isNotEmpty()) delay(retryDelayMs)
                }
            }
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
                Diag.log("metadata", "${item.item.id.value}: title still unavailable; retrying in ${retryDelayMs}ms")
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
            Diag.warn("metadata", "${item.item.id.value}: metadata repair failed; will retry", failure)
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
