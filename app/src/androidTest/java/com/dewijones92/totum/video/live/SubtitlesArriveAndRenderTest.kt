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
        assertTrue("nothing played, so there is nothing to caption. Trail:\n${trail()}", playing)

        val tracks = withTimeoutOrNull(TRACKS_TIMEOUT_MS) {
            while (controller.state.value?.subtitles.isNullOrEmpty()) delay(POLL_MS)
            controller.state.value?.subtitles
        }.orEmpty()
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
