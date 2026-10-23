package com.dewijones92.totum.downloads

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.operations.tracking
import com.dewijones92.totum.settings.DownloadChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EpisodeDownloads(
    private val downloads: DownloadManager,
    private val scope: CoroutineScope,
    private val operations: Operations? = null,
) {

    data class Ask(val title: String, val items: List<PlayableItem>) {
        val offersAChoice: Boolean get() = items.any { it.handle is PlayHandle.Video && !it.isSong }
    }

    private val _asking = MutableStateFlow<Ask?>(null)
    val asking: StateFlow<Ask?> = _asking.asStateFlow()

    fun ask(title: String, items: List<PlayableItem>) {
        Diag.log(TAG, "choose episodes of \"$title\": ${items.size} offered, all ticked")
        _asking.value = Ask(title, items)
    }

    fun dismiss() {
        _asking.value?.let { Diag.log(TAG, "choose episodes of \"${it.title}\": closed") }
        _asking.value = null
    }

    fun start(ask: Ask, chosen: Set<MediaItemId>, choice: DownloadChoice) {
        val picked = ask.items.filter { it.item.id in chosen }
        Diag.log(
            TAG,
            "downloading ${picked.size} of ${ask.items.size} from \"${ask.title}\" as ${choice.stored} " +
                "(a few at a time; torrents and podcasts as their files)",
        )
        _asking.value = null
        scope.launch {
            operations.tracking("episodes:${ask.title}", OperationKind.DOWNLOAD_BATCH, ask.title) { progress ->
                progress?.report(step = 0, steps = picked.size)
                enqueue(picked, choice)
                followToTheEnd(picked.map { it.item.id }.toSet(), progress)
            }
        }
    }

    private suspend fun followToTheEnd(ids: Set<MediaItemId>, progress: Operations.Progress?): OperationEnd {
        try {
            val started = mutableSetOf<MediaItemId>()
            val settled = downloads.observeDownloads().map { states -> ids.associateWith { states[it] } }
                .onEach { now ->
                    started += now.filterValues { it is DownloadState.Downloading }.keys
                    progress?.report(step = now.values.count { it is DownloadState.Downloaded })
                }
                .first { now -> now.all { (id, state) -> state.isSettled() || (id in started && state.isGone()) } }
            val done = settled.values.count { it is DownloadState.Downloaded }
            val failed = settled.values.count { it is DownloadState.Failed }
            Diag.log(TAG, "episode batch settled: $done downloaded, $failed failed, of ${ids.size}")
            return if (done == 0) OperationEnd.Nothing else OperationEnd.Done(done, partial = failed > 0)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val states = downloads.observeDownloads().first()
                val stopping = ids.filter { states[it] is DownloadState.Downloading }
                Diag.log(TAG, "episode batch cancelled from the strip: stopping ${stopping.size} still downloading")
                stopping.forEach { downloads.cancel(it) }
            }
            throw cancelled
        }
    }

    private fun DownloadState?.isSettled() = this is DownloadState.Downloaded || this is DownloadState.Failed

    private fun DownloadState?.isGone() = this == null || this is DownloadState.NotDownloaded

    private suspend fun enqueue(picked: List<PlayableItem>, choice: DownloadChoice) {
        picked.forEach { item ->
            val asSound = item.handle is PlayHandle.Video && (item.isSong || choice == DownloadChoice.Sound)
            val picture = item.handle is PlayHandle.Video && !asSound
            val height = (choice as? DownloadChoice.Video)?.maxHeight?.takeIf { picture }
            downloads.download(item, audioOnly = asSound, maxHeight = height)
        }
    }

    private companion object {
        const val TAG = "download"
    }
}

private val PlayableItem.isSong: Boolean get() = item.contentKind == MediaContentKind.MUSIC

internal fun startsTicked(item: PlayableItem, state: DownloadState?, choice: DownloadChoice): Boolean =
    state !is DownloadState.Downloaded ||
        (state.audioOnly && choice is DownloadChoice.Video && item.handle is PlayHandle.Video && !item.isSong)
