package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SkipSegment
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.asPlayable
import com.dewijones92.totum.domain.fillingSilenceFrom
import com.dewijones92.totum.domain.isPermanent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DefaultDownloadManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val item = MediaItem(
        id = MediaItemId("ep-1"),
        sourceId = SourceId("feed-1"),
        title = "Episode",
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://cdn.example.com/ep1.mp3"),
    )

    private val store = InMemoryDownloadStore()

    private fun manager(strategy: DownloadStrategy, scope: kotlinx.coroutines.CoroutineScope) =
        DefaultDownloadManager(tempFolder.root, store, strategy, scope)

    @Test
    fun `a finished download is handed on so its segments can be stored straight away`() = runTest {
        val finished = mutableListOf<PlayableItem>()
        val strategy = DownloadStrategy { _, target, _ -> flowOf(DownloadState.Downloaded(target.absolutePath)) }
        val manager =
            DefaultDownloadManager(tempFolder.root, store, strategy, backgroundScope, onDownloaded = { finished += it })

        manager.download(item)
        store.observeAll().map { it[item.id] }.first { it is DownloadState.Downloaded }
        advanceUntilIdle()

        assertEquals(listOf(item.id), finished.map { it.item.id })
    }

    @Test
    fun `a burst of progress is written at most once a second, and the finish always`() = runTest {
        val strategy = DownloadStrategy { _, target, _ ->
            flow {
                repeat(BURST) { emit(DownloadState.Downloading(it.toLong(), BURST.toLong())) }
                delay(1_500)
                emit(DownloadState.Downloading(BURST.toLong(), BURST.toLong()))
                emit(DownloadState.Downloaded(target.absolutePath))
            }
        }
        val manager = DefaultDownloadManager(tempFolder.root, store, strategy, backgroundScope, {}) {
            testScheduler.currentTime
        }

        manager.download(item)
        store.observeAll().map { it[item.id] }.first { it is DownloadState.Downloaded }

        val progress = store.puts.filterIsInstance<DownloadState.Downloading>()
        assertTrue("${progress.size} progress writes for $BURST ticks", progress.size <= 4)
        assertEquals(DownloadState.Downloading(BURST.toLong(), BURST.toLong()), progress.last())
        assertTrue(store.puts.last() is DownloadState.Downloaded)
    }

    @Test
    fun `no more than four downloads run at once, whoever asked for them`() = runTest {
        val running = MutableStateFlow(0)
        var most = 0
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val strategy = DownloadStrategy { _, target, _ ->
            flow {
                running.update { it + 1 }
                most = maxOf(most, running.value)
                release.await()
                running.update { it - 1 }
                emit(DownloadState.Downloaded(target.absolutePath))
            }
        }
        val manager = manager(strategy, backgroundScope)

        (1..6).forEach { manager.download(item.copy(id = MediaItemId("ep-$it"))) }
        running.first { it >= 4 }
        delay(1_000)
        assertEquals(4, running.value)

        release.complete(Unit)
        store.observeAll().first { states -> states.values.count { it is DownloadState.Downloaded } == 6 }
        assertEquals(4, most)
    }

    @Test
    fun `download records progress then completion`() = runTest {
        val strategy = DownloadStrategy { _, target, _ ->
            flowOf(
                DownloadState.Downloading(500, 1000),
                DownloadState.Downloaded(target.absolutePath),
            )
        }

        manager(strategy, backgroundScope).download(item)

        val finalState = store.observeAll().map { it[item.id] }.first { it is DownloadState.Downloaded }
        assertTrue(finalState is DownloadState.Downloaded)
    }

    @Test
    fun `already-downloaded item is not re-downloaded`() = runTest {
        store.put(item.asPlayable(), DownloadState.Downloaded("/somewhere.media"), audioOnly = false)
        var called = false
        val strategy = DownloadStrategy { _, _, _ ->
            called = true
            flowOf()
        }

        manager(strategy, backgroundScope).download(item)

        assertFalse(called)
    }

    @Test
    fun `a download the app died during starts again by itself, as it was asked for`() = runTest {
        val asked = mutableListOf<Pair<Boolean, Int?>>()
        val strategy = object : DownloadStrategy {
            override fun download(item: PlayableItem, target: java.io.File, audioOnly: Boolean) =
                download(item, target, audioOnly, null)

            override fun download(item: PlayableItem, target: java.io.File, audioOnly: Boolean, maxHeight: Int?) =
                flowOf<DownloadState>(
                    DownloadState.Downloaded(target.absolutePath)
                ).also { asked += audioOnly to maxHeight }
        }
        manager(strategy, backgroundScope).download(item.asPlayable(), audioOnly = true, maxHeight = 720)
        advanceUntilIdle()
        asked.clear()
        store.put(item.asPlayable(), DownloadState.Downloading(500, 1000), audioOnly = true)

        manager(strategy, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()

        assertEquals(listOf(true to 720), asked)
        assertTrue(store.get(item.id) is DownloadState.Downloaded)
    }

    @Test
    fun `a download that keeps dying with the app is left failed after two fresh starts`() = runTest {
        repeat(3) {
            store.put(item.asPlayable(), DownloadState.Downloading(500, 1000), audioOnly = true)
            manager(
                DownloadStrategy { _, _, _ -> kotlinx.coroutines.flow.flow { kotlinx.coroutines.awaitCancellation() } },
                CoroutineScope(UnconfinedTestDispatcher(testScheduler) + kotlinx.coroutines.Job()),
            )
            advanceTimeBy(1_000)
        }

        val state = store.get(item.id)
        assertTrue("got $state", state is DownloadState.Failed)
        assertFalse((state as DownloadState.Failed).isPermanent)
        assertEquals(true, store.request(item.id)?.audioOnly)
    }

    /**
     * Two callers asking for the SAME item at the same moment start one download, not two.
     *
     * Check-then-act across a suspension point: `download` asked the STORE whether the item was
     * already in flight, and the store is Room, so the answer arrives on another thread some
     * milliseconds later. Two callers arriving inside that window both read "not downloading", both
     * claim the item, and two coroutines then write the same file — a corrupt download for twice
     * the data.
     *
     * Caught on a device on 2026-08-31, within a minute of downloads first running in parallel:
     * three queued videos produced six `start` lines and six extractions. It was reachable before
     * that too — two queue changes in quick succession, or several URLs shared into the app one
     * after another — just far less likely.
     *
     * Counted at the CLAIM (the first `Downloading` row written), not at the strategy: that is the
     * moment the item is taken, it is what the device log showed twice per item, and it does not
     * depend on how a test scheduler happens to interleave the jobs afterwards.
     */
    @Test
    fun `two callers asking for the same item at once start one download`() = runTest {
        val strategy = DownloadStrategy { _, _, _ ->
            flow {
                emit(DownloadState.Downloading(1, 100))
                awaitCancellation()
            }
        }
        val slow = SlowToCommit(store)
        val manager = DefaultDownloadManager(tempFolder.root, slow, strategy, backgroundScope)

        listOf(
            backgroundScope.launch { manager.download(item) },
            backgroundScope.launch { manager.download(item) },
        ).joinAll()
        advanceUntilIdle()

        assertEquals("the same item was claimed twice, so it is being fetched twice", 1, slow.claims)
    }

    @Test
    fun `delete removes the file and record`() = runTest {
        val file = tempFolder.newFile("dl.media").apply { writeText("data") }
        store.put(item.asPlayable(), DownloadState.Downloaded(file.absolutePath), audioOnly = false)

        manager(DownloadStrategy { _, _, _ -> flowOf() }, backgroundScope).delete(item.id)

        assertFalse(file.exists())
        assertEquals(DownloadState.NotDownloaded, store.get(item.id))
    }

    @Test
    fun `an audio-only download does not satisfy a later request for the full media`() = runTest {
        val requested = mutableListOf<Boolean>()
        val manager = manager(
            DownloadStrategy { _, target, audioOnly ->
                requested.add(audioOnly)
                flowOf(DownloadState.Downloaded(target.path, audioOnly = audioOnly))
            },
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )

        manager.download(item, audioOnly = true)
        advanceUntilIdle()
        manager.download(item, audioOnly = false)
        advanceUntilIdle()

        // Both ran: the queue's audio grab must not make "Download" look done.
        assertEquals(listOf(true, false), requested)
        assertEquals(false, (manager.observe(item.id).first() as DownloadState.Downloaded).audioOnly)
    }

    @Test
    fun `a full download satisfies a later audio-only request`() = runTest {
        val requested = mutableListOf<Boolean>()
        val manager = manager(
            DownloadStrategy { _, target, audioOnly ->
                requested.add(audioOnly)
                flowOf(DownloadState.Downloaded(target.path, audioOnly = audioOnly))
            },
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )

        manager.download(item, audioOnly = false)
        advanceUntilIdle()
        manager.download(item, audioOnly = true)
        advanceUntilIdle()

        assertEquals(listOf(false), requested)
    }
}

