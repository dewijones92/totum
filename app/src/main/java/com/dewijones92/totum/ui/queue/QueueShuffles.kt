package com.dewijones92.totum.ui.queue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.OfflineReadiness
import com.dewijones92.totum.queue.GroupDownloads
import com.dewijones92.totum.queue.blocks
import com.dewijones92.totum.queue.groupedBlockCount
import com.dewijones92.totum.queue.shuffleGroupOrder
import com.dewijones92.totum.stats.UseKind
import com.dewijones92.totum.ui.common.ShuffleAsk

internal fun shuffleUpNextOrNull(container: AppContainer, upNext: List<QueueEntry>, ask: ShuffleAsk): (() -> Unit)? =
    asking(container, ask, "up next") { container.playbackQueue.shuffleUpNext() }.takeIf { upNext.size > 1 }

internal fun shuffleGroupsOrNull(container: AppContainer, upNext: List<QueueEntry>, ask: ShuffleAsk): (() -> Unit)? =
    asking(container, ask, "group order") { container.playbackQueue.shuffleGroupOrder() }
        .takeIf { upNext.groupedBlockCount() > 0 && upNext.blocks().size > 1 }

private fun asking(container: AppContainer, ask: ShuffleAsk, what: String, shuffle: () -> Unit): () -> Unit = {
    ask { startAtRandom ->
        shuffle()
        container.statsLog.use(UseKind.SHUFFLE, what)
        container.randomStarts.assign(
            container.playbackQueue.state.value.upNext.map { it.item.item },
            what,
            startAtRandom,
        )
    }
}

@Composable
internal fun QueueOfflineSummary(
    container: AppContainer,
    entries: List<QueueEntry>,
    downloads: Map<MediaItemId, DownloadState>,
) {
    val settings by container.appPreferences.settings.collectAsStateWithLifecycle()
    val groupChoices by container.groupDownloads.choices.collectAsStateWithLifecycle()
    OfflineSummary(
        readiness = readinessOf(entries, downloads) { GroupDownloads.fetchesAutomatically(it, groupChoices) },
        autoDownloadOff = !settings.autoDownloadQueue,
        waitingForWifi = settings.autoDownloadQueue && !container.autoDownloadAllowedNow(),
    )
}

private fun readinessOf(
    entries: List<QueueEntry>,
    downloads: Map<MediaItemId, DownloadState>,
    fetchesAutomatically: (QueueEntry) -> Boolean,
): OfflineReadiness = OfflineReadiness.of(
    entries.map { it.item.item.id },
    stateOf = { downloads[it] ?: DownloadState.NotDownloaded },
    fetchedAutomatically = { id ->
        entries.firstOrNull { it.item.item.id == id }?.let(fetchesAutomatically) ?: true
    },
)
