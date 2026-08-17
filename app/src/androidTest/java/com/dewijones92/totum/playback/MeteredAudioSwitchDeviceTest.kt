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
import com.dewijones92.totum.settings.PlaybackMode
import com.dewijones92.totum.support.DeviceRadios.goOnline
import com.dewijones92.totum.support.DeviceRadios.shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The Wi-Fi-drops-mid-video switch, with real radios and a real player.
 *
 * Dewi, 2026-08-04: *"if the phone is playing video but then there is suddenly no wifi, a
 * notification appears saying 'hey we have switched to listening only mode'"*.
 *
 * The decision is covered by ten unit tests; what they cannot reach is everything around it — that
 * the player actually reports `hasVideo`, that turning Wi-Fi off really does leave a METERED
 * connection rather than no connection, and that the re-prepare survives. The emulator carries both
 * a mobile and a Wi-Fi network, so `svc wifi disable` falls back to mobile exactly as a phone does
 * when you walk out of the house — which is the scenario, not an approximation of it.
 *
 * **What this deliberately does NOT prove:** that less data is used. The clip is a local file, so
 * nothing is downloaded either way. The saving was measured separately and is not in doubt (15.2
 * MB/min against 2.1); what was unproven is the machinery, and that is what runs here.
 *
 * The clip is a 90-second black 320x240 H.264 with silent audio, ~69KB, generated with ffmpeg
 * rather than sourced — the repository carries no real media, and it only has to have a video track
 * for the player to report one.
 */
class MeteredAudioSwitchDeviceTest {

    /** Foreground, or the platform refuses audio focus and nothing ever plays. */
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
        clip = File(context.cacheDir, "clip.mp4")
        instrumentation.context.assets.open("clip.mp4").use { input ->
            clip.outputStream().use(input::copyTo)
        }
        goOnline()
        runBlocking(Dispatchers.Main) {
            awaitControllerConnected()
            // Video mode, or there is no video to drop and the whole test is vacuous.
            container.appPreferences.setPlaybackMode(PlaybackMode.VIDEO)
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
            awaitRearmed()
        }
        Breadcrumbs.clear()
    }

    /**
     * Waits until the switch's metered accumulator has certainly been zeroed.
     *
     * `MeteredAudioSwitch` samples every 5s and only resets `meteredForMs` on a tick that SEES an
     * unmetered network — so the previous test, which deliberately banks past the 15-second hold,
     * leaves the accumulator full until such a tick lands. Without this, `a momentary drop onto
     * mobile data changes nothing` puts its 6-second blip on top of 15 seconds already banked, the
     * switch fires immediately, and the test fails having proved nothing about hysteresis. It passed
     * in isolation and failed after its sibling, on 2026-08-17.
     *
     * Both exits establish the precondition: either the re-arm breadcrumb arrives, or a full
     * sampling interval has passed with the network unmetered, which zeroes it by definition. The
     * breadcrumb only appears when there WAS accumulation, so waiting for it unconditionally would
     * hang whenever this test class runs on its own.
     */
    private suspend fun awaitRearmed() {
        withTimeoutOrNull(REARM_TIMEOUT_MS) {
            while (Breadcrumbs.snapshot().none { "the switch is re-armed" in it.message }) delay(POLL_MS)
        }
    }

    @After
    fun tearDown() {
        // FIRST and unconditionally: a device left on mobile-only would change the meaning of every
        // test that runs after this one.
        goOnline()
        runBlocking(Dispatchers.Main) {
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
            container.appPreferences.setPlaybackMode(PlaybackMode.AUTO)
        }
        clip.delete()
    }

    @Test
    fun `dropping onto mobile data while a video plays switches it to audio`() =
        runBlocking(Dispatchers.Main) {
            queue.playNow(videoClip())
            assertTrue("the clip never started playing with video", awaitPlayingVideo())

            // Off Wi-Fi, onto mobile — the emulator keeps a cellular network up, so this is a
            // change of network rather than a loss of one.
            shell("svc wifi disable")

            val switched = withTimeoutOrNull(SWITCH_TIMEOUT_MS) {
                while (Breadcrumbs.snapshot().none { "switching to audio only" in it.message }) {
                    delay(POLL_MS)
                }
                true
            } ?: false

            assertTrue(
                "walking onto mobile data with a video playing must switch to audio. Trail: " +
                    Breadcrumbs.snapshot().map { it.message }.filter { "data" in it || "metered" in it },
                switched,
            )
            assertEquals(
                "the switch must flip the real listen mode, so the player's own toggle is the undo",
                PlaybackMode.AUDIO,
                container.appPreferences.settings.value.playbackMode,
            )
        }

    /**
     * And it holds off first. This is the case that decides whether the feature is tolerable: a
     * lift or a tunnel must not re-prepare the player, and a phone that flaps would otherwise
     * stutter continuously.
     */
    @Test
    fun `a momentary drop onto mobile data changes nothing`() = runBlocking(Dispatchers.Main) {
        queue.playNow(videoClip())
        assertTrue("the clip never started playing with video", awaitPlayingVideo())

        shell("svc wifi disable")
        delay(BLIP_MS)
        shell("svc wifi enable")
        delay(SETTLE_MS)

        assertEquals(
            "a brief blip must leave the mode alone",
            PlaybackMode.VIDEO,
            container.appPreferences.settings.value.playbackMode,
        )
    }

    private fun videoClip() = PlayableItem(
        item = MediaItem(
            id = MediaItemId("a-clip"),
            sourceId = SourceId("test"),
            title = "a clip with a picture",
            publishedAt = null,
            duration = null,
            mediaUrl = null,
        ),
        handle = PlayHandle.Podcast(clip.absolutePath),
    )

    private suspend fun awaitPlayingVideo(): Boolean = withTimeoutOrNull(START_TIMEOUT_MS) {
        while (controller.state.value?.let { it.isPlaying && it.hasVideo } != true) delay(POLL_MS)
        true
    } ?: false

    private suspend fun awaitControllerConnected() {
        val connected = withTimeoutOrNull(START_TIMEOUT_MS) {
            while (controller.player == null) delay(POLL_MS)
            true
        }
        assertEquals("the media controller never connected to the playback service", true, connected)
    }

    private companion object {
        const val START_TIMEOUT_MS = 30_000L

        /** Must outlast the 15-second hold plus a 5-second sampling interval. */
        const val SWITCH_TIMEOUT_MS = 45_000L

        /** Comfortably under the hold, so it is a blip by definition. */
        const val BLIP_MS = 6_000L

        /** One 5-second sampling interval plus margin — see [awaitRearmed]. */
        const val REARM_TIMEOUT_MS = 7_000L
        const val SETTLE_MS = 25_000L
        const val POLL_MS = 250L
    }
}
