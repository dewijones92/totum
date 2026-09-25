package com.dewijones92.totum.data.channel

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.net.FetchResult
import com.dewijones92.totum.data.net.HttpTextFetcher
import com.dewijones92.totum.domain.MediaItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

public data class CheckedChannel(val channelId: String, val latest: MediaItem?, val checkedAt: Instant)

public interface ChannelLatestStore {
    public fun observe(): Flow<List<CheckedChannel>>

    public suspend fun checkedAt(): Map<String, Instant>

    public suspend fun put(checked: List<CheckedChannel>)
}

public data class ChannelCheckProgress(val checked: Int, val total: Int)

public sealed interface ChannelCheckSummary {
    public data object AlreadyRunning : ChannelCheckSummary

    public data class Done(
        val due: Int,
        val skippedFresh: Int,
        val withUpload: Int,
        val neverUploaded: Int,
        val failed: Int,
        val skippedFailedRecently: Int = 0,
        val decodedChars: Long,
        val elapsedMs: Long,
    ) : ChannelCheckSummary
}

public class ChannelLatestUploads(
    private val fetcher: HttpTextFetcher,
    private val store: ChannelLatestStore,
    private val clock: Clock = Clock.systemUTC(),
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    private val maxAge: Duration = DEFAULT_MAX_AGE,
    private val batchSize: Int = DEFAULT_BATCH,
) {
    private val running = Mutex()
    private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Instant>()
    private val _progress = MutableStateFlow<ChannelCheckProgress?>(null)

    public val progress: StateFlow<ChannelCheckProgress?> = _progress.asStateFlow()

    public fun latest(): Flow<List<MediaItem>> = store.observe().map { rows -> rows.mapNotNull { it.latest } }

    public suspend fun refresh(channelIds: List<String>, force: Boolean = false): ChannelCheckSummary {
        if (!running.tryLock()) {
            Diag.log("subs", "channel check already running — not starting a second")
            return ChannelCheckSummary.AlreadyRunning
        }
        try {
            return check(channelIds.distinct(), force)
        } finally {
            _progress.value = null
            running.unlock()
        }
    }

    private suspend fun check(ids: List<String>, force: Boolean): ChannelCheckSummary.Done {
        val started = clock.millis()
        val now = clock.instant()
        val stale = staleOf(ids, force, now)
        val due = if (force) stale else stale.filterNot { failedRecently(it, now) }
        val known = store.observe().first().associate { it.channelId to it.latest }
        Diag.log(
            "subs",
            "channel check: ${due.size} due of ${ids.size} (force=$force, fresh within $maxAge skipped, " +
                "${stale.size - due.size} failed within $FAILED_RETRY skipped)",
        )
        val done = AtomicInteger()
        val bytes = AtomicLong()
        val failures = mutableListOf<String>()
        val pending = mutableListOf<CheckedChannel>()
        val lock = Mutex()
        _progress.value = ChannelCheckProgress(0, due.size)
        val gate = Semaphore(concurrency)
        val results = coroutineScope {
            due.map { id ->
                async {
                    val outcome = gate.withPermit { fetch(id, bytes, known[id]) }
                    lock.withLock {
                        when (outcome) {
                            is Outcome.Checked -> {
                                pending += outcome.row
                                failedAt.remove(id)
                            }
                            is Outcome.Failed -> {
                                failures += "$id: ${outcome.detail}"
                                failedAt[id] = now
                            }
                        }
                        if (pending.size >= batchSize) flush(pending)
                    }
                    _progress.value = ChannelCheckProgress(done.incrementAndGet(), due.size)
                    outcome
                }
            }.awaitAll()
        }
        lock.withLock { flush(pending) }
        val checked = results.filterIsInstance<Outcome.Checked>()
        val summary = ChannelCheckSummary.Done(
            due = due.size,
            skippedFresh = ids.size - due.size,
            withUpload = checked.count { it.row.latest != null },
            neverUploaded = checked.count { it.row.latest == null },
            failed = failures.size,
            skippedFailedRecently = stale.size - due.size,
            decodedChars = bytes.get(),
            elapsedMs = clock.millis() - started,
        )
        Diag.log("subs", "channel check done: $summary")
        failures.take(FAILURES_LOGGED).forEach { Diag.warn("subs", "channel check failed $it") }
        return summary
    }

    private suspend fun staleOf(ids: List<String>, force: Boolean, now: Instant): List<String> {
        val checkedAt = if (force) emptyMap() else store.checkedAt()
        return ids.filter { id -> checkedAt[id]?.let { Duration.between(it, now) >= maxAge } ?: true }
    }

    private fun failedRecently(id: String, now: Instant): Boolean =
        failedAt[id]?.let { Duration.between(it, now) < FAILED_RETRY } == true

    private suspend fun flush(pending: MutableList<CheckedChannel>) {
        if (pending.isEmpty()) return
        store.put(pending.toList())
        pending.clear()
    }

    private suspend fun fetch(channelId: String, bytes: AtomicLong, known: MediaItem?): Outcome =
        when (val fetched = fetcher.fetch(ChannelFeedParser.feedUrlFor(channelId))) {
            is FetchResult.Failure -> Outcome.Failed(fetched.detail)
            is FetchResult.Success -> {
                bytes.addAndGet(fetched.body.length.toLong())
                val uploads = ChannelFeedParser.uploads(fetched.body)
                if (uploads == null) {
                    Outcome.Failed("not a channel feed")
                } else {
                    val newest = uploads.maxByOrNull { it.publishedAt!! }
                    if (newest == null && known != null) {
                        Diag.log("subs", "$channelId: its feed listed no uploads; keeping the known \"${known.title}\"")
                    }
                    Outcome.Checked(CheckedChannel(channelId, newest ?: known, clock.instant()))
                }
            }
        }

    private sealed interface Outcome {
        data class Checked(val row: CheckedChannel) : Outcome
        data class Failed(val detail: String) : Outcome
    }

    private companion object {
        const val DEFAULT_CONCURRENCY = 6
        const val DEFAULT_BATCH = 50
        const val FAILURES_LOGGED = 3
        val DEFAULT_MAX_AGE: Duration = Duration.ofHours(6)
        val FAILED_RETRY: Duration = Duration.ofMinutes(30)
    }
}

public class InMemoryChannelLatestStore : ChannelLatestStore {
    private val rows = MutableStateFlow<Map<String, CheckedChannel>>(emptyMap())

    override fun observe(): Flow<List<CheckedChannel>> = rows.map { it.values.toList() }

    override suspend fun checkedAt(): Map<String, Instant> = rows.value.mapValues { it.value.checkedAt }

    override suspend fun put(checked: List<CheckedChannel>) {
        rows.value = rows.value + checked.associateBy { it.channelId }
    }
}
