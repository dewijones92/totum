package com.dewijones92.totum.downloads

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.operations.Ended
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.settings.DownloadChoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeBatchEndTest {

    private fun video(id: String) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("s"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://example.test/$id"),
        ),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
    )

    private val items = listOf(video("aaaaaaaaaaa"), video("bbbbbbbbbbb"), video("ccccccccccc"))

    private fun failing(fake: FakeDownloadManager, vararg ids: String) = object : DownloadManager by fake {
        override suspend fun download(item: PlayableItem, audioOnly: Boolean, maxHeight: Int?) {
            if (item.item.id.value in ids) {
                fake.know(item)
                fake.setFailed(item.item.id, "boom")
            } else {
                fake.download(item, audioOnly, maxHeight)
            }
        }
    }

    private fun kotlinx.coroutines.test.TestScope.endsOf(operations: Operations): List<Ended> {
        val ended = mutableListOf<Ended>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { operations.ended.collect { ended += it } }
        return ended
    }

    @Test
    fun `a batch where some failed ends done but partial, counting only what arrived`() = runTest {
        val operations = Operations(clock = { 0L })
        val ended = endsOf(operations)

        EpisodeDownloads(failing(FakeDownloadManager(), "bbbbbbbbbbb"), this, operations)
            .fetchBatch("Trip", items, DownloadChoice.Sound)
        advanceUntilIdle()

        assertEquals(OperationKind.DOWNLOAD_BATCH, ended.single().kind)
        assertEquals(OperationEnd.Done(2, partial = true), ended.single().end)
    }

    @Test
    fun `a batch where every one failed says nothing was downloaded`() = runTest {
        val operations = Operations(clock = { 0L })
        val ended = endsOf(operations)

        EpisodeDownloads(failing(FakeDownloadManager(), "aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), this, operations)
            .fetchBatch("Trip", items, DownloadChoice.Sound)
        advanceUntilIdle()

        assertEquals(OperationEnd.Nothing, ended.single().end)
    }

    @Test
    fun `a batch is tracked under its title and goes at the chosen quality`() = runTest {
        val fake = FakeDownloadManager()
        val operations = Operations(clock = { 0L })
        val ended = endsOf(operations)

        EpisodeDownloads(fake, this, operations).fetchBatch("Trip", items, DownloadChoice.Video(480))
        advanceUntilIdle()

        assertEquals(listOf(480, 480, 480), fake.requestedHeights)
        assertEquals(OperationEnd.Done(3), ended.single().end)
        assertTrue(operations.running.value.isEmpty())
    }
}
