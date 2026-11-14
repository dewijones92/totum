package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.RepeatMode
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartingAGroupEndsRepeatOneTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val album = QueueGroup("album:a", "An album")
    private var chosen = RepeatMode.ONE
    private var kidLocked = false
    private val modeSets = mutableListOf<RepeatMode>()

    private val queue = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
        repeatOneOffForRun = RepeatOneRule({ chosen }, { kidLocked }) { mode ->
            modeSets += mode
            chosen = mode
        }::switchOffForRunOf,
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

    private val run = listOf("t1", "t2", "t3").map(::track)

    private fun TestScope.noticesOf(): List<QueueNotice> {
        val heard = mutableListOf<QueueNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { queue.notices.collect { heard += it } }
        return heard
    }

    @Test
    fun `an album started by you while repeat one is on switches it off and says which album`() =
        runTest(dispatcher) {
            val heard = noticesOf()

            queue.playAll(run, album)
            advanceUntilIdle()

            assertEquals(RepeatMode.OFF, chosen)
            assertEquals(listOf(RepeatMode.OFF), modeSets)
            assertEquals(listOf<QueueNotice>(QueueNotice.RepeatOneOff("An album")), heard)
            assertEquals("t1", queue.state.value.current?.item?.item?.id?.value)
        }

    @Test
    fun `a run with no group leaves repeat one alone`() = runTest(dispatcher) {
        val heard = noticesOf()

        queue.playAll(run)
        advanceUntilIdle()

        assertEquals(RepeatMode.ONE, chosen)
        assertTrue(modeSets.isEmpty())
        assertTrue(heard.isEmpty())
    }

    @Test
    fun `a run the app starts for itself leaves repeat one alone`() = runTest(dispatcher) {
        val heard = noticesOf()

        queue.playAll(run, album, byYou = false)
        advanceUntilIdle()

        assertEquals(RepeatMode.ONE, chosen)
        assertTrue(modeSets.isEmpty())
        assertTrue(heard.isEmpty())
    }

    @Test
    fun `the shorts reel is not by you but still ends repeat one when it opens`() = runTest(dispatcher) {
        val heard = noticesOf()

        queue.playAll(run, album, byYou = false, endsRepeatOne = true)
        advanceUntilIdle()

        assertEquals(RepeatMode.OFF, chosen)
        assertEquals(listOf<QueueNotice>(QueueNotice.RepeatOneOff("An album")), heard)
    }

    @Test
    fun `by you but told not to end repeat one, it stays on`() = runTest(dispatcher) {
        val heard = noticesOf()

        queue.playAll(run, album, byYou = true, endsRepeatOne = false)
        advanceUntilIdle()

        assertEquals(RepeatMode.ONE, chosen)
        assertTrue(heard.isEmpty())
    }

    @Test
    fun `repeating the queue or the group is left as it was`() = runTest(dispatcher) {
        val heard = noticesOf()
        for (mode in RepeatMode.entries.filter { it != RepeatMode.ONE && it != RepeatMode.OFF }) {
            chosen = mode
            queue.playAll(run, album)
            advanceUntilIdle()
            assertEquals(mode, chosen)
        }

        assertTrue(modeSets.isEmpty())
        assertTrue(heard.filterIsInstance<QueueNotice.RepeatOneOff>().isEmpty())
    }

    @Test
    fun `repeat off stays off and says nothing`() = runTest(dispatcher) {
        chosen = RepeatMode.OFF
        val heard = noticesOf()

        queue.playAll(run, album)
        advanceUntilIdle()

        assertTrue(heard.isEmpty())
        assertTrue(modeSets.isEmpty())
    }

    @Test
    fun `under the kid lock the chosen repeat one is not touched, because the lock sets repeat itself`() =
        runTest(dispatcher) {
            kidLocked = true
            val heard = noticesOf()

            queue.playAll(run, album)
            advanceUntilIdle()

            assertEquals(RepeatMode.ONE, chosen)
            assertTrue(modeSets.isEmpty())
            assertTrue(heard.isEmpty())
        }

    @Test
    fun `the second run started after the first has nothing left to switch off`() = runTest(dispatcher) {
        val heard = noticesOf()

        queue.playAll(run, album)
        advanceUntilIdle()
        queue.playAll(listOf(track("u1")), QueueGroup("album:b", "Another"))
        advanceUntilIdle()

        assertEquals(listOf<QueueNotice>(QueueNotice.RepeatOneOff("An album")), heard)
        assertEquals(1, modeSets.size)
    }

    @Test
    fun `choosing repeat one again after the run started is not undone by the next track`() =
        runTest(dispatcher) {
            queue.playAll(run, album)
            advanceUntilIdle()
            chosen = RepeatMode.ONE

            queue.playNextInQueue()
            advanceUntilIdle()

            assertEquals(RepeatMode.ONE, chosen)
            assertFalse(modeSets.size > 1)
        }
}
