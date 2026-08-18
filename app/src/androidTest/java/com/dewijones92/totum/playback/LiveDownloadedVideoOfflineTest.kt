package com.dewijones92.totum.playback

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.support.DeviceRadios.goOffline
import com.dewijones92.totum.support.DeviceRadios.goOnline
import com.dewijones92.totum.support.DeviceRadios.hasNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The real thing, end to end: yt-dlp fetches a real YouTube video's audio, the radios go off, and
 * the queue plays that file.
 *
 * This is the test that would have caught the reported bug with no argument about fidelity. Dewi,
 * 2026-08-06: airplane mode, a Novara episode the queue had already downloaded, nothing played —
 * because the queue asked the download store for a podcast and never for a video. Everything here
 * is the production path: the real engine, the real `RoutedDownloadStrategy`, a real file, real
 * Room, and `PlaybackQueue` routing a `PlayHandle.Video` with no network.
 *
 * **It needs live YouTube, and CI can have it.** A GitHub runner is a datacentre IP that gets
 * bot-checked, so it runs inside `tools/ci/live-test-via-home.sh`, which brings up a WireGuard
 * tunnel to Dewi's Pi and egresses through his home broadband — the same rig `SabrPlaybackTest`
 * uses. That script is allowed to skip (a router reboot is not a build failure), which is exactly
 * why the deterministic guard in [OfflineQueuePlaybackTest] exists as well: this one proves the
 * real path when it can run, that one proves the routing on every commit regardless.
 *
 * **Skipped, never failed, when YouTube will not serve this machine.** Extraction failing is an
 * environment condition, and a suite that reports defects it has no evidence for is a suite
 * everyone learns to ignore. The skip is visible in CI: the script greps the result XML for it.
 */
class LiveDownloadedVideoOfflineTest {

    /** Foreground, or the platform refuses audio focus and nothing ever plays. */
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as TotumApplication
    private val container get() = app.container
    private val controller get() = container.playbackController
    private val queue get() = container.playbackQueue
    private val downloads get() = container.downloadManager

    private val watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO_ID")

    @Before
    fun start() = runBlocking(Dispatchers.Main) {
        awaitControllerConnected()
        // A real clip has real silence in it, and sample removal would make the position jump in a
        // way this test would read as a stall.
        controller.setSkipSilence(false)
        queue.clear()
        controller.player?.stop()
        controller.player?.clearMediaItems()
        downloads.delete(MediaItemId(VIDEO_ID))
    }

    @After
    fun tearDown() {
        goOnline()
        runBlocking(Dispatchers.Main) {
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
            downloads.delete(MediaItemId(VIDEO_ID))
        }
    }

    @Test
    fun `a video downloaded from YouTube plays from disk with the radios off`() =
        runBlocking(Dispatchers.Main) {
            val video = youTubeItem()

            // Exactly what the queue's auto-downloader does: audio only, through the engine.
            downloads.download(video, audioOnly = true)
            val state = awaitSettled()
            assumeTrue(
                "YouTube would not serve this machine (${(state as? DownloadState.Failed)?.reason}) " +
                    "— skipped rather than failed, since that is an environment condition",
                state is DownloadState.Downloaded,
            )
            val path = (state as DownloadState.Downloaded).localPath

            goOffline()
            assertEquals("the radios did not actually go off", false, hasNetwork())
            // And wait for the APP to notice, which is a separate fact. `ConnectivityManager`
            // delivers its callback asynchronously, so the radios can be off while the container
            // still reports online — and `routeNow` asked while it did would stream instead of using
            // the file, failing this test for a reason that is purely a race. Seen on 2026-08-18: it
            // passed in isolation and failed after another live test, playing `itag=303` from the
            // network with the radios provably down.
            assertTrue(
                "the app never noticed the network had gone",
                withTimeoutOrNull(OFFLINE_NOTICED_TIMEOUT_MS) {
                    while (!container.isOffline()) delay(POLL_MS)
                    true
                } ?: false,
            )

            queue.playNow(video)
            assertTrue(
                "the downloaded video never started playing offline",
                awaitPlaying(),
            )
            assertTrue(
                "it must play the file yt-dlp produced ($path). It played from \"${lastSource()}\". " +
                    "At this moment: app-offline=${container.isOffline()} device-network=${hasNetwork()} " +
                    "route trail:\n" + Breadcrumbs.snapshot().takeLast(TRAIL_LINES)
                        .filter { "route" in it.message || "play " in it.message }
                        .joinToString("\n") { it.message.take(TRAIL_CHARS) },
                lastSource()?.contains(path) == true,
            )
            assertTrue(
                "playback stalled at ${controller.state.value?.positionMs}ms",
                awaitPositionBeyond(PROGRESS_MS),
            )
        }

    /**
     * Queued as a video, which is how everything from a YouTube feed arrives. No media URL: a feed
     * item carries the watch URL in its handle and nothing else, so this is the honest shape.
     */
    private fun youTubeItem() = PlayableItem(
        item = MediaItem(
            id = MediaItemId(VIDEO_ID),
            sourceId = SourceId("live-test"),
            title = "a real video for the plane",
            publishedAt = null,
            duration = null,
            mediaUrl = watchUrl,
        ),
        handle = PlayHandle.Video(watchUrl),
    )

    /** Waits for the download to reach a terminal state. Extraction alone can take 20-25s here. */
    private suspend fun awaitSettled(): DownloadState? = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
        downloads.observe(MediaItemId(VIDEO_ID)).first { state ->
            state is DownloadState.Downloaded || state is DownloadState.Failed
        }
    }

    private fun lastSource(): String? =
        controller.player?.currentMediaItem?.localConfiguration?.uri?.toString()

    private suspend fun awaitControllerConnected() {
        val connected = withTimeoutOrNull(START_TIMEOUT_MS) {
            while (controller.player == null) delay(POLL_MS)
            true
        }
        assertEquals("the media controller never connected to the playback service", true, connected)
    }

    private suspend fun awaitPlaying(): Boolean = withTimeoutOrNull(START_TIMEOUT_MS) {
        while (controller.state.value?.isPlaying != true) delay(POLL_MS)
        true
    } ?: false

    private suspend fun awaitPositionBeyond(target: Long): Boolean =
        withTimeoutOrNull(START_TIMEOUT_MS) {
            while ((controller.state.value?.positionMs ?: 0) <= target) delay(POLL_MS)
            true
        } ?: false

    private companion object {
        /** "Me at the zoo" — 19 seconds, the oldest video on the site, and unlikely to move. */
        const val VIDEO_ID = "jNQXAC9IVRw"

        /** Python start, extraction and a real fetch, on an emulator. */
        const val DOWNLOAD_TIMEOUT_MS = 5 * 60 * 1000L
        const val START_TIMEOUT_MS = 30_000L

        /** ConnectivityManager's callback is asynchronous; this is generous and finite. */
        const val OFFLINE_NOTICED_TIMEOUT_MS = 20_000L
        const val TRAIL_LINES = 40
        const val TRAIL_CHARS = 200

        const val PROGRESS_MS = 1_000L
        const val POLL_MS = 200L
    }
}
