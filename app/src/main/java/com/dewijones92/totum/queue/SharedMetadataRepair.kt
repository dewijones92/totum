package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.PublishedAge
import com.dewijones92.totum.domain.hasPlaceholderTitle
import com.dewijones92.totum.innertube.related.RelatedResult
import com.dewijones92.totum.innertube.related.YouTubeRelated
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
import java.time.Instant
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
    private val dateOf: suspend (MediaItemId) -> Instant? = { null },
) {
    private class Tries(val count: Int, val nextAt: Long)

    private val tries = HashMap<MediaItemId, Tries>()

    fun start() {
        scope.launch {
            combine(queue, downloads.observeRecords()) { snapshot, records ->
                val near = snapshot.entries.withIndex()
                    .filter { (i, _) -> i - snapshot.place in NEAR }
                    .map { it.value.item }
                val titles = (snapshot.entries.map { it.item } + records.map { it.item })
                    .filter { it.item.hasPlaceholderTitle && it.handle is PlayHandle.Video }
                val dates = (near + records.map { it.item }).filter { it.needsADate }
                (titles + dates).distinctBy { it.item.id }
            }.distinctUntilChanged().collectLatest { candidates -> repairAll(candidates) }
        }
    }

    private suspend fun repairAll(candidates: List<PlayableItem>) {
        val repaired = HashSet<MediaItemId>()
        while (true) {
            val left = candidates.filter { it.item.id !in repaired && (tries[it.item.id]?.count ?: 0) < it.maxTries }
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
                    failed(item)
                }
            }
        }
    }

    private fun failed(item: PlayableItem) {
        val id = item.item.id
        val what = if (item.item.hasPlaceholderTitle) "title" else "date"
        val count = (tries[id]?.count ?: 0) + 1
        val gap = retryDelayMs * BACK_OFF.pow(count - 1).toLong()
        tries[id] = Tries(count, now() + gap)
        if (count >= item.maxTries) {
            Diag.log("metadata", "${id.value}: no $what after $count tries; not asking YouTube again this session")
        } else {
            Diag.log(
                "metadata",
                "${id.value}: $what still unavailable (try $count of ${item.maxTries}); next in ${gap}ms"
            )
        }
    }

    private suspend fun learnDate(item: PlayableItem): MediaItem? {
        val day = dateOf(item.item.id) ?: return null
        return item.item.copy(publishedAt = day)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun repair(item: PlayableItem): Boolean {
        return try {
            val resolved = if (item.item.hasPlaceholderTitle) {
                resolve(item)?.takeIf { it.id == item.item.id && it.title.isNotBlank() && !it.hasPlaceholderTitle }
            } else {
                learnDate(item)
            }
            currentCoroutineContext().ensureActive()
            if (resolved == null) {
                false
            } else {
                downloads.learnFacts(resolved)
                onResolved(resolved)
                Diag.log(
                    "metadata",
                    "${item.item.id.value}: queue and download learned title=\"${resolved.title}\" " +
                        "published=${resolved.publishedAt ?: "-"}",
                )
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

internal class MetadataLookups(val launcher: VideoPlaybackLauncher, val related: YouTubeRelated)

internal fun startSharedMetadataRepair(
    queue: PlaybackQueue,
    downloads: DownloadManager,
    network: NetworkStatus,
    scope: CoroutineScope,
    lookups: MetadataLookups,
) {
    SharedMetadataRepair(
        queue = queue.state,
        downloads = downloads,
        resolve = { item ->
            (item.handle as? PlayHandle.Video)?.let { lookups.launcher.describe(it.watchUrl, item.item.sourceId) }
        },
        onResolved = queue::adoptFacts,
        awaitNetwork = network::awaitOnline,
        scope = scope,
        dateOf = { id ->
            (lookups.related.relatedTo(id.value) as? RelatedResult.Success)?.publishedOn?.let(PublishedAge::ofDay)
        },
    ).start()
}

internal val MediaItem.saysNoDate: Boolean
    get() = publishedAt == null && publishedText == null && contentKind != MediaContentKind.MUSIC

private val PlayableItem.needsADate: Boolean
    get() = handle is PlayHandle.Video && item.saysNoDate && !item.hasPlaceholderTitle

private val PlayableItem.maxTries: Int get() = if (item.hasPlaceholderTitle) MAX_TRIES else DATE_TRIES

private val NEAR = -5..30
private const val MAX_TRIES = 5
private const val DATE_TRIES = 2
private const val BACK_OFF = 4.0
