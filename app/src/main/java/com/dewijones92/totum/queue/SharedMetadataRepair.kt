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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.EnumMap
import kotlin.coroutines.CoroutineContext
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
    private val io: CoroutineContext = Dispatchers.IO,
    private val batchSize: Int = BATCH_SIZE,
    private val batchGapMs: Long = BATCH_GAP_MS,
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
            runPass(due, candidates.size, repaired)
        }
    }

    private suspend fun runPass(due: List<PlayableItem>, candidates: Int, repaired: MutableSet<MediaItemId>) {
        val tally = PassTally(due.size, candidates)
        val posters = HashMap<String, Result<HttpUrl?>>()
        val started = now()
        var finished = false
        try {
            due.chunked(batchSize).forEachIndexed { index, batch ->
                if (index > 0) delay(batchGapMs)
                awaitNetwork()
                batch.forEach { item ->
                    if (repair(item, posters, tally)) {
                        repaired += item.item.id
                        tries.remove(item.item.id)
                    } else {
                        failed(item, tally)
                    }
                }
                tally.batches++
            }
            finished = true
        } finally {
            Diag.log("metadata", tally.summary(finished, now() - started, batchSize, batchGapMs))
        }
    }

    private fun failed(item: PlayableItem, tally: PassTally) {
        val id = item.item.id
        val count = (tries[id]?.count ?: 0) + 1
        val gap = retryDelayMs * BACK_OFF.pow(count - 1).toLong()
        tries[id] = Tries(count, now() + gap)
        tally.askedAgain(giveUp = count >= item.maxTries, gapMs = gap)
    }

    private suspend fun <T> off(block: suspend () -> T): T = withContext(io) { block() }

    private suspend fun learnDate(item: PlayableItem): MediaItem? {
        val day = off { dateOf(item.item.id) } ?: return null
        return item.item.copy(publishedAt = day)
    }

    private suspend fun learnArtist(item: PlayableItem): MediaItem? {
        val artist = off { artistOf(item) } ?: return null
        return item.item.copy(author = artist)
    }

    private suspend fun learnPoster(
        item: PlayableItem,
        posters: MutableMap<String, Result<HttpUrl?>>,
        tally: PassTally,
    ): MediaItem? {
        val torrentName = item.item.author.orEmpty()
        val known = posters[torrentName]
        val answer = if (known != null) {
            tally.posterReused++
            known
        } else {
            tally.posterLookups++
            lookUpPoster(item).also { posters[torrentName] = it }
        }
        val poster = answer.getOrThrow() ?: return null
        return item.item.copy(thumbnailUrl = poster)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun lookUpPoster(item: PlayableItem): Result<HttpUrl?> = try {
        Result.success(off { posterOf(item) })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun repair(
        item: PlayableItem,
        posters: MutableMap<String, Result<HttpUrl?>>,
        tally: PassTally,
    ): Boolean {
        return try {
            val resolved = when (item.need) {
                Need.TITLE -> off { resolve(item) }
                    ?.takeIf { it.id == item.item.id && it.title.isNotBlank() && !it.hasPlaceholderTitle }
                Need.ARTIST -> learnArtist(item)
                Need.POSTER -> learnPoster(item, posters, tally)
                Need.DATE -> learnDate(item)
            }
            currentCoroutineContext().ensureActive()
            if (resolved == null) {
                tally.none(item.need)
                false
            } else {
                downloads.learnFacts(resolved)
                onResolved(resolved)
                tally.learned(item.need, resolved)
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (tally.failed(item.need, failure)) {
                Diag.warn(
                    "metadata",
                    "${item.item.id.value}: metadata repair failed; the first of its pass, the rest are counted",
                    failure,
                )
            }
            false
        }
    }
}

private class PassTally(private val due: Int, private val candidates: Int) {
    private val repaired = EnumMap<Need, Int>(Need::class.java)
    private val nothing = EnumMap<Need, Int>(Need::class.java)
    private val broken = EnumMap<Need, Int>(Need::class.java)
    private val reasons = LinkedHashMap<String, Int>()
    private val examples = ArrayList<String>()
    private var gaveUp = 0
    private var retrying = 0
    private var nextRetryMs: Long? = null

    var posterLookups = 0
    var posterReused = 0
    var batches = 0

    fun learned(need: Need, resolved: MediaItem) {
        repaired.merge(need, 1, Int::plus)
        if (examples.size < EXAMPLES) {
            examples += "${resolved.id.value} learned ${need.label}"
        }
    }

    fun none(need: Need) {
        nothing.merge(need, 1, Int::plus)
    }

    fun failed(need: Need, failure: Exception): Boolean {
        broken.merge(need, 1, Int::plus)
        val first = reasons.isEmpty()
        reasons.merge(failure.javaClass.simpleName, 1, Int::plus)
        return first
    }

    fun askedAgain(giveUp: Boolean, gapMs: Long) {
        if (giveUp) {
            gaveUp++
        } else {
            retrying++
            nextRetryMs = minOf(nextRetryMs ?: gapMs, gapMs)
        }
    }

    fun summary(finished: Boolean, tookMs: Long, batchSize: Int, batchGapMs: Long): String {
        val byNeed = Need.entries.mapNotNull { need ->
            val r = repaired[need] ?: 0
            val n = nothing[need] ?: 0
            val f = broken[need] ?: 0
            if (r + n + f == 0) null else "${need.label} repaired $r none $n failed $f"
        }
        return buildString {
            append("pass: due=$due of $candidates candidate(s) repaired=${repaired.values.sum()} ")
            append("none=${nothing.values.sum()} failed=${broken.values.sum()} ")
            append("[${byNeed.joinToString("; ")}] ")
            append("poster lookups=$posterLookups reused=$posterReused ")
            append("gave up=$gaveUp retrying=$retrying")
            nextRetryMs?.let { append(" (next in ${it}ms)") }
            append(" reasons=$reasons ")
            append("batches=$batches of $batchSize every ${batchGapMs}ms in ${tookMs}ms")
            if (!finished) append(" INTERRUPTED before every row was tried")
            if (examples.isNotEmpty()) append("; first: ${examples.joinToString(", ")}")
        }
    }

    private companion object {
        const val EXAMPLES = 3
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
    get() = item.sourceId == TorrentIds.SOURCE && item.contentKind != MediaContentKind.AUDIOBOOK &&
        item.thumbnailUrl == null && !item.author.isNullOrBlank()

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
private const val BATCH_SIZE = 20
private const val BATCH_GAP_MS = 1_000L
