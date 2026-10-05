package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.placeholderTitleFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedMetadataRepairTest {
    private val id = MediaItemId("4kKd9-vCPts")
    private val url = HttpUrl.of("https://www.youtube.com/watch?v=${id.value}")
    private val placeholder = PlayableItem(
        MediaItem(
            id = id,
            sourceId = SourceId("shared"),
            title = placeholderTitleFor(id),
            publishedAt = null,
            duration = null,
            mediaUrl = url,
        ),
        PlayHandle.Video(url),
    )
    private val resolved = placeholder.item.copy(title = "A real title", author = "The channel")

    @Test
    fun `a downloaded placeholder learns its title after the network returns`() = runTest {
        val downloads = FakeDownloadManager()
        downloads.download(placeholder, audioOnly = true)
        val records = downloads.observeRecords().stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
        val queue = MutableStateFlow(QueueSnapshot(entries = listOf(QueueEntry(placeholder))))
        val online = CompletableDeferred<Unit>()
        val learned = mutableListOf<MediaItem>()
        var calls = 0
        SharedMetadataRepair(queue, downloads, {
            calls++
            resolved
        }, learned::add, { online.await() }, backgroundScope)
            .start()
        runCurrent()
        assertEquals(0, calls)
        online.complete(Unit)
        runCurrent()

        assertEquals(listOf(resolved), learned)
        val copy = downloads.observeDownloaded().first().single()
        assertEquals("A real title", copy.item.title)
        assertEquals("The channel", copy.item.author)
        assertEquals(resolved.title, records.value.single().item.item.title)
        assertTrue(copy.audioOnly)
        assertEquals(1, downloads.requested.size)
    }

    @Test
    fun `a failed metadata lookup retries without a queue edit`() = runTest {
        val downloads = FakeDownloadManager()
        val queue = MutableStateFlow(QueueSnapshot(entries = listOf(QueueEntry(placeholder))))
        val learned = mutableListOf<MediaItem>()
        var calls = 0
        SharedMetadataRepair(
            queue,
            downloads,
            { if (++calls == 1) null else resolved },
            learned::add,
            {},
            backgroundScope,
            retryDelayMs = 1_000,
        ).start()
        runCurrent()
        assertTrue(learned.isEmpty())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(resolved), learned)
        assertEquals(2, calls)
    }

    @Test
    fun `a placeholder held only in the library is repaired too`() = runTest {
        val downloads = FakeDownloadManager()
        downloads.download(placeholder, audioOnly = true)
        SharedMetadataRepair(
            MutableStateFlow(QueueSnapshot()),
            downloads,
            { resolved },
            {},
            {},
            backgroundScope,
        ).start()
        runCurrent()

        assertEquals(resolved.title, downloads.observeDownloaded().first().single().item.title)
    }

    @Test
    fun `removing a placeholder cancels its pending metadata lookup`() = runTest {
        val queue = MutableStateFlow(QueueSnapshot(entries = listOf(QueueEntry(placeholder))))
        val lookup = CompletableDeferred<MediaItem>()
        val learned = mutableListOf<MediaItem>()
        SharedMetadataRepair(
            queue,
            FakeDownloadManager(),
            { withContext(NonCancellable) { lookup.await() } },
            learned::add,
            {},
            backgroundScope
        ).start()
        runCurrent()
        queue.value = QueueSnapshot()
        runCurrent()
        lookup.complete(resolved)
        runCurrent()

        assertTrue(learned.isEmpty())
    }
}