/** Shared by the download tests in this package; the real one is Room, which needs a device. */
internal class InMemoryDownloadStore : DownloadStore {
    override suspend fun learnFacts(resolved: MediaItem) {
        states.update { rows ->
            rows.mapValues { (id, row) ->
                if (id == resolved.id) {
                    row.copy(item = row.item.copy(item = row.item.item.fillingSilenceFrom(resolved)))
                } else {
                    row
                }
            }
        }
    }
    private val states = MutableStateFlow<Map<MediaItemId, PlayableAndState>>(emptyMap())

    override fun observeAll(): Flow<Map<MediaItemId, DownloadState>> =
        states.map { rows -> rows.mapValues { (_, row) -> row.state } }

    override fun observeDownloaded(): Flow<List<DownloadedMedia>> = states.map { rows ->
        rows.values.mapNotNull { row ->
            (row.state as? DownloadState.Downloaded)?.let {
                DownloadedMedia(
                    row.item,
                    it.localPath,
                    it.audioOnly,
                    it.sponsorSegmentsCut,
                    segments.value[row.item.item.id].orEmpty(),
                )
            }
        }
    }

    val puts = mutableListOf<DownloadState>()

    override suspend fun put(item: PlayableItem, state: DownloadState, audioOnly: Boolean) {
        puts += state
        states.update { it + (item.item.id to PlayableAndState(item, state, audioOnly)) }
    }

