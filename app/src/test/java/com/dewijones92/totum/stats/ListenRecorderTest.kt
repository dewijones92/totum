package com.dewijones92.totum.stats

import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ListenRecorderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val controller = FakePlaybackController()

    private fun state(id: String, playing: Boolean, kind: MediaKind = MediaKind.PODCAST) = PlaybackState(
        itemId = MediaItemId(id),
        title = "title $id",
        artist = "A show",
        artworkUrl = null,
        kind = kind,
        isPlaying = playing,
        positionMs = 0,
        durationMs = 600_000,
        speed = 1f,
    )

    private fun TestScope.recorder(log: StatsLog, work: CoroutineScope): ListenRecorder {
        val queue = PlaybackQueue(
            controller = controller,
            launcher = VideoPlaybackLauncher(
                VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
                controller,
                FakeYouTubeWatchHistory(),
                InMemoryPlayHistoryStore(),
            ),
            scope = work,
        )
        return ListenRecorder(controller, queue, log, work, now = {
            testScheduler.currentTime
        }, ticks = { testScheduler.currentTime })
    }

    @Test
    fun `time playing is heard, a pause is not, and the play is written when the item changes`() = runTest {
        val work = backgroundScope
        val log = StatsLog(null, work, StandardTestDispatcher(testScheduler))
        recorder(log, work).start()
        runCurrent()

        controller.emitState(state("ep1", playing = true))
        advanceTimeBy(40_000)
        controller.emitState(state("ep1", playing = false))
        advanceTimeBy(60_000)
        controller.emitState(state("ep2", playing = true))
        advanceTimeBy(2_000)

        val listen = log.all().filterIsInstance<StatEvent.Listen>().single()
        assertEquals("ep1", listen.itemId)
        assertEquals(ListenKind.PODCAST, listen.kind)
        assertTrue("heard ${listen.heardMs}", listen.heardMs in 38_000..41_000)
        assertTrue(listen.counts)
    }

    @Test
    fun `a play cut off by the process ending is recovered on the next start`() = runTest {
        val work = CoroutineScope(coroutineContext + SupervisorJob())
        val dir = folder.newFolder("stats")
        val first = StatsLog(dir, work, StandardTestDispatcher(testScheduler))
        recorder(first, work).start()
        runCurrent()
        controller.emitState(state("ep1", playing = true))
        advanceTimeBy(35_000)
        work.cancel()

        val again = backgroundScope
        val second = StatsLog(dir, again, StandardTestDispatcher(testScheduler))
        recorder(second, again).start()
        advanceTimeBy(1_000)

        val recovered = second.all().filterIsInstance<StatEvent.Listen>().single()
        assertEquals("ep1", recovered.itemId)
        assertTrue("heard ${recovered.heardMs}", recovered.heardMs >= 30_000)
    }
}
