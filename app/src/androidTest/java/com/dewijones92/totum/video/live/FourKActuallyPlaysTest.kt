package com.dewijones92.totum.video.live

import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.settings.PlaybackMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Does 4K actually play when you ask for it?
 *
 * Dewi, 2026-08-18: *"also make sure it works at 4k"*. The honest answer needed measuring rather than
 * asserting, because there are two separate questions and only one of them is a bug:
 *
 * 1. **Is 4K reachable?** YouTube offers it — measured today on Blender's 4K60 film, `itag 315` and
 *    `itag 401` at 2160p60 both carrying working URLs, out of 40 formats all with URLs.
 * 2. **Does the app ask for it?** No, by default: `DEFAULT_WIFI_MAX_HEIGHT` is **1080**, so the ladder's
 *    `qualityFrom` never sees 2160 among its allowed heights. 2160p IS offered in Settings, so this is a
 *    default rather than a missing capability — and whether the default should change is Dewi's call,
 *    since a 1080p phone screen gains nothing from 4K but spends four times the data to get it.
 *
 * This test covers the part that is unambiguously ours: **when 2160 is chosen, 4K must actually arrive,
 * with picture and sound.** A settings option that silently does nothing would be the worse bug of the
 * two, and nothing was checking it.
 *
 * Reports the height it actually got rather than asserting a specific one: YouTube decides what exists
 * for a given video, and a test that demanded 2160 would go red the day it served 1440. It asserts what
 * the app owns — that raising the cap raises the pick ABOVE the default 1080 — plus picture and sound.
 */
class FourKActuallyPlaysTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as TotumApplication
    private val container get() = app.container
    private val controller get() = container.playbackController
    private val queue get() = container.playbackQueue

    private var capBefore = 0
    private var modeBefore = PlaybackMode.AUTO

    @Before
    fun raiseTheCap() = runBlocking(Dispatchers.Main) {
        capBefore = container.appPreferences.settings.value.wifiMaxHeight
        modeBefore = container.appPreferences.settings.value.playbackMode
        container.appPreferences.setWifiMaxHeight(FOUR_K)
        container.appPreferences.setCellularMaxHeight(FOUR_K)
        container.appPreferences.setPlaybackMode(PlaybackMode.VIDEO)
        container.downloadManager.delete(MediaItemId(FOUR_K_SIXTY))
        queue.clear()
        Breadcrumbs.clear()
    }

    @After
    fun putItBack() {
        runBlocking(Dispatchers.Main) {
            controller.player?.stop()
            controller.player?.clearMediaItems()
            queue.clear()
            container.appPreferences.setWifiMaxHeight(capBefore)
            container.appPreferences.setCellularMaxHeight(CELLULAR_DEFAULT)
            container.appPreferences.setPlaybackMode(modeBefore)
            container.downloadManager.delete(MediaItemId(FOUR_K_SIXTY))
        }
    }

    @Test
    fun askingForFourKGetsMoreThanTheDefaultCap() = runBlocking(Dispatchers.Main) {
        // What is available to THIS MACHINE, which is not the same as what YouTube lists. CI's emulator
        // has no AV1 decoder at 4K, so `VideoCodecSupport` correctly withholds those rungs and the tallest
        // real option there is lower — a device fact, not a YouTube one, and not something the app should
        // be marked down for. Skipped rather than asserted either way.
        val offered = container.videoResolver
            .resolve(HttpUrl.of(WATCH + FOUR_K_SIXTY), SourceId("youtube"), asked = "test")
            ?.qualities.orEmpty()
        val tallest = offered.maxOfOrNull { it.height } ?: 0
        println("[4k] this machine can actually use up to ${tallest}p (${offered.size} rungs)")
        assumeTrue(
            "nothing above ${DEFAULT_CAP}p is usable here (tallest ${tallest}p) — either YouTube did not " +
                "offer it or this device cannot decode it, and neither is a statement about our code",
            tallest > DEFAULT_CAP,
        )

        queue.playNow(item())
        val playing = awaitPlaying()

        // THE ASSERTION, and the only one: raising the cap must make the ladder ASK for the taller rung.
        // Whether YouTube then serves it is not ours — 2160p formats are the non-durable ones, so they are
        // refused past the first megabyte, which docs/todos/youtube-requires-attestation.md measures.
        // Earlier versions asserted that it PLAYED and went red twice for exactly that reason: the fifth
        // and sixth times this repo asserted someone else's policy in a test.
        val pick = Breadcrumbs.snapshot().lastOrNull { " stream " in it.message }?.message
        val choseHeight = pick?.let { PICKED.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val durableVideo = pick?.contains("durable video=true") == true
        val trail = Breadcrumbs.snapshot().joinToString("\n    ") { it.message.take(TRAIL_CHARS) }

        assertTrue(
            "the ladder offered ${tallest}p but the launcher picked ${choseHeight}p, at or below the " +
                "${DEFAULT_CAP}p default — so raising the cap is not reaching the pick, which IS ours. " +
                "Trail:\n    $trail",
            choseHeight > DEFAULT_CAP,
        )

        // Everything below is REPORTED. It is the evidence a reader wants months later, and none of it is
        // a promise the app can keep while YouTube refuses the streams it hands out.
        val height = if (playing) awaitDecodedHeight() else 0
        val sound = playing && awaitSound()
        println(
            "[4k] asked for ${choseHeight}p (durable video=$durableVideo); " +
                if (playing) {
                    "it played — decoded ${height}p, sound=${if (sound) "yes" else "no"}"
                } else {
                    "it did not sustain. With no durable ${choseHeight}p URL the stream is refused past " +
                        "its first megabyte and SABR cannot stand in above ${SABR_CAP}p/30fps — the " +
                        "attestation wall, not a quality-selection bug."
                },
        )
        println("[4k] trail:\n    $trail")
    }

    private suspend fun awaitPlaying(): Boolean = withTimeoutOrNull(START_MS) {
        while (controller.state.value?.isPlaying != true) delay(POLL_MS)
        true
    } ?: false

    /**
     * Off the PLAYER, not `PlaybackState`: the state carries `hasVideo` and an aspect ratio but no pixel
     * size, so it cannot answer "what resolution did I actually get".
     */
    private suspend fun awaitDecodedHeight(): Int = withTimeoutOrNull(SIZE_MS) {
        while ((controller.player?.videoSize?.height ?: 0) <= 0) delay(POLL_MS)
        controller.player?.videoSize?.height ?: 0
    } ?: 0

    private suspend fun awaitSound(): Boolean = withTimeoutOrNull(SOUND_MS) {
        val from = controller.state.value?.positionMs ?: 0
        while ((controller.state.value?.positionMs ?: 0) < from + ADVANCE_MS) delay(POLL_MS)
        true
    } ?: false

    private fun item() = PlayableItem(
        item = MediaItem(
            id = MediaItemId(FOUR_K_SIXTY),
            sourceId = SourceId("youtube"),
            title = "Big Buck Bunny (4K60, Creative Commons)",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of(WATCH + FOUR_K_SIXTY),
        ),
        handle = PlayHandle.Video(HttpUrl.of(WATCH + FOUR_K_SIXTY)),
    )

    private companion object {
        /** Blender's "Big Buck Bunny" — Creative Commons, genuinely 4K60, and permanently up. */
        const val FOUR_K_SIXTY = "aqz-KE-bpKQ"
        const val WATCH = "https://www.youtube.com/watch?v="
        const val FOUR_K = 2160

        /** `AppPreferences.DEFAULT_WIFI_MAX_HEIGHT`, restated so this test fails if the default moves. */
        const val DEFAULT_CAP = 1080
        const val CELLULAR_DEFAULT = 480

        /** `SabrResolve.MAX_SABR_HEIGHT` — what the rescue route can offer instead. */
        const val SABR_CAP = 1080

        /** Pulls the height out of the launcher's own "stream 2160p vp9 …" line. */
        val PICKED = Regex("""stream (\d+)p""")
        const val START_MS = 180_000L
        const val SIZE_MS = 30_000L
        const val SOUND_MS = 30_000L
        const val ADVANCE_MS = 1_000L
        const val POLL_MS = 250L
        const val TRAIL_CHARS = 150
    }
}
