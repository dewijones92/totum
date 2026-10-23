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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeBatchProgressTest {

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

    private val ask = EpisodeDownloads.Ask("Peppa", listOf(video("aaaaaaaaaaa"), video("bbbbbbbbbbb")))
    private val everything = ask.items.mapTo(mutableSetOf()) { it.item.id }

    @Test
    fun `a batch shows on the strip until every episode is on the phone, then says how many`() = runTest {
        val operations = Operations(clock = { 0L })
        val ended = mutableListOf<Ended>()
        val collector = launch { operations.ended.collect { ended += it } }
        testScheduler.advanceUntilIdle()

        EpisodeDownloads(FakeDownloadManager(), this, operations).start(ask, everything, DownloadChoice.Sound)
        testScheduler.advanceUntilIdle()

        assertTrue(operations.running.value.isEmpty())
        assertEquals(OperationKind.DOWNLOAD_BATCH, ended.single().kind)
        assertEquals(OperationEnd.Done(2), ended.single().end)
        collector.cancel()
    }

    @Test
    fun `cancel from the strip stops the episodes still downloading`() = runTest {
        val fake = FakeDownloadManager()
        val cancelled = mutableListOf<MediaItemId>()
        val slow = object : DownloadManager by fake {
            override suspend fun download(item: PlayableItem, audioOnly: Boolean, maxHeight: Int?) =
                fake.setDownloading(item.item.id, 0, null)

            override suspend fun cancel(id: MediaItemId) {
                cancelled += id
            }
        }
        val operations = Operations(clock = { 0L })
        val ended = mutableListOf<Ended>()
        val collector = launch { operations.ended.collect { ended += it } }
        testScheduler.advanceUntilIdle()

        EpisodeDownloads(slow, this, operations).start(ask, everything, DownloadChoice.Sound)
        testScheduler.advanceUntilIdle()
        val live = operations.running.value.single()
        assertEquals(listOf(0, 2), listOf(live.step, live.steps))

        operations.cancel(live.id)
        testScheduler.advanceUntilIdle()

        assertEquals(everything, cancelled.toSet())
        assertEquals(OperationEnd.Cancelled(playing = false), ended.single().end)
        collector.cancel()
    }
}
