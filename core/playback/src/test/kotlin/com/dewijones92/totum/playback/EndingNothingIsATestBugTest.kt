package com.dewijones92.totum.playback

import com.dewijones92.totum.data.podcast.fake.FakePodcastRepository
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.playback.fake.FakePlaybackController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ending nothing has to fail loudly, because the alternative cost a red CI build nobody could read.
 *
 * `endCurrent()` built its event from `state.value` behind a `?.let`, so calling it before anything
 * was playing emitted NO event and left no trace. In [com.dewijones92.totum.ui.ShortsReelAdvanceTest]
 * that is reachable: `waitForIdle()` settles composition, but the reel starts its first short through
 * a suspending resolve, so on a slow machine the test could reach `endCurrent()` with nothing playing
 * yet. The advancer then heard nothing and the only symptom was
 * `ComposeTimeoutException: Condition still not satisfied after 5000 ms` — which reads as "the reel
 * does not advance", the exact opposite of the truth (2026-08-19, run 32239962730).
 *
 * The same lesson as [FakePlaybackController.failStream]'s note: a fake that drops the signal under
 * test is the worst possible place to learn it. A silent no-op here is indistinguishable from a
 * product bug, so it is now an error that names itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EndingNothingIsATestBugTest {

    private val controller = FakePlaybackController()
    private val episode = FakePodcastRepository.sampleEpisode(SourceId("feed-1"))

    @Test
    fun `ending nothing is an error rather than a silent no-op`() {
        val thrown = assertThrows(IllegalStateException::class.java) { controller.endCurrent() }

        assertTrue(
            "the message has to say what went wrong, or it is no better than the timeout it replaces: $thrown",
            thrown.message.orEmpty().contains("nothing is playing"),
        )
    }

    @Test
    fun `ending what is playing still announces itself`() = runTest {
        controller.play(episode)
        val heard = mutableListOf<PlaybackEvent>()
        val collector = launch { heard += controller.events.first() }
        runCurrent()

        controller.endCurrent()
        collector.join()

        assertEquals(listOf(episode.id), heard.map { (it as PlaybackEvent.Ended).itemId })
        assertEquals(true, controller.state.value?.hasEnded)
    }
}
