package com.dewijones92.totum.playback

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
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
import com.dewijones92.totum.support.PlaybackWaits
import com.dewijones92.totum.support.SilentWav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * What the app is FOR, on a plane: something downloaded plays with the radios off.
 *
 * Dewi, 2026-08-03: *"put e2e tests that test that offline stuff (i.e. stuff in the queue) can be
 * played successfully offline … this means putting emulator offline"*.
 *
 * Two phases, in one test because the order is the point: download while online, then take the
 * device genuinely offline and play what was downloaded. Splitting them across tests would let the
 * offline half pass on a device that had never downloaded anything.
 *
 * The radios really go off (see [DeviceRadios], which explains why a packet filter will not do).
 *
 * **Two ways this test could pass while proving nothing**, both guarded:
 *
 *  - *It streamed instead.* "Audio came out" is satisfied by playing the remote URL, so the test
 *    asserts the player was given the **local file**, not merely that it played.
 *  - *The network was never off.* If `svc` silently failed, the whole thing is an online test with
 *    a misleading name — so being offline is asserted through `ConnectivityManager`, the same
 *    source the app itself consults, before anything is played.
 *
 * Restoring the radios in [tearDown] is not politeness — see [DeviceRadios.goOnline].
 */
class OfflineQueuePlaybackTest {

    /** Foreground, or the platform refuses audio focus and nothing ever plays. */
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as TotumApplication
    private val container get() = app.container
    private val controller get() = container.playbackController
    private val queue get() = container.playbackQueue
    private val downloads get() = container.downloadManager

    private lateinit var server: ServerSocket
    private val media = SilentWav.bytes(seconds = MEDIA_SECONDS)

    @Before
    fun startServerAndEmptyTheQueue() {
        server = ServerSocket(0)
        thread(isDaemon = true, name = "offline-test-media-server") { serveUntilClosed() }
        runBlocking(Dispatchers.Main) {
            awaitControllerConnected()
            // Explicitly OFF, not merely assumed off. This media is a SILENT wav, so if a
            // previous test left skip-silence on, sample-removal deletes the entire file and
            // playback never starts — which reads as "the item never played" and looks like a
            // playback bug. CI hit exactly that; the local order happened to hide it.
            controller.setSkipSilence(false)
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
            downloads.delete(ITEM_ID)
            downloads.delete(MediaItemId(UNAVAILABLE_ID))
        }
    }

    @After
    fun tearDown() {
        // FIRST, and unconditionally: a leaked offline device breaks every test that follows.
        goOnline()
        runBlocking(Dispatchers.Main) {
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
            downloads.delete(ITEM_ID)
            downloads.delete(MediaItemId(UNAVAILABLE_ID))
        }
        runCatching { server.close() }
    }

    @Test
    fun `a downloaded queue item plays with the radios off`() = runBlocking(Dispatchers.Main) {
        val item = hostedItem()

        // Phase one, online: download it through the real download path, so what is played back
        // is a file this app actually produced rather than one the test planted.
        downloads.download(item, audioOnly = false)
        val downloaded = awaitDownloaded()
        assertTrue(
            "the item never finished downloading, so the offline half would prove nothing",
            downloaded != null,
        )

        // Phase two: genuinely offline.
        goOffline()
        assertEquals(
            "the radios did not actually go off — `svc` may have failed, and this would " +
                "otherwise be an online test wearing an offline name",
            false,
            hasNetwork(),
        )

        queue.playNow(item)
        awaitPlaying()

        assertTrue(
            "offline playback must come from the downloaded file. It played, but from " +
                "\"${lastSource()}\" — a remote URL here means the test would pass online and " +
                "tell us nothing about a plane",
            lastSource()?.contains(downloaded!!) == true,
        )
        val progressed = awaitPositionBeyond(PROGRESS_MS)
        assertTrue(
            "offline playback stalled. The player is on ${PlaybackWaits.whatIsActuallyPlaying(controller)}",
            progressed,
        )
    }

    /**
     * Offline, the queue steps over what it cannot play and reaches what it can — quickly.
     *
     * "Quickly" is the assertion, not a nicety. Before the device was consulted, a non-downloaded
     * item was ATTEMPTED offline: a 20-second stall, two rescues and a give-up, about a minute of
     * spinner per item, to reach a conclusion `ConnectivityManager` had from the start. With a queue
     * of eighty that is indistinguishable from the app being broken. The timeout here is far below
     * that budget, so a regression shows up as a failure rather than as slowness nobody notices.
     *
     * No comma in the name: dex cannot represent one in a method name, and D8 fails the whole
     * androidTest build with "cannot be represented in dex format". JVM tests have no such limit,
     * so the same phrasing is fine one tier down and fatal here.
     */
    @Test
    fun `with the radios off the queue skips what it cannot play and reaches what it can`() =
        runBlocking(Dispatchers.Main) {
            val item = hostedItem()
            downloads.download(item, audioOnly = false)
            assertTrue("setup: the item must download while still online", awaitDownloaded() != null)

            // Queued in front of it: same shape, never downloaded, so offline it is unplayable.
            val unavailable = PlayableItem(
                item = MediaItem(
                    id = MediaItemId(UNAVAILABLE_ID),
                    sourceId = SourceId("test"),
                    title = "not downloaded",
                    publishedAt = null,
                    duration = null,
                    mediaUrl = HttpUrl.of("http://127.0.0.1:${server.localPort}/never-downloaded.wav"),
                ),
                handle = PlayHandle.Podcast(),
            )
            queue.enqueue(unavailable)
            queue.enqueue(item)

            goOffline()
            assertEquals("the radios did not actually go off", false, hasNetwork())

            queue.playNextInQueue()

            val reached = withTimeoutOrNull(SKIP_TIMEOUT_MS) {
                while (controller.state.value?.itemId != ITEM_ID) delay(POLL_MS)
                true
            } ?: false
            assertTrue(
                "offline, the queue must step straight over the item it cannot play. It was still " +
                    "on \"${controller.state.value?.itemId?.value}\" after ${SKIP_TIMEOUT_MS}ms, " +
                    "which is what attempting a doomed stream looks like",
                reached,
            )
        }

