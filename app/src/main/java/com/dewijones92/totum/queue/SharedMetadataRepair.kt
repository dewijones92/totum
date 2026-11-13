package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.data.torrent.HomeTorrentServer
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.PublishedAge
import com.dewijones92.totum.domain.TorrentIds
import com.dewijones92.totum.domain.hasPlaceholderTitle
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue
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
    private val artistOf: suspend (PlayableItem) -> String? = { null },
    private val posterOf: suspend (PlayableItem) -> HttpUrl? = { null },
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
                val artists = (snapshot.entries.map { it.item } + records.map { it.item }).filter { it.needsAnArtist }
                val posters = (snapshot.entries.map { it.item } + records.map { it.item }).filter { it.needsAPoster }
                (titles + dates + artists + posters).distinctBy { it.item.id }
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
        val what = item.need.label
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

    private suspend fun learnArtist(item: PlayableItem): MediaItem? {
        val artist = artistOf(item) ?: return null
        return item.item.copy(author = artist)
    }

    private suspend fun learnPoster(item: PlayableItem): MediaItem? {
        val poster = posterOf(item) ?: return null
        Diag.log(
            "metadata",
            "${item.item.id.value}: a poster found by its torrent name \"${item.item.author}\": $poster",
        )
        return item.item.copy(thumbnailUrl = poster)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun repair(item: PlayableItem): Boolean {
        return try {
            val resolved = when (item.need) {
                Need.TITLE ->
                    resolve(item)?.takeIf { it.id == item.item.id && it.title.isNotBlank() && !it.hasPlaceholderTitle }
                Need.ARTIST -> learnArtist(item)
                Need.POSTER -> learnPoster(item)
                Need.DATE -> learnDate(item)
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
                        "published=${resolved.publishedAt ?: "-"} artist=${resolved.author ?: "-"}",
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

internal class MetadataLookups(
    val launcher: VideoPlaybackLauncher,
    val related: YouTubeRelated,
    val music: YouTubeMusicCatalogue,
    val torrents: () -> HomeTorrentServer? = { null },
)

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
        artistOf = ArtistLookup(lookups.music)::of,
        posterOf = { item -> item.item.author?.let { lookups.torrents()?.posterFor(it) } },
    ).start()
}

internal val MediaItem.saysNoDate: Boolean
    get() = publishedAt == null && publishedText == null && contentKind != MediaContentKind.MUSIC

private val PlayableItem.needsADate: Boolean
    get() = handle is PlayHandle.Video && item.saysNoDate && !item.hasPlaceholderTitle

private val MediaItem.saysNoArtist: Boolean
    get() = author == null && (contentKind == MediaContentKind.MUSIC || sourceId.value.startsWith("music:"))

private val PlayableItem.needsAnArtist: Boolean
    get() = item.saysNoArtist && !item.hasPlaceholderTitle

private val PlayableItem.needsAPoster: Boolean
    get() = item.sourceId == TorrentIds.SOURCE && item.thumbnailUrl == null && !item.author.isNullOrBlank()

private enum class Need(val label: String) { TITLE("title"), DATE("date"), ARTIST("artist"), POSTER("poster") }

private val PlayableItem.need: Need
    get() = when {
        item.hasPlaceholderTitle -> Need.TITLE
        needsAnArtist -> Need.ARTIST
        needsAPoster -> Need.POSTER
        else -> Need.DATE
    }

private val PlayableItem.maxTries: Int get() = if (need == Need.TITLE) MAX_TRIES else DATE_TRIES

private val NEAR = -5..30
private const val MAX_TRIES = 5
private const val DATE_TRIES = 2
private const val BACK_OFF = 4.0
