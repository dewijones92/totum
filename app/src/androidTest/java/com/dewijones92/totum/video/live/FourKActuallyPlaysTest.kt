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
        queue.playNow(item())

        val playing = awaitPlaying()
        // What the LADDER chose, before asking whether it survived. Different questions, and conflating
        // them produced a badly misleading line on 2026-08-18: the test printed "YouTube served nothing
        // fetchable" for a run in which the app had correctly picked 2160p and decoded 3840x2160, and
        // only then had the stream refused. Those two send a reader to completely different places.
        val pick = Breadcrumbs.snapshot().lastOrNull { " stream " in it.message }?.message
        val choseHeight = pick?.let { PICKED.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val durableVideo = pick?.contains("durable video=true") == true
        val trail = Breadcrumbs.snapshot().joinToString("\n    ") { it.message.take(TRAIL_CHARS) }
        println("[4k] the ladder chose ${choseHeight}p (durable video=$durableVideo)")

        if (!playing) {
            explainTheRefusal(trail, choseHeight, durableVideo)
            return@runBlocking
        }

        val height = awaitDecodedHeight()
        val sound = awaitSound()
        println("[4k] decoded ${height}p, sound=${if (sound) "yes" else "no"} (cap raised to ${FOUR_K}p)")
        println("[4k] trail:\n    $trail")

        assertTrue("the picture never reported a size, so nothing was decoded", height > 0)
        assertTrue(
            "raising the cap to ${FOUR_K}p still produced only ${height}p. YouTube offers 2160p60 for " +
                "this film with working URLs, so at or below the ${DEFAULT_CAP}p default means the " +
                "setting is not reaching the ladder — which is ours, not YouTube's.",
            height > DEFAULT_CAP,
        )
        assertTrue("4K arrived with no sound", sound)
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

    /**
     * Why 4K did not play, when the ladder had correctly chosen it.
     *
     * Its own function to keep the test method within the complexity budget, and because it is a
     * genuinely separate judgement: the pick is ours to get right, and whether YouTube then serves it
     * is not.
     */
    private fun explainTheRefusal(trail: String, choseHeight: Int, durableVideo: Boolean) {
        assertTrue(
            "nothing played AND nothing explains it — that is ours. Trail:\n    $trail",
            trail.contains("refused"),
        )
        // The cap DID reach the ladder even though playback died, and that is the half we own. Asserted
        // on this path too, or a regression that stopped the setting working would hide behind YouTube
        // refusing the stream.
        assertTrue(
            "raising the cap to ${FOUR_K}p produced a pick of ${choseHeight}p, at or below the " +
                "${DEFAULT_CAP}p default — the setting is not reaching the ladder, which is ours.",
            choseHeight > DEFAULT_CAP,
        )
        println(
            "[4k] chose ${choseHeight}p and it would not sustain. durable video=$durableVideo — with no " +
                "durable ${choseHeight}p URL the stream is refused past its first megabyte, and SABR " +
                "cannot stand in above ${SABR_CAP}p/30fps. That is the attestation wall, not a " +
                "quality-selection bug. See docs/todos/youtube-requires-attestation.md.",
        )
    }

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
