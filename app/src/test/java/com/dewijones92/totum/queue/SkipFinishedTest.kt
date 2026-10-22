package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.settings.RepeatMode
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SkipFinishedTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val finished = mutableSetOf<String>()
    private val queue = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
        passOver = { if (it.item.id.value in finished) "finished" else null },
    )

    private fun episode(id: String) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("show"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://feed.test/$id.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    private fun playing() = queue.state.value.current?.item?.item?.id?.value

    @Test
    fun `moving on passes over what is finished, and the next item ready ahead agrees`() = runTest(dispatcher) {
        queue.playAll(listOf("t1", "t2", "t3", "t4").map(::episode))
        advanceUntilIdle()
        finished += setOf("t2", "t3")

        assertEquals("t4", queue.peekNext()?.item?.id?.value)
        queue.continueInKind()
        advanceUntilIdle()

        assertEquals("t4", playing())
    }

    @Test
    fun `with nothing unfinished left the queue stops`() = runTest(dispatcher) {
        queue.playAll(listOf("t1", "t2", "t3").map(::episode))
        advanceUntilIdle()
        finished += setOf("t2", "t3")

        assertNull(queue.peekNext())
        assertFalse(queue.continueInKind())
        assertEquals("t1", playing())
    }

    @Test
    fun `music, repeats and unfinished items are never passed over`() {
        val played = mapOf(MediaItemId("t1") to PlayState.Played)
        val talk = episode("t1")
        assertEquals("finished", passOverReason(talk, RepeatMode.OFF, music = false, played))
        assertNull(passOverReason(talk, RepeatMode.OFF, music = true, played))
        assertNull(passOverReason(talk, RepeatMode.GROUP, music = false, played))
        assertNull(passOverReason(episode("t2"), RepeatMode.OFF, music = false, played))
    }

    @Test
    fun `finished talk ahead does not hide that only music is left`() {
        val talk = episode("now")
        val music = (1..3).map { song("m$it") }
        val snapshot = com.dewijones92.totum.data.queue.QueueSnapshot(
            entries = (listOf(talk, episode("done1"), episode("done2")) + music)
                .map { com.dewijones92.totum.data.queue.QueueEntry(it) },
            currentIndex = 0,
        )
        val left = onlyOtherKindAfter(
            snapshot,
            talk.item
        ) { if (it.item.id.value.startsWith("done")) "finished" else null }
        assertEquals(com.dewijones92.totum.domain.Listening.MUSIC to 3, left)
    }

    @Test
    fun `a reshuffled repeat leaves the order alone while there is still something to play`() = runTest(dispatcher) {
        queue.playAll(listOf("t1", "t2", "t3", "t4").map(::episode))
        advanceUntilIdle()

        queue.playFromTheTop(reshuffle = true)
        advanceUntilIdle()

        assertEquals(listOf("t1", "t2", "t3", "t4"), queue.state.value.entries.map { it.item.item.id.value })
    }

    @Test
    fun `next by hand passes over finished talk too`() = runTest(dispatcher) {
        queue.playAll(listOf("t1", "t2", "t3").map(::episode))
        advanceUntilIdle()
        finished += "t2"

        queue.playNextInQueue()
        advanceUntilIdle()

        assertEquals("t3", playing())
    }

    private fun song(id: String) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("album"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
            contentKind = com.dewijones92.totum.domain.MediaContentKind.MUSIC,
        ),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
    )
}
