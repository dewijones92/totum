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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class QueueGroupPlaybackTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val album = QueueGroup("album:a", "An album")
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

    private fun track(id: String) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("album"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://feed.test/$id.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    private fun inAlbum() = queue.state.value.entries.filter { it.group?.id == album.id }.map { it.item.item.id.value }

    @Test
    fun `playing through a group keeps every song in it`() = runTest(dispatcher) {
        queue.playAll(listOf("t1", "t2", "t3").map(::track), album)
        advanceUntilIdle()

        queue.playNextInQueue()
        queue.playNextInQueue()
        advanceUntilIdle()

        assertEquals(listOf("t1", "t2", "t3"), inAlbum())
        assertEquals("t3", queue.state.value.current?.item?.item?.id?.value)
    }

    @Test
    fun `tapping a song that is already in a group plays it and leaves it in the group`() = runTest(dispatcher) {
        queue.playAll(listOf("t1", "t2", "t3").map(::track), album)
        advanceUntilIdle()

        queue.playNow(track("t3"))
        advanceUntilIdle()

        assertEquals(setOf("t1", "t2", "t3"), inAlbum().toSet())
        assertEquals("t3", queue.state.value.current?.item?.item?.id?.value)
    }

    private fun playingId() = queue.state.value.current?.item?.item?.id?.value

    @Test
    fun `shuffle and play gathers the group, shuffles it and plays its first song`() = runTest(dispatcher) {
        queue.playAll(listOf(track("other")))
        queue.enqueueAll(listOf("t1", "t2", "t3", "t4", "t5").map(::track), album)
        advanceUntilIdle()

        queue.shuffleAndPlayGroup(album.id, Random(SEED))
        advanceUntilIdle()

        val entries = queue.state.value.entries
        val first = entries.indexOfFirst { it.group?.id == album.id }
        assertEquals(setOf("t1", "t2", "t3", "t4", "t5"), inAlbum().toSet())
        assertTrue("the group stays together", entries.drop(first).take(5).all { it.group?.id == album.id })
        assertEquals(inAlbum().first(), playingId())
        assertEquals(inAlbum().first(), controller.state.value?.itemId?.value)
        assertTrue("the other item stays queued", entries.any { it.item.item.id.value == "other" })
    }

    @Test
    fun `shuffle and play inside the playing group moves to a different song`() = runTest(dispatcher) {
        repeat(SEEDS) { seed ->
            queue.playAll(listOf("t1", "t2", "t3").map(::track), album)
            advanceUntilIdle()
            val before = playingId()

            queue.shuffleAndPlayGroup(album.id, Random(seed))
            advanceUntilIdle()

            assertNotEquals("seed $seed", before, playingId())
            assertEquals(setOf("t1", "t2", "t3"), inAlbum().toSet())
        }
    }

    @Test
    fun `previous goes back one item, or restarts the item once it is well under way`() = runTest(dispatcher) {
        queue.playAll(listOf("t1", "t2", "t3").map(::track), album)
        advanceUntilIdle()
        queue.playNextInQueue()
        advanceUntilIdle()
        assertEquals("t2", playingId())

        queue.playPreviousInQueue()
        advanceUntilIdle()
        assertEquals("t1", playingId())

        controller.seekTo(60_000)
        queue.playPreviousInQueue()
        advanceUntilIdle()
        assertEquals("t1", playingId())
        assertEquals(0L, controller.state.value?.positionMs)
    }

    private companion object {
        const val SEED = 7
        const val SEEDS = 20
    }
}