    override fun observeRecords(): Flow<List<DownloadRecord>> =
        states.map { rows -> rows.values.map { DownloadRecord(it.item, it.state) } }

    override suspend fun request(id: MediaItemId): DownloadRequest? =
        states.value[id]?.let { DownloadRequest(it.item, it.audioOnly) }

    override suspend fun get(id: MediaItemId): DownloadState =
        states.value[id]?.state ?: DownloadState.NotDownloaded

    override suspend fun remove(id: MediaItemId) { states.update { it - id } }

    override suspend fun noteOpened(id: MediaItemId): Boolean = states.value.containsKey(id)

    private val segments = MutableStateFlow<Map<MediaItemId, List<SkipSegment>>>(emptyMap())

    override suspend fun rememberSkipSegments(id: MediaItemId, segments: List<SkipSegment>) =
        this.segments.update { it + (id to segments) }
}

/**
 * A store whose writes land a moment after they are made, as Room's do.
 *
 * [InMemoryDownloadStore] commits synchronously, which quietly closes the exact window this class
 * exists to open: with it, a check-then-act race is unreachable and a test for one passes against
 * broken code.
 */
private class SlowToCommit(private val delegate: DownloadStore) : DownloadStore by delegate {

    /** How many times a caller got far enough to claim the item by writing its first row. */
    var claims: Int = 0
        private set

    override suspend fun put(item: PlayableItem, state: DownloadState, audioOnly: Boolean) {
        if (state == DownloadState.Downloading(0, null)) claims++
        delay(1)
        delegate.put(item, state, audioOnly)
    }

    override suspend fun get(id: MediaItemId): DownloadState {
        delay(1)
        return delegate.get(id)
    }
}

private data class PlayableAndState(
    val item: PlayableItem,
    val state: DownloadState,
    val audioOnly: Boolean = false,
)

private const val BURST = 1_000
