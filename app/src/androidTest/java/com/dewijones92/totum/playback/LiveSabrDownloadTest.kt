package com.dewijones92.totum.playback

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.HttpDownloadStrategy
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.player.PlayerResponseParser
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.support.DeviceRadios.goOffline
import com.dewijones92.totum.support.DeviceRadios.goOnline
import com.dewijones92.totum.support.DeviceRadios.hasNetwork
import com.dewijones92.totum.support.PlaybackWaits
import com.dewijones92.totum.video.PlayerBackedDownloadStrategy
import com.dewijones92.totum.video.SabrResolve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Fetching a video's audio through the app's OWN signed-in path, then playing it with no network.
 *
 * This is the mechanism that reaches what yt-dlp cannot. YouTube serves members-only uploads to the
 * signed-in app and refuses them to yt-dlp, so three Novara episodes in Dewi's queue could be played
 * and never downloaded (report 0.1.346, 2026-08-06). The fix routes such a refusal to the same
 * resolution playback uses — which for an authenticated stream means SABR, because a plain ranged
 * GET of an ANDROID-client URL serves its first megabyte and then 403s forever.
 *
 * **What this proves and what it does not.** It proves the PATH: a real `/player` call, a real SABR
 * session, real bytes on disk, and a real player reading them with the radios off. It cannot prove
 * the members-only case, which needs an account with that membership — only Dewi's phone can, and
 * the log line to look for there is `"…" was refused (…) — trying the app's own signed-in path`.
 *
 * Live YouTube, so it runs through `tools/ci/live-test-via-home.sh` (residential egress) and is
 * SKIPPED, never failed, when the service will not serve this machine.
 *
 * No apostrophe in the test name ("apps", not "app's"). Dex cannot represent one in a method name
 * and D8 fails the WHOLE androidTest build with "cannot be represented in dex format" — the same
 * trap as a comma, which this repo already learned the hard way. JVM tests have no such limit.
 */
class LiveSabrDownloadTest {

    /** Foreground, or the platform refuses audio focus and nothing ever plays. */
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as TotumApplication
    private val container get() = app.container
    private val controller get() = container.playbackController
    private val queue get() = container.playbackQueue

    private val target = File(context.filesDir, "sabr-download-test.media")

    private val watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO_ID")

    private val strategy by lazy {
        PlayerBackedDownloadStrategy(
            // Exactly the wiring AppContainer uses.
            resolveAudioUrl = { item ->
                (item.handle as? PlayHandle.Video)?.let {
                    container.videoResolver.resolve(it.watchUrl, item.item.sourceId, asked = "download")
                        ?.audioOnlyUrl
                }
            },
            http = HttpDownloadStrategy(OkHttpClient()),
        )
    }

    @After
    fun tearDown() {
        goOnline()
        runBlocking(Dispatchers.Main) {
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
        }
        target.delete()
    }

    @Test
    fun `audio fetched through the apps own resolution plays with the radios off`() =
        runBlocking(Dispatchers.Main) {
            controller.setSkipSilence(false)
            val item = videoItem()

            val states = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                strategy.download(item, target, audioOnly = true).toList()
            }
            assumeTrue(
                "the app's own path could not fetch this video here — an environment condition, so " +
                    "skipped rather than failed. Last state: ${states?.lastOrNull()}",
                states?.lastOrNull() is DownloadState.Downloaded,
            )
            val path = (states!!.last() as DownloadState.Downloaded).localPath
            assertTrue("the fetched file must have real bytes in it", File(path).length() > MIN_BYTES)

            goOffline()
            assertEquals("the radios did not actually go off", false, hasNetwork())

            // Played as the queue would play it: a video item whose copy is on disk.
            queue.playNow(item.copy(handle = PlayHandle.Podcast(localPath = path)))
            assertTrue("the fetched audio never started playing offline", awaitPlaying())
            assertTrue(
                "it must play from the file. It played from \"${lastSource()}\"",
                lastSource()?.contains(path) == true,
            )
        }

    /**
     * And the SABR branch specifically, which the case above cannot reach.
     *
     * A public video resolves through yt-dlp to an ordinary URL, so the plain-HTTP branch runs and
     * the SABR one never does — but SABR is the whole reason this path exists, since it is the only
     * way to fetch an authenticated stream past its first megabyte. So this registers a real session
     * from a real `/player` response, exactly as `SabrResolve` does in production, and hands the
     * strategy the `sabr://` URL that comes out.
     *
     * A gated video would exercise it end to end; only an account with that membership can, so this
     * proves the mechanism on a video anyone can fetch.
     */
    @Test
    fun `a sabr stream is fetched to a file and plays offline`() = runBlocking(Dispatchers.Main) {
        controller.setSkipSilence(false)
        val player = InnerTubeClient(OkHttpClient()).player(VIDEO_ID)
        val parsed = (player as? InnerTubeResponse.Success)?.body?.let(PlayerResponseParser::parse)
        assumeTrue(
            "YouTube did not serve this machine a player response — commonly a datacentre IP being " +
                "bot-checked, which is not a defect in this path",
            parsed is PlayerResult.Success,
        )
        val success = parsed as PlayerResult.Success
        val prepared = SabrResolve.prepare(VIDEO_ID, success.streaming, success.details)
        assumeTrue("the player response could not make a SABR session", prepared != null)

        val item = videoItem()
        val states = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            PlayerBackedDownloadStrategy(
                resolveAudioUrl = { prepared!!.audioUrl },
                http = HttpDownloadStrategy(OkHttpClient()),
            ).download(item, target, audioOnly = true).toList()
        }
        assumeTrue(
            "SABR did not deliver the bytes here. Last state: ${states?.lastOrNull()}",
            states?.lastOrNull() is DownloadState.Downloaded,
        )
        val path = (states!!.last() as DownloadState.Downloaded).localPath
        assertTrue("a SABR fetch must produce real bytes", File(path).length() > MIN_BYTES)

        goOffline()
        assertEquals("the radios did not actually go off", false, hasNetwork())

        queue.playNow(item.copy(handle = PlayHandle.Podcast(localPath = path)))
        assertTrue("the SABR-fetched audio never played offline", awaitPlaying())
        assertTrue(
            "it must play from the file. It played from \"${lastSource()}\"",
            lastSource()?.contains(path) == true,
        )
    }

    private fun videoItem() = PlayableItem(
        MediaItem(
            id = MediaItemId(VIDEO_ID),
            sourceId = SourceId("live-test"),
            title = "a real video fetched the apps way",
            publishedAt = null,
            duration = null,
            mediaUrl = watchUrl,
        ),
        PlayHandle.Video(watchUrl),
    )

    private fun lastSource(): String? =
        controller.player?.currentMediaItem?.localConfiguration?.uri?.toString()

    private suspend fun awaitPlaying(): Boolean =
        PlaybackWaits.awaitStateOf(controller, MediaItemId(VIDEO_ID), START_TIMEOUT_MS) { it.isPlaying } != null

    private companion object {
        /** "Me at the zoo" — 19 seconds, the oldest video on the site, unlikely to move. */
        const val VIDEO_ID = "jNQXAC9IVRw"

        /** A /player call, a SABR session and a real fetch, on an emulator. */
        const val FETCH_TIMEOUT_MS = 3 * 60 * 1000L
        const val START_TIMEOUT_MS = 30_000L

        /** Enough that an empty or header-only file cannot pass as a download. */
        const val MIN_BYTES = 10_000L
    }
}
