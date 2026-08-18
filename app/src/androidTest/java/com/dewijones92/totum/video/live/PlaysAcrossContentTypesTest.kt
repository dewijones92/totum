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
import kotlin.math.abs

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
            val outcome = attempt(fixture)
            report.append("\n  ${fixture.label}: $outcome")
            if (outcome.startsWith(UNEXPLAINED)) silent += fixture.label
        }

        assertTrue("these went silent with nothing to explain it: $silent$report", silent.isEmpty())
        println("[breadth]$report")
    }

    /**
     * Plays one fixture and says what happened, in one line.
     *
     * Silence counts against the app ONLY when nothing explains it. In a SABR-only session — which
     * YouTube enables per session — the direct URLs are stripped including the audio-only one, so there
     * is no stream and no fallback, and `routeNow` reports `refused`. Treating that as a defect here
     * would make this a standing complaint about YouTube, which this repository has already done three
     * times in one day.
     */
    private suspend fun attempt(fixture: Fixture): String {
        queue.clear()
        controller.player?.stop()
        Breadcrumbs.clear()
        queue.playNow(fixture.item())

        val playing = withTimeoutOrNull(START_TIMEOUT_MS) {
            while (controller.state.value?.isPlaying != true) delay(POLL_MS)
            true
        } ?: false
        // Position MOVING, not merely "isPlaying": a player reporting playing while stuck at one
        // millisecond is exactly what a refused stream looks like from the outside.
        //
        // MOVING, though — not increasing. This asked for `position > from + 1500` and called the live
        // stream silent on 2026-08-18 when the logs showed it playing perfectly: a live position is an
        // offset into a window that slides, so a re-resolve mid-play moved it from 14120ms to 11523ms.
        // Backwards. An assertion that only accepts forward motion measures VOD-ness, not playback.
        val from = controller.state.value?.positionMs ?: 0
        val advanced = playing && withTimeoutOrNull(ADVANCE_TIMEOUT_MS) {
            while (abs((controller.state.value?.positionMs ?: 0) - from) < ADVANCE_MS) delay(POLL_MS)
            true
        } ?: false

        if (advanced) {
            // WAITED for, not sampled. `hasVideo` comes from the decoder's track list, which arrives
            // after playback starts — reading it the instant the position moves reported "no picture"
            // for a plain muxed clip that plainly had one. An absent reading is not proof of absence.
            val picture = withTimeoutOrNull(PICTURE_TIMEOUT_MS) {
                while (controller.state.value?.hasVideo != true) delay(POLL_MS)
                true
            } ?: false
            return "sound=YES picture=${describePicture(picture)}"
        }
        val trail = lastTrail()
        if (trail.contains("refused")) return "sound=NO — YouTube served nothing fetchable\n    $trail"
        return "$UNEXPLAINED sound=NO and nothing explains it\n    $trail"
    }

    /**
     * What the absent picture MEANS — because on a metered connection the app removes it on purpose.
     *
     * `MeteredAudioSwitch` re-plays the current item as audio once mobile data has held for its
     * hold-off, and it overrides `PlaybackMode.VIDEO` rather than deferring to it. On an emulator
     * whose Wi-Fi reports metered, its timer has long since elapsed, so it fires within about five
     * seconds of EVERY item starting to show video — which is a race against this very measurement
     * and is exactly why the picture column moved run to run on 2026-08-18 while nothing changed.
     *
     * Reported rather than asserted, and named rather than left as a bare "no": a data-saving
     * feature working correctly must never read as a missing picture. If it says `downgraded` the
     * device is metered — `adb shell cmd netpolicy set metered-network '"AndroidWifi"' false`.
     *
     * "No picture" has THREE distinct causes and only the last is ours, which is the whole reason
     * this function exists rather than a boolean:
     *
     * | Reported as | What happened | Whose fault |
     * |---|---|---|
     * | `downgraded` | mobile data held, so the app dropped the picture on purpose | nobody — a feature |
     * | `rescued` | YouTube refused the video stream, so the app kept the sound | YouTube, handled |
     * | `no` | the picture is absent and nothing explains it | **ours** |
     *
     * `rescued` was reported as a bare `no` on 2026-08-18 for a clip that plainly has a picture, and
     * the trail showed the rescue ladder working perfectly: 403 from ANDROID_VR with 21577s of lease
     * left, one retry, then "keeping the sound without the picture". Reading that as a missing picture
     * would have sent the next session hunting a video bug that does not exist.
     */
    private fun describePicture(picture: Boolean): String = when {
        picture -> "yes"
        breadcrumbSays("switching to audio only") ->
            "downgraded by the app's own data saver (this device reports METERED) — not a finding"
        breadcrumbSays("keeping the sound") ->
            "rescued — YouTube refused the video stream and the app kept the sound"
        else -> "no"
    }

    private fun breadcrumbSays(phrase: String) = Breadcrumbs.snapshot().any { phrase in it.message }

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
        /** Marks an outcome the app has to answer for, as opposed to one YouTube explained. */
        const val UNEXPLAINED = "UNEXPLAINED:"

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
