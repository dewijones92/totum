package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.asPlayable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StrandedDownloadsTest {

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

    private fun manager(strategy: DownloadStrategy, scope: CoroutineScope, mayResumeNow: () -> Boolean = { true }) =
        DefaultDownloadManager(tempFolder.root, store, strategy, scope, mayResumeNow = mayResumeNow)

    @Test
    fun `a downloading row with nothing running it is started again, as it was asked for`() = runTest {
        val asked = mutableListOf<Boolean>()
        val strategy = DownloadStrategy { _, target, audioOnly ->
            asked += audioOnly
            flowOf(DownloadState.Downloaded(target.absolutePath, audioOnly = audioOnly))
        }
        val manager = manager(strategy, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        store.put(item.asPlayable(), DownloadState.Downloading(0, null), audioOnly = true)

        val found = manager.recoverStranded()
        advanceUntilIdle()

        assertEquals(listOf(item.id), found.restarted)
        assertEquals(listOf(true), asked)
        assertTrue(store.get(item.id) is DownloadState.Downloaded)
    }

    @Test
    fun `a download that is really running is left alone`() = runTest {
        var starts = 0
        val strategy = DownloadStrategy { _, _, _ ->
            starts++
            flow {
                emit(DownloadState.Downloading(1, 100))
                awaitCancellation()
            }
        }
        val manager = manager(strategy, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        manager.download(item)
        store.observeAll().map { it[item.id] }.first { it is DownloadState.Downloading }

        val found = manager.recoverStranded()

        assertEquals(StrandedDownloads.NONE, found)
        assertEquals(1, starts)
    }

    @Test
    fun `two recoveries at once start it once`() = runTest {
        var starts = 0
        val strategy = DownloadStrategy { _, _, _ ->
            starts++
            flow {
                emit(DownloadState.Downloading(1, 100))
                awaitCancellation()
            }
        }
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = manager(strategy, scope)
        store.put(item.asPlayable(), DownloadState.Downloading(0, null), audioOnly = true)
        val found = mutableListOf<StrandedDownloads>()

        listOf(
            scope.launch { found += manager.recoverStranded() },
            scope.launch { found += manager.recoverStranded() },
        ).joinAll()

        assertEquals(1, starts)
        assertEquals(1, found.sumOf { it.restarted.size })
    }

    @Test
    fun `a download being cancelled is not stranded while it winds down`() = runTest {
        var starts = 0
        val running = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val strategy = DownloadStrategy { _, _, _ ->
            starts++
            flow {
                emit(DownloadState.Downloading(1, 100))
                running.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { release.await() }
                }
            }
        }
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = manager(strategy, scope)
        manager.download(item)
        running.await()
        val cancelling = scope.launch { manager.cancel(item.id) }

        val found = manager.recoverStranded()
        release.complete(Unit)
        cancelling.join()

        assertEquals(StrandedDownloads.NONE, found)
        assertEquals(1, starts)
    }

    @Test
    fun `a metered network leaves a stranded download failed for Retry`() = runTest {
        var starts = 0
        val strategy = DownloadStrategy { _, _, _ -> flowOf<DownloadState>().also { starts++ } }
        val manager = manager(strategy, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), { false })
        store.put(item.asPlayable(), DownloadState.Downloading(0, null), audioOnly = true)

        val found = manager.recoverStranded()

        assertEquals(listOf(item.id), found.failed)
        assertEquals(0, starts)
        assertTrue(store.get(item.id) is DownloadState.Failed)
    }
}
