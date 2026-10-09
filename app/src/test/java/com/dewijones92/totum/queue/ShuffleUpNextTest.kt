package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.QueueGroup
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class ShuffleUpNextTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val queue = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
    )

    private fun episode(id: String) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("feed"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://feed.test/$id.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    private fun ids() = queue.state.value.entries.map { it.item.item.id.value }

    @Test
    fun `shuffling keeps what is playing and everything before it, and reorders only what is next`() = runTest(
        dispatcher
    ) {
        (1..10).forEach { queue.enqueue(episode("e$it")) }
        queue.jumpTo(2)
        advanceUntilIdle()

        queue.shuffleUpNext(Random(SEED))

        val after = ids()
        assertEquals(listOf("e1", "e2", "e3"), after.take(3))
        assertEquals("e3", queue.state.value.current?.item?.item?.id?.value)
        assertEquals((4..10).map { "e$it" }.toSet(), after.drop(3).toSet())
        assertNotEquals((4..10).map { "e$it" }, after.drop(3))
    }

    @Test
    fun `items keep the group they arrived with`() = runTest(dispatcher) {
        queue.enqueue(episode("now"))
        queue.enqueueAll((1..6).map { episode("a$it") }, QueueGroup("album:x", "An album"))
        queue.jumpTo(0)
        advanceUntilIdle()

        queue.shuffleUpNext(Random(SEED))

        assertEquals(List(6) { "album:x" }, queue.state.value.entries.drop(1).map { it.group?.id })
    }

    @Test
    fun `nothing after the current item means nothing to shuffle`() = runTest(dispatcher) {
        queue.enqueue(episode("only"))
        queue.jumpTo(0)
        advanceUntilIdle()

        queue.shuffleUpNext(Random(SEED))

        assertEquals(listOf("only"), ids())
    }

    private companion object {
        const val SEED = 42
    }
}
