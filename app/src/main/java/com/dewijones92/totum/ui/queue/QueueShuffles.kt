package com.dewijones92.totum.ui.queue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.DownloadState.NotDownloaded
import com.dewijones92.totum.domain.DownloadedFilter
import com.dewijones92.totum.domain.ItemFilters
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.OfflineReadiness
import com.dewijones92.totum.domain.playsWithNoSignal
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
    downloads: State<Map<MediaItemId, DownloadState>>,
    filters: MutableState<ItemFilters>,
) {
    val settings by container.appPreferences.settings.collectAsStateWithLifecycle()
    val groupChoices by container.groupDownloads.choices.collectAsStateWithLifecycle()
    val offline by remember(container) { container.offlineChanges }.collectAsStateWithLifecycle(container.isOffline())
    val playable = entries.count { it.item.playsWithNoSignal(downloads.value[it.item.item.id] ?: NotDownloaded) }
    OfflineSummary(
        readiness = readinessOf(entries, downloads.value) { GroupDownloads.fetchesAutomatically(it, groupChoices) },
        autoDownloadOff = !settings.autoDownloadQueue,
        waitingForWifi = settings.autoDownloadQueue && !container.autoDownloadAllowedNow(),
        playable = OfflinePlayable(
            count = playable,
            offline = offline,
            onlyThose = filters.value.downloaded == DownloadedFilter.PLAYS_OFFLINE,
            onToggle = {
                val on = filters.value.downloaded != DownloadedFilter.PLAYS_OFFLINE
                Diag.log(
                    "queue",
                    "dewidebug offline filter ${if (on) "on" else "off"}: $playable of ${entries.size} entries play " +
                        "with no signal (offline now=$offline)",
                )
                filters.value = filters.value.copy(
                    downloaded = if (on) DownloadedFilter.PLAYS_OFFLINE else DownloadedFilter.ANY,
                )
            },
        ),
    )
}

private fun readinessOf(
    entries: List<QueueEntry>,
    downloads: Map<MediaItemId, DownloadState>,
    fetchesAutomatically: (QueueEntry) -> Boolean,
): OfflineReadiness {
    val firstEntry = LinkedHashMap<MediaItemId, QueueEntry>()
    entries.forEach { firstEntry.putIfAbsent(it.item.item.id, it) }
    return OfflineReadiness.of(
        entries.map { it.item.item.id },
        stateOf = { downloads[it] ?: DownloadState.NotDownloaded },
        fetchedAutomatically = { id -> firstEntry[id]?.let(fetchesAutomatically) ?: true },
    )
}
