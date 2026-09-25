package com.dewijones92.totum.playback

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.support.PlaybackWaits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The right silence mechanism actually engages for the content being played.
 *
 * The unit test pins the DECISION; this pins that the decision reaches the audio chain against a
 * real player. Both halves matter and neither implies the other: a correct choice wired to nothing
 * sounds exactly like no feature at all, and the wiring runs through an audio-sink processor chain
 * that no JVM test can construct.
 *
 * Dewi, 2026-08-04: *"make sure the skip silences thing is as smooth as other apps e.g.
 * antennapod"*. AntennaPod removes the silent samples; that is what must happen for a podcast.
 */
class SilenceStrategyDeviceTest {

    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as TotumApplication
    private val container get() = app.container
    private val controller get() = container.playbackController
    private val queue get() = container.playbackQueue

    private lateinit var clip: File

    @Before
    fun setUp() {
        clip = File(context.cacheDir, "strategy-clip.mp4")
        instrumentation.context.assets.open("clip.mp4").use { input ->
            clip.outputStream().use(input::copyTo)
        }
        runBlocking(Dispatchers.Main) {
            withTimeoutOrNull(TIMEOUT_MS) {
                while (controller.player == null) delay(POLL_MS)
            }
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
        }
    }

    @After
    fun tearDown() = runBlocking(Dispatchers.Main) {
        controller.setSkipSilence(false)
        queue.clear()
        controller.player?.stop()
        controller.player?.clearMediaItems()
        clip.delete()
        Unit
    }

    /** A picture is being kept in sync, so samples must not be removed. */
    @Test
    fun `a video speeds through silence rather than cutting it`() = runBlocking(Dispatchers.Main) {
        queue.playNow(item(CLIP_ID, clip.absolutePath))
        PlaybackWaits.awaitStateOf(controller, MediaItemId(CLIP_ID), TIMEOUT_MS) { it.hasVideo }
        Breadcrumbs.clear()
        controller.setSkipSilence(true)

        assertTrue(trail(), awaitStrategy("SPEED_UP"))
    }

    private fun item(id: String, path: String) = PlayableItem(
        item = MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("test"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = null,
        ),
        handle = PlayHandle.Podcast(path),
    )

    private suspend fun awaitStrategy(name: String): Boolean = withTimeoutOrNull(TIMEOUT_MS) {
        while (Breadcrumbs.snapshot().none { "handling silence by $name" in it.message }) delay(POLL_MS)
        true
    } ?: false

    private fun trail() =
        "the chain was never told which mechanism to use. Trail: " +
            Breadcrumbs.snapshot().map { it.message }.filter { "silence" in it }

    private companion object {
        const val CLIP_ID = "a-clip"
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 200L
    }
}
