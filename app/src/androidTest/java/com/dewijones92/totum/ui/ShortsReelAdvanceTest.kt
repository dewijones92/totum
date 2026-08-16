package com.dewijones92.totum.ui

import androidx.compose.ui.test.junit4.createComposeRule
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.ReelStart
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.playback.AutoAdvancer
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.shorts.ShortsReelScreen
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The shorts reel on the shared advance path.
 *
 * Instrumented rather than a JVM test because the reel's queue binding is composable effects,
 * and driven directly rather than through the UI because the reel is only reachable from the
 * signed-in feed selector — so there is no way to reach it by hand on an emulator without a
 * YouTube account. Composing it with a fake container is what makes the behaviour testable at
 * all, and it is the behaviour that matters: a short must be advanced past like anything else,
 * including when nothing is looking at the screen.
 *
 * Previously the reel paged itself from its own composable effect and told the shared advancer
 * to keep out, so with the phone in a pocket both were asleep and a short dead-ended.
 */
class ShortsReelAdvanceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val playback = FakePlaybackController()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val engine = FakeYtDlpEngine()
    private val container = FakeAppContainer(ytDlpEngine = engine, playbackController = playback)

    private val shorts = listOf(short("s1"), short("s2"), short("s3"))

    @Before
    fun makeShortsExtractable() {
        // A queued video resolves just-in-time through the engine, so an unregistered URL
        // simply never plays — the first run of this test failed on exactly that, which is a
        // fake's default rather than anything about the reel.
        shorts.forEach { short ->
            engine.registerMedia(short.mediaUrl!!, FakeYtDlpEngine.sampleMetadata(id = short.id.value))
        }
    }

    private fun openReel() {
        // The real advancer, wired as AppContainer wires it — the point is that THIS is what
        // moves the reel on, not anything inside the screen.
        AutoAdvancer(
            events = playback.events,
            advance = { container.playbackQueue.playNextInQueue() },
            whenQueueEmpty = {},
            isEnabled = { true },
            scope = scope,
        ).start()
        composeTestRule.setContent {
            TotumTheme { ShortsReelScreen(container, ReelStart(shorts, 0), onBack = {}) }
        }
        composeTestRule.waitForIdle()
    }

    /**
     * The whole run is queued up front. This is the crux: queueing one short at a time meant
     * the next did not exist when the current one ended, which is why the reel needed a
     * private advance in the first place.
     */
    @Test
    fun openingTheReel_queuesEveryShort() {
        openReel()

        val queued = container.playbackQueue.state.value.entries.map { it.item.item.id.value }
        assertTrue("expected all three shorts queued, got $queued", queued.containsAll(listOf("s1", "s2", "s3")))
    }

    @Test
    fun openingTheReel_playsTheFirstShort() {
        openReel()

        assertEquals("s1", playback.state.value?.itemId?.value)
    }

    /** The behaviour Dewi asked for: a short ending rolls on, on the shared path. */
    @Test
    fun aShortEnding_advancesToTheNext() {
        openReel()

        playback.endCurrent()
        composeTestRule.waitUntil(TIMEOUT_MS) { playback.state.value?.itemId?.value == "s2" }

        assertEquals("s2", playback.state.value?.itemId?.value)
    }

    /** And keeps rolling, rather than advancing once and stopping. */
    @Test
    fun endingRepeatedly_walksTheWholeReel() {
        openReel()

        playback.endCurrent()
        composeTestRule.waitUntil(TIMEOUT_MS) { playback.state.value?.itemId?.value == "s2" }
        playback.endCurrent()
        composeTestRule.waitUntil(TIMEOUT_MS) { playback.state.value?.itemId?.value == "s3" }

        assertEquals("s3", playback.state.value?.itemId?.value)
    }

    private fun short(id: String) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("shorts"),
        title = id,
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
        contentKind = MediaContentKind.SHORT,
    )

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
