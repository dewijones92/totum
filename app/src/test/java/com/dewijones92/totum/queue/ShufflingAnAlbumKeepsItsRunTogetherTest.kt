package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaContentKind
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShufflingAnAlbumKeepsItsRunTogetherTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val album = QueueGroup("album:z", "Album Z")
    private val radio = QueueGroup("radio:r", "Radio · R")

    private fun song(id: String, music: Boolean = true) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("s"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://feed.test/$id.mp3"),
            contentKind = if (music) MediaContentKind.MUSIC else MediaContentKind.STANDARD,
        ),
        PlayHandle.Podcast(),
    )

    private fun TestScope.queueOf(): Pair<PlaybackQueue, CoroutineScope> {
        val work = CoroutineScope(coroutineContext + SupervisorJob())
        return PlaybackQueue(
            controller = controller,
            launcher = VideoPlaybackLauncher(
                VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
                controller,
                FakeYouTubeWatchHistory(),
                InMemoryPlayHistoryStore(),
            ),
            scope = work,
        ) to work
    }

    private fun PlaybackQueue.ids() = state.value.entries.map { it.item.item.id.value }

    private fun PlaybackQueue.playing() = state.value.current?.item?.item?.id?.value

    private suspend fun TestScope.advancePlayed(queue: PlaybackQueue, times: Int): List<String?> =
        (1..times).map {
            queue.continueInKind()
            advanceUntilIdle()
            queue.playing()
        }

    @Test
    fun `an album shuffled again from a peek goes after the place the queue left, not to the head`() =
        runTest(dispatcher) {
            val (queue, work) = queueOf()
            queue.playAll(listOf("a", "b", "c", "d", "e").map { song(it) })
            advanceUntilIdle()
            queue.playAt(3, reason = null)
            advanceUntilIdle()
            queue.peek(song("peeked"))
            advanceUntilIdle()

            queue.playAll(listOf("x1", "d", "x2").map { song(it) }, album)
            advanceUntilIdle()

            assertEquals(listOf("a", "b", "c", "x1", "d", "x2", "e"), queue.ids())
            assertEquals("x1", queue.playing())
            work.cancel()
        }

    @Test
    fun `an album shuffled again after a restart goes after the remembered place, not to the head`() =
        runTest(dispatcher) {
            val (queue, work) = queueOf()
            queue.playAll(listOf("a", "b", "c", "d", "e").map { song(it) })
            advanceUntilIdle()
            queue.clear()
            queue.enqueueAll(listOf("a", "b", "c", "d", "e").map { song(it) })
            queue.restorePlace(MediaItemId("d"))

            queue.playAll(listOf("d", "x1", "x2").map { song(it) }, album)
            advanceUntilIdle()

            assertEquals(listOf("a", "b", "c", "d", "x1", "x2", "e"), queue.ids())
            assertEquals("d", queue.playing())
            work.cancel()
        }

    @Test
    fun `while a shuffled album has songs left only its own songs play, even with a radio ahead and music behind`() =
        runTest(dispatcher) {
            val (queue, work) = queueOf()
            queue.playAll(listOf("r1", "r2", "r3").map { song(it) }, radio)
            queue.enqueueAll(listOf("later1", "later2").map { song(it) })
            advanceUntilIdle()

            queue.playAll(listOf("z3", "z1", "z4", "z2").map { song(it) }, album)
            advanceUntilIdle()

            assertEquals(listOf("r1", "z3", "z1", "z4", "z2", "r2", "r3", "later1", "later2"), queue.ids())
            assertEquals("z3", queue.playing())
            assertEquals(listOf("z1", "z4", "z2"), advancePlayed(queue, 3))
            work.cancel()
        }

    @Test
    fun `when the album ends the queue carries on with the next music in order and passes over talk`() =
        runTest(dispatcher) {
            val (queue, work) = queueOf()
            queue.playAll(listOf("r1", "r2").map { song(it) }, radio)
            queue.enqueueAll(listOf(song("talk", music = false), song("later")))
            advanceUntilIdle()
            queue.playAll(listOf("z1", "z2").map { song(it) }, album)
            advanceUntilIdle()

            val afterTheAlbum = advancePlayed(queue, 3)

            assertEquals(listOf("z2", "r2", "later"), afterTheAlbum)
            assertFalse("the talk item is passed over, not played", "talk" in afterTheAlbum)
            work.cancel()
        }
}
