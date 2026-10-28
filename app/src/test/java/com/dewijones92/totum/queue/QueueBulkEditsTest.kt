package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.data.queue.QueueStore
import com.dewijones92.totum.data.queue.fake.InMemoryQueueStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueBulkEditsTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val launcher = VideoPlaybackLauncher(
        VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
        controller,
        FakeYouTubeWatchHistory(),
        InMemoryPlayHistoryStore(),
    )

    private fun queue(withStore: QueueStore) = PlaybackQueue(
        controller,
        launcher,
        CoroutineScope(dispatcher),
        withStore,
        clock = { 1_000L },
    )

    @Test
    fun `bulk add and bulk play next land exactly where adding one at a time would`() = runTest(dispatcher) {
        val random = kotlin.random.Random(7)
        repeat(200) { round ->
            val pool = (0 until 12).map { podcast("p$it") }
            val start = pool.shuffled(random).take(random.nextInt(0, 10))
            val playing = if (start.isEmpty() || random.nextBoolean()) -1 else random.nextInt(start.size)
            val seed = QueueSnapshot(start.map { QueueEntry(it) }, playing)
            val batch = List(random.nextInt(0, 8)) { pool.random(random) }
            val group = QueueGroup("g$round", "G").takeIf { random.nextBoolean() }
            val bulk = queue(InMemoryQueueStore().also { it.save(seed) })
            val single = queue(InMemoryQueueStore().also { it.save(seed) })
            advanceUntilIdle()
            if (random.nextBoolean()) {
                bulk.enqueueAll(batch, group)
                batch.forEach { single.enqueue(it, group) }
            } else {
                bulk.playNextAll(batch)
                batch.asReversed().forEach { single.playNext(it) }
            }
            advanceUntilIdle()
            assertEquals("round $round: $seed then $batch", single.state.value.entries, bulk.state.value.entries)
            assertEquals("round $round cursor", single.state.value.currentIndex, bulk.state.value.currentIndex)
        }
    }

    @Test
    fun `removing or moving a selection in one step matches doing it one item at a time`() = runTest(dispatcher) {
        val random = kotlin.random.Random(11)
        repeat(200) { round ->
            val start = (0 until random.nextInt(1, 12)).map { podcast("m$it") }
            val playing = if (random.nextBoolean()) -1 else random.nextInt(start.size)
            val seed = QueueSnapshot(start.map { QueueEntry(it) }, playing)
            val chosen = start.shuffled(random).take(random.nextInt(0, start.size + 1)).map { it.item.id }
            val bulk = queue(InMemoryQueueStore().also { it.save(seed) })
            val single = queue(InMemoryQueueStore().also { it.save(seed) })
            advanceUntilIdle()
            fun indexOf(id: MediaItemId) = single.state.value.entries.indexOfFirst { it.item.item.id == id }
            when (random.nextInt(3)) {
                0 -> {
                    bulk.removeAll(chosen.toSet())
                    chosen.forEach { id ->
                        single.state.value.entries.firstOrNull { it.item.item.id == id }?.let(
                            single::remove
                        )
                    }
                }
                1 -> {
                    bulk.moveAll(chosen, toTop = true)
                    chosen.asReversed().forEach { id -> indexOf(id).takeIf { it > 0 }?.let { single.move(it, 0) } }
                }
                else -> {
                    bulk.moveAll(chosen, toTop = false)
                    chosen.forEach { id ->
                        val last = single.state.value.entries.lastIndex
                        indexOf(id).takeIf { it in 0 until last }?.let { single.move(it, last) }
                    }
                }
            }
            advanceUntilIdle()
            assertEquals("round $round: $seed with $chosen", single.state.value.entries, bulk.state.value.entries)
            assertEquals("round $round cursor", single.state.value.currentIndex, bulk.state.value.currentIndex)
        }
    }

    private fun podcast(id: String) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("feed"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://feeds.example.com/$id.mp3"),
        ),
        PlayHandle.Podcast(),
    )
}
