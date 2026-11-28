package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.NextStack
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
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

class PlayNextChainSurvivesARestartTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val launcher = VideoPlaybackLauncher(
        VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
        controller,
        FakeYouTubeWatchHistory(),
        InMemoryPlayHistoryStore(),
    )

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

    private suspend fun saved(): InMemoryQueueStore = InMemoryQueueStore().also {
        it.save(QueueSnapshot(listOf("now", "a", "x").map { id -> QueueEntry(podcast(id)) }, currentIndex = 0))
    }

    private fun queue(store: InMemoryQueueStore) =
        PlaybackQueue(controller, launcher, CoroutineScope(dispatcher), store, clock = { 1_000L })

    @Test
    fun `a restored chain is built on, so the next play next goes after it`() = runTest(dispatcher) {
        val q = queue(saved())
        advanceUntilIdle()

        q.restoreNextStack(NextStack(MediaItemId("a"), MediaItemId("now")))
        q.playNext(podcast("b"))
        advanceUntilIdle()

        assertEquals(listOf("now", "a", "b", "x"), q.state.value.entries.map { it.item.item.id.value })
    }

    @Test
    fun `without a restored chain the next play next goes straight after what plays`() = runTest(dispatcher) {
        val q = queue(saved())
        advanceUntilIdle()

        q.playNext(podcast("b"))
        advanceUntilIdle()

        assertEquals(listOf("now", "b", "a", "x"), q.state.value.entries.map { it.item.item.id.value })
    }

    @Test
    fun `a restored chain that was built behind another item is ignored`() = runTest(dispatcher) {
        val q = queue(saved())
        advanceUntilIdle()

        q.restoreNextStack(NextStack(MediaItemId("a"), MediaItemId("something else")))
        q.playNext(podcast("b"))
        advanceUntilIdle()

        assertEquals(listOf("now", "b", "a", "x"), q.state.value.entries.map { it.item.item.id.value })
    }
}
