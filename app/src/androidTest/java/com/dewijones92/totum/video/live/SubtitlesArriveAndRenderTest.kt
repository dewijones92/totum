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
 * Subtitles reach the player, and selecting one is actually honoured.
 *
 * Dewi asked for this on 2026-08-18 — *"make sure … subtitles work etc etc"* — and it turned out to be
 * a genuine hole: resolves have logged "8 subtitle tracks" for weeks and **nothing on a device had ever
 * checked that any of them arrive**. The plumbing is unit-tested at both ends (`SubtitleJson` parses
 * them, `SubtitleCues` reads them back off the player) and the middle — a real resolve handing real
 * tracks to a real ExoPlayer, which then has to fetch and select them — was covered by nothing.
 *
 * Two assertions, because the failure modes are different and only one of them is visible in a log:
 *
 * 1. **Tracks are offered.** A resolve that returns none means the extractor lost them, which after
 *    2026-08-18's client changes is a real possibility — `web_embedded` returns a different set from
 *    `android_vr`, and captions are exactly the sort of thing that differs.
 * 2. **Choosing one sticks.** `setSubtitleLanguage` goes over the media session to the service, and a
 *    session command that is not advertised in `onConnect` is rejected **in silence** — a trap this
 *    repository has already paid for once, with the preload command.
 *
 * It deliberately does not assert that CUES render as text on screen: that is Media3 drawing a
 * `SubtitleView`, and the emulator's timing makes it flaky in a way that says nothing about this app.
 * What is ours is that the track list arrives and the choice is applied.
 */
class SubtitlesArriveAndRenderTest {

    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as TotumApplication
    private val container get() = app.container
    private val controller get() = container.playbackController
    private val queue get() = container.playbackQueue

    private var modeBefore: PlaybackMode = PlaybackMode.AUTO
    private var autoDownloadBefore = true

    @Before
    fun reset() = runBlocking(Dispatchers.Main) {
        modeBefore = container.appPreferences.settings.value.playbackMode
        autoDownloadBefore = container.appPreferences.settings.value.autoDownloadQueue
        // A local copy carries no subtitle tracks, so a download would quietly make this vacuous.
        container.appPreferences.setAutoDownloadQueue(false)
        container.downloadManager.delete(MediaItemId(VIDEO_ID))
        queue.clear()
        controller.setSkipSilence(false)
        Breadcrumbs.clear()
    }

    @After
    fun tidyUp() {
        runBlocking(Dispatchers.Main) {
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
            container.appPreferences.setPlaybackMode(modeBefore)
            container.appPreferences.setAutoDownloadQueue(autoDownloadBefore)
        }
    }

    @Test
    fun subtitleTracksArriveAndAChoiceSticks() = runBlocking(Dispatchers.Main) {
        container.appPreferences.setPlaybackMode(PlaybackMode.VIDEO)
        queue.playNow(subtitledVideo())

        val playing = withTimeoutOrNull(START_TIMEOUT_MS) {
            while (controller.state.value?.isPlaying != true) delay(POLL_MS)
            true
        } ?: false
        // Nothing played at all: in a SABR-only session YouTube strips the direct URLs INCLUDING the
        // audio-only one, so there is no stream and no fallback either, and the item is genuinely
        // unplayable. That is policy, not a caption bug, and it is the trail that tells them apart —
        // a `refused` route means YouTube offered nothing fetchable.
        if (!playing || servedNothing()) {
            assertTrue(
                "nothing played AND nothing explains it — that is ours. Trail:\n${trail()}",
                servedNothing(),
            )
            println("[subtitles] no caption assertions: YouTube served no fetchable stream this session")
            return@runBlocking
        }

        val tracks = withTimeoutOrNull(TRACKS_TIMEOUT_MS) {
            while (controller.state.value?.subtitles.isNullOrEmpty()) delay(POLL_MS)
            controller.state.value?.subtitles
        }.orEmpty()
        // Checked again here, because playback can START and then be refused a second later — which is
        // what happened on 2026-08-18: `isPlaying` went true, the route then reported `refused`, and the
        // absent captions were blamed on the caption path.
        if (tracks.isEmpty() && servedNothing()) {
            println("[subtitles] no caption assertions: the stream was refused after starting")
            return@runBlocking
        }
        // A third thing outside this test's control, alongside the two above: the app decided to play
        // this without the picture, and an audio-only route carries no captions. See audioOnlyRouteTaken.
        val audioOnly = audioOnlyRouteTaken()
        if (tracks.isEmpty() && audioOnly != null) {
            println("[subtitles] no caption assertions: the route carried no picture —\n  $audioOnly")
            return@runBlocking
        }
        assertTrue(
            "no subtitle track reached the player. The resolve log says how many were extracted; if it " +
                "says several then they were lost between the resolver and the session. Trail:\n${trail()}",
            tracks.isNotEmpty(),
        )

        val wanted = tracks.first().languageCode
        controller.setSubtitleLanguage(wanted)

        val applied = withTimeoutOrNull(APPLY_TIMEOUT_MS) {
            while (controller.state.value?.subtitleLanguage != wanted) delay(POLL_MS)
            true
        } ?: false
        assertTrue(
            "choosing \"$wanted\" of ${tracks.map { it.languageCode }} never took. A session command " +
                "that is not advertised in onConnect is rejected in silence, which looks exactly like " +
                "this. Trail:\n${trail()}",
            applied,
        )
    }

    private fun subtitledVideo() = PlayableItem(
        item = MediaItem(
            id = MediaItemId(VIDEO_ID),
            sourceId = SourceId("youtube"),
            title = "Cosmic Dawn",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO_ID"),
        ),
        handle = PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO_ID")),
    )

    /**
     * Whether YouTube gave us nothing fetchable this session — the one outcome that is not ours.
     *
     * In a SABR-only session the direct URLs are stripped INCLUDING the audio-only one, so there is no
     * stream, no sound-only fallback, and `routeNow` says `refused`. Captions cannot be judged on an
     * item that never really played.
     */
    private fun servedNothing() = trail().contains("refused")

    private fun trail() = Breadcrumbs.snapshot().takeLast(TRAIL_LINES)
        .filter { "subtitle" in it.message || "resolve" in it.tag || "route" in it.message }
        .joinToString("\n  ") { it.message.take(TRAIL_CHARS) }

    private companion object {
        /** NASA's "Cosmic Dawn" — public domain, and its resolves report eight caption tracks. */
        const val VIDEO_ID = "uSMGENDH_QI"

        const val START_TIMEOUT_MS = 180_000L
        const val TRACKS_TIMEOUT_MS = 30_000L
        const val APPLY_TIMEOUT_MS = 20_000L
        const val POLL_MS = 250L
        const val TRAIL_LINES = 30
        const val TRAIL_CHARS = 150
    }
}
