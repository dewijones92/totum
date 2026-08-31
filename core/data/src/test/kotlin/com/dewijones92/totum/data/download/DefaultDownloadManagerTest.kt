package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.asPlayable
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

    /**
     * A download the process died during must be left VISIBLE and retryable, not vanish.
     *
     * It used to be deleted outright, on the reasoning that its coroutine was gone so the row would
     * only show a stuck spinner. True, but the consequence is worse: the row disappears, the partial
     * file becomes bytes nothing in the app points at, and the person who asked for the download is
     * told nothing — a download that silently un-happens every time Android reclaims the process,
     * which for a backgrounded app is often. Recorded as failed instead, so Library offers Retry and
     * the queue's automatic pass asks again.
     */
    @Test
    fun `a download the app died during is left failed, so it can be retried`() = runTest {
        store.put(item.asPlayable(), DownloadState.Downloading(500, 1000), audioOnly = true)

        // Unconfined so the manager's init sweep runs eagerly at construction.
        manager(DownloadStrategy { _, _, _ -> flowOf() }, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()

        val state = store.get(item.id)
        assertTrue("an interrupted download must be reported, not dropped. Got: $state", state is DownloadState.Failed)
        assertFalse(
            "and it must stay retryable — the app stopping says nothing about the media",
            (state as DownloadState.Failed).isPermanent,
        )
        assertEquals(
            "what was asked for must survive too, or the retry fetches the wrong variant",
            true,
            store.request(item.id)?.audioOnly,
        )
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
    private val states = MutableStateFlow<Map<MediaItemId, PlayableAndState>>(emptyMap())

    override fun observeAll(): Flow<Map<MediaItemId, DownloadState>> =
        states.map { rows -> rows.mapValues { (_, row) -> row.state } }

    override fun observeDownloaded(): Flow<List<DownloadedMedia>> = states.map { rows ->
        rows.values.mapNotNull { row ->
            (row.state as? DownloadState.Downloaded)?.let {
                DownloadedMedia(row.item, it.localPath, it.audioOnly)
            }
        }
    }

    override suspend fun put(item: PlayableItem, state: DownloadState, audioOnly: Boolean) =
        states.update { it + (item.item.id to PlayableAndState(item, state, audioOnly)) }

    override fun observeRecords(): Flow<List<DownloadRecord>> =
        states.map { rows -> rows.values.map { DownloadRecord(it.item, it.state) } }

    override suspend fun request(id: MediaItemId): DownloadRequest? =
        states.value[id]?.let { DownloadRequest(it.item, it.audioOnly) }

    override suspend fun get(id: MediaItemId): DownloadState =
        states.value[id]?.state ?: DownloadState.NotDownloaded

    override suspend fun remove(id: MediaItemId) { states.update { it - id } }
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