    /**
     * The bug Dewi hit on 2026-08-06: a **video** queue entry whose copy was already downloaded,
     * refused in airplane mode with the file on the disk. The two tests above could not have
     * caught it — both queue podcast-handled items, and the missing lookup was on the video branch.
     *
     * The file is fetched through the podcast route on purpose, and this test is deliberately the
     * DETERMINISTIC half: it needs no YouTube, so it runs on every emulator on every commit. The
     * seam under test is downstream of how the bytes arrived — "offline, a copy exists, does the
     * VIDEO branch find it" — and a real file with a real Room record proves that.
     *
     * The genuinely end-to-end version, where yt-dlp fetches a real video's audio and *that* is
     * played offline, is [LiveDownloadedVideoOfflineTest]: CI can reach YouTube from the emulator
     * through Dewi's home connection (`tools/ci/live-test-via-home.sh`), but that tunnel is allowed
     * to be absent, so the always-on guard cannot depend on it. Both, not either.
     *
     * The watch URL is a genuine YouTube one and stays untouched: if this branch ever regresses to
     * resolving, the test fails offline rather than passing quietly.
     */
    @Test
    fun `a downloaded video plays from disk with the radios off`() = runBlocking(Dispatchers.Main) {
        val forDownloading = hostedItem()
        downloads.download(forDownloading, audioOnly = true)
        val path = awaitDownloaded()
        assertTrue("setup: the copy must exist before the offline half means anything", path != null)

        goOffline()
        assertEquals("the radios did not actually go off", false, hasNetwork())

        // The SAME item id, queued as a video — which is how everything from a YouTube feed
        // arrives, and what the auto-downloader fetches audio for.
        queue.playNow(
            PlayableItem(
                item = forDownloading.item,
                handle = PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=aaaaaaaaaaa")),
            ),
        )
        awaitPlaying()

        assertTrue(
            "a downloaded video must play from its file offline. It played from " +
                "\"${lastSource()}\" — which offline can only mean the file it was NOT given",
            lastSource()?.contains(path!!) == true,
        )
        assertTrue(
            "offline playback of the downloaded video stalled. The player is on " +
                "${PlaybackWaits.whatIsActuallyPlaying(controller)}",
            awaitPositionBeyond(PROGRESS_MS),
        )
    }

    /** The local path once the download reports itself finished, or null on timeout. */
    private suspend fun awaitDownloaded(): String? = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
        var path: String? = null
        while (path == null) {
            delay(POLL_MS)
            path = (downloads.observe(ITEM_ID).first() as? DownloadState.Downloaded)?.localPath
        }
        path
    }

    /** What the player was actually handed — the assertion that separates local from streamed. */
    private fun lastSource(): String? =
        controller.player?.currentMediaItem?.localConfiguration?.uri?.toString()

    private suspend fun awaitControllerConnected() {
        val connected = withTimeoutOrNull(START_TIMEOUT_MS) {
            while (controller.player == null) delay(POLL_MS)
            true
        }
        assertEquals("the media controller never connected to the playback service", true, connected)
    }

    private suspend fun awaitPlaying() {
        val playing = PlaybackWaits.awaitStateOf(controller, ITEM_ID, START_TIMEOUT_MS) { it.isPlaying } != null
        assertEquals(
            "the downloaded item never started playing offline — if this is a focus problem the " +
                "app was not foreground; if not, offline playback is broken",
            true,
            playing,
        )
    }

    private suspend fun awaitPositionBeyond(target: Long): Boolean =
        PlaybackWaits.awaitStateOf(controller, ITEM_ID, START_TIMEOUT_MS) { it.positionMs > target } != null

    private fun serveUntilClosed() {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return
            thread(isDaemon = true) { runCatching { respond(socket) } }
        }
    }

    private fun respond(socket: Socket) {
        socket.use {
            val input = socket.getInputStream().bufferedReader()
            var line = input.readLine()
            while (!line.isNullOrBlank()) line = input.readLine()
            val out = socket.getOutputStream()
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: audio/wav\r\n" +
                        "Content-Length: ${media.size}\r\n\r\n"
                    ).toByteArray(),
            )
            out.write(media)
            out.flush()
        }
    }

    private fun hostedItem() = PlayableItem(
        item = MediaItem(
            id = ITEM_ID,
            sourceId = SourceId("test"),
            title = "an episode for the plane",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("http://127.0.0.1:${server.localPort}/episode.wav"),
        ),
        // No local path to begin with: earning one through the download path is the first phase.
        handle = PlayHandle.Podcast(),
    )

    private companion object {
        val ITEM_ID = MediaItemId("offline-episode")
        const val MEDIA_SECONDS = 30
        const val DOWNLOAD_TIMEOUT_MS = 60_000L
        const val START_TIMEOUT_MS = 30_000L
        const val PROGRESS_MS = 1_000L
        const val POLL_MS = 200L
        const val UNAVAILABLE_ID = "never-downloaded"

        /**
         * Well under the stall budget an attempted stream would burn (~60s), so a regression to
         * "try it anyway" fails here rather than merely feeling slow.
         */
        const val SKIP_TIMEOUT_MS = 20_000L
    }
}
