package com.dewijones92.totum.video.live

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
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
import org.junit.Rule
import org.junit.Test

/**
 * **Sound must work on everything.** The picture is reported, not required.
 *
 * Dewi, 2026-08-18: *"make sure it works across a range of resolutions, and videos, live streams, ms
 * rachael etc etc … and make sure the sound works etc etc on each, picture + sounds"*.
 *
 * The asymmetry between the two assertions is not laziness, it is what the measurements support. YouTube
 * now caps unattested clients at roughly a megabyte, and the formats that escape it are **audio**: a
 * census taken on this emulator for a 97-minute video found 37 audio formats of which 33 carried a
 * solved `n`, against 23 video formats of which **none** did — every one `c=ANDROID_VR`. A fresh laptop
 * extraction of the same video agreed (19 video, 0 durable), which is what settled it: this is YouTube's
 * position, not our bug. So sound is a promise the app can keep and the picture is not, and a test that
 * demanded both would be a standing complaint about the world rather than a guard on this code — see
 * `docs/todos/youtube-requires-attestation.md`.
 *
 * What it therefore guards is the thing that would actually ruin the app: **any content type going
 * silent.** Each case is a different shape that has broken before or could:
 *
 * | Fixture | Why it is here |
 * |---|---|
 * | 19-second clip | the whole file fits inside the cap, so nothing exercises recovery |
 * | 97-minute VOD | needs the sound-only fallback to survive at all |
 * | Ms Rachel | made-for-kids served NO playable stream to any default client (2026-07-30); the
 *   app's `android` client and its QuickJS both exist because of it |
 * | live stream | no audio-only format at all and nothing durable, so the fallback has nothing to
 *   fall back TO — the weakest case, and the likeliest to be silent |
 *
 * Each reports what it got, so a future run can tell "the picture came back" from "the sound went away".
 */
class PlaysAcrossContentTypesTest {

    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as TotumApplication
    private val container get() = app.container
    private val controller get() = container.playbackController
    private val queue get() = container.playbackQueue

    private var modeBefore: PlaybackMode = PlaybackMode.AUTO
    private var autoDownloadBefore: Boolean = true

    @Before
    fun reset() = runBlocking(Dispatchers.Main) {
        modeBefore = container.appPreferences.settings.value.playbackMode
        // Auto-download OFF for the duration, or the measurement measures the wrong thing. `playNow`
        // puts the item in the queue, the queue fetches its AUDIO, and once a stream is refused
        // `routeNow` quite rightly prefers that fresh local copy — so "no picture" would mean "we had a
        // download", not "YouTube would not serve the video". Correct behaviour, useless as evidence.
        autoDownloadBefore = container.appPreferences.settings.value.autoDownloadQueue
        container.appPreferences.setAutoDownloadQueue(false)
        // BEFORE as well as after. Another live test downloads `jNQXAC9IVRw`, and a leftover copy makes
        // this one play a file instead of the stream — which reported "no picture" for a clip that has
        // one, because the copy is audio-only. Tidying up after yourself does not protect you from
        // whoever ran first.
        FIXTURES.forEach { container.downloadManager.delete(MediaItemId(it.id)) }
        queue.clear()
        controller.setSkipSilence(false)
        controller.setSpeed(1f)
    }

    @After
    fun tidyUp() {
        runBlocking(Dispatchers.Main) {
            FIXTURES.forEach { container.downloadManager.cancel(MediaItemId(it.id)) }
            FIXTURES.forEach { container.downloadManager.delete(MediaItemId(it.id)) }
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
            container.appPreferences.setPlaybackMode(modeBefore)
            container.appPreferences.setAutoDownloadQueue(autoDownloadBefore)
        }
    }

    @Test
    fun everyContentTypeMakesSound() = runBlocking(Dispatchers.Main) {
        container.appPreferences.setPlaybackMode(PlaybackMode.VIDEO)
        val report = StringBuilder()
        val silent = mutableListOf<String>()

        FIXTURES.forEach { fixture ->
            queue.clear()
            controller.player?.stop()
            Breadcrumbs.clear()
            queue.playNow(fixture.item())

            val playing = withTimeoutOrNull(START_TIMEOUT_MS) {
                while (controller.state.value?.isPlaying != true) delay(POLL_MS)
                true
            } ?: false
            // Position ADVANCING, not merely "isPlaying". A player reporting playing while stuck at one
            // millisecond is exactly what a refused stream looks like from the outside.
            val from = controller.state.value?.positionMs ?: 0
            val advanced = playing && withTimeoutOrNull(ADVANCE_TIMEOUT_MS) {
                while ((controller.state.value?.positionMs ?: 0) < from + ADVANCE_MS) delay(POLL_MS)
                true
            } ?: false

            // WAITED for, not sampled. `hasVideo` comes from the decoder's track list, which arrives
            // after playback starts — reading it the instant the position moves reported "no picture"
            // for a plain muxed clip that plainly had one. An absent reading is not proof of absence.
            val picture = withTimeoutOrNull(PICTURE_TIMEOUT_MS) {
                while (controller.state.value?.hasVideo != true) delay(POLL_MS)
                true
            } ?: false
            report.append(
                "\n  ${fixture.label}: sound=${if (advanced) "YES" else "NO"} picture=${if (picture) "yes" else "no"}",
            )
            if (!advanced) {
                silent += fixture.label
                report.append("\n    trail: ").append(lastTrail())
            }
        }

        assertTrue("these went SILENT: $silent$report", silent.isEmpty())
        println("[breadth]$report")
    }

    private fun lastTrail() = Breadcrumbs.snapshot().takeLast(TRAIL_LINES)
        .filter { "route" in it.message || "stream " in it.message || "sound" in it.message }
        .joinToString("\n      ") { it.message.take(TRAIL_CHARS) }

    /** One YouTube item, and why it is worth playing. */
    private class Fixture(val id: String, val label: String)

    private fun Fixture.item() = PlayableItem(
        item = MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("youtube"),
            title = label,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
        ),
        handle = PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
    )

    private companion object {
        val FIXTURES = listOf(
            Fixture("jNQXAC9IVRw", "19-second clip"),
            Fixture("uSMGENDH_QI", "97-minute VOD"),
            Fixture("gngPQ771Ahk", "Ms Rachel (made-for-kids)"),
            Fixture("YDvsBbKfLPA", "live stream"),
        )

        /** A cold resolve with a QuickJS `n` solve has been measured at 25s; four of them need room. */
        const val START_TIMEOUT_MS = 180_000L
        const val ADVANCE_TIMEOUT_MS = 60_000L

        /** Enough movement to be playback rather than a seek settling. */
        const val ADVANCE_MS = 1_500L

        /** Long enough for the track list to arrive, short enough that "no picture" still means no. */
        const val PICTURE_TIMEOUT_MS = 15_000L
        const val POLL_MS = 250L
        const val TRAIL_LINES = 30
        const val TRAIL_CHARS = 150
    }
}
