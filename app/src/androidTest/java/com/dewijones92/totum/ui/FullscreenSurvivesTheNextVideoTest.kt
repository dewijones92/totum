package com.dewijones92.totum.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.playback.SleepTimerState
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.player.ChosenBrightness
import com.dewijones92.totum.ui.player.CommentReplies
import com.dewijones92.totum.ui.player.FullPlayerOverlay
import com.dewijones92.totum.ui.player.PlaybackToggles
import com.dewijones92.totum.ui.player.QualityControl
import com.dewijones92.totum.ui.player.QueueControls
import com.dewijones92.totum.ui.player.WatchActions
import com.dewijones92.totum.ui.player.WatchViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fullscreen must survive the whole gap between one video and the next — including a stumble.
 *
 * Dewi, 2026-08-09: *"it went from playing one of the openai videos to another video but the full
 * screen didn't remain"*. Report 0.1.374 has the mechanism end to end: the item ended at
 * 18:52:31.9, the next one's stream came back `ERROR_CODE_IO_BAD_HTTP_STATUS … Expired` at
 * 18:52:35.6, the player went idle — no video track, and no longer buffering — and fullscreen was
 * dropped 1.1s later. The re-resolve finished at 18:52:39.2 and the video played on, windowed.
 *
 * The states below are the real ones from that report, in order and with the real gaps between
 * them. It is an instrumented test because the decision is a `LaunchedEffect` with a delay inside
 * a real composition, and the thing being asserted is which subtree is on screen afterwards.
 */
@RunWith(AndroidJUnit4::class)
class FullscreenSurvivesTheNextVideoTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var player: ExoPlayer
    private var state by mutableStateOf(playingVideo("87DyyMV0kCY"))

    @Before
    fun setUp() {
        composeTestRule.runOnUiThread { player = ExoPlayer.Builder(composeTestRule.activity).build() }
    }

    /**
     * Called from the test body, not from `@Before`.
     *
     * Setting the content in `@Before` left every test failing with "No compose hierarchies found
     * in the app" — the rule had not finished bringing its activity up. Every other instrumented
     * test here sets content inside the test for the same reason.
     */
    private fun start() {
        composeTestRule.setContent {
            TotumTheme {
                FullPlayerOverlay(
                    state = state,
                    player = player,
                    comments = WatchViewModel.CommentsState.Loaded(emptyList()),
                    replies = CommentReplies.None,
                    related = WatchViewModel.RelatedState.Loaded(emptyList()),
                    watchActions = WatchActions.ReadOnly,
                    quality = QualityControl.None,
                    sleepTimer = SleepTimerState.Off,
                    onDismiss = {},
                    onPlayRelated = {},
                    onStartSleep = {},
                    onStopSleepAfterItem = {},
                    onCancelSleep = {},
                    onTogglePlayPause = {},
                    onSeekTo = {},
                    onSeekBackward = {},
                    onSeekForward = {},
                    onSetSpeed = {},
                    onSetSubtitleLanguage = {},
                    onMore = {},
                    toggles = PlaybackToggles(),
                    queue = QueueControls.None,
                )
            }
        }
    }

    @After
    fun tearDown() {
        composeTestRule.runOnUiThread { player.release() }
        ChosenBrightness.forget()
    }

    private fun enterFullscreen() {
        start()
        composeTestRule.onNodeWithContentDescription(ENTER).performClick()
        composeTestRule.waitForIdle()
        assertStillFullscreen("could not get into fullscreen to begin with")
    }

    /**
     * Fullscreen shows the EXIT affordance; windowed shows the ENTER one.
     *
     * [why] is re-thrown with the failure because "a node was not displayed" cannot say which of
     * the four moments in the sequence lost it, and that is the whole diagnosis.
     */
    private fun assertStillFullscreen(why: String) {
        runCatching {
            composeTestRule.onNodeWithContentDescription(EXIT).assertIsDisplayed()
            composeTestRule.onNodeWithContentDescription(ENTER).assertDoesNotExist()
        }.onFailure { throw AssertionError("no longer fullscreen: $why", it) }
    }

    /**
     * Asserted by the EXIT affordance being gone, not by the ENTER one arriving: an audio item has
     * no video stage windowed, so it offers no way INTO fullscreen and looking for one would fail
     * whatever happened. The title is checked too, so a tree that vanished entirely cannot pass.
     */
    private fun assertNoLongerFullscreen(title: String) {
        composeTestRule.onNodeWithContentDescription(EXIT).assertDoesNotExist()
        // assertExists, not assertIsDisplayed: the window is still landscape at this point, so
        // where the title lands on screen is beside the point — that it is composed at all is what
        // rules out "the tree vanished" as the reason the exit button is gone.
        composeTestRule.onNodeWithText(title, substring = true).assertExists()
    }

    /** Advances time past the grace period the decision waits out, with room to spare. */
    private fun waitOutTheGrace() {
        composeTestRule.mainClock.advanceTimeBy(GRACE_PLUS_MS)
        composeTestRule.waitForIdle()
    }

    /** THE BUG, as report 0.1.374 recorded it, beat for beat. */
    @Test
    fun anExpiredStreamOnTheNextVideoDoesNotDropFullscreen() {
        enterFullscreen()

        state = resolvingNextItem() // 18:52:32 — no video track yet, buffering
        waitOutTheGrace()
        assertStillFullscreen("dropped while the next item was still loading")

        state = streamFailedAndReResolving() // 18:52:35.6 — idle: no video, NOT buffering
        waitOutTheGrace()
        assertStillFullscreen("dropped during the re-resolve, which is the reported bug")

        state = playingVideo("Cyl3X88KEgg") // 18:52:39.2 — the picture comes back
        composeTestRule.waitForIdle()
        assertStillFullscreen("dropped by the time the next video actually played")
    }

    /** The ordinary advance, with no stumble at all — the case that already worked. */
    @Test
    fun a_clean_advance_keeps_fullscreen() {
        enterFullscreen()

        state = resolvingNextItem()
        waitOutTheGrace()
        state = playingVideo("Cyl3X88KEgg")
        composeTestRule.waitForIdle()

        assertStillFullscreen("a clean auto-advance must not cost fullscreen either")
    }

    /** A long resolve — 11 seconds is real on a phone — must not time it out either. */
    @Test
    fun a_very_slow_resolve_keeps_fullscreen() {
        enterFullscreen()

        state = resolvingNextItem()
        repeat(SLOW_RESOLVE_TICKS) { waitOutTheGrace() }

        assertStillFullscreen("a slow resolve is still a video arriving")
    }

    /**
     * The other half of the contract: advancing onto a PODCAST really must leave fullscreen, or you
     * are left staring at a black landscape frame. A fix that just never exited would pass the rest.
     */
    @Test
    fun advancing_onto_a_podcast_does_leave_fullscreen() {
        enterFullscreen()

        state = playingPodcast()
        waitOutTheGrace()

        assertNoLongerFullscreen(EPISODE_TITLE)
    }

    /** And a video switched to Listen mode has no picture either, so it leaves too. */
    @Test
    fun switching_to_listen_leaves_fullscreen() {
        enterFullscreen()

        state = playingVideo("87DyyMV0kCY").copy(hasVideo = false, isPlaying = true)
        waitOutTheGrace()

        assertNoLongerFullscreen(VIDEO_TITLE)
    }

    private companion object {
        const val ENTER = "Fullscreen"
        const val EXIT = "Exit fullscreen"

        /** The decision waits 1000ms; this clears it comfortably without being slow. */
        const val GRACE_PLUS_MS = 2_000L

        /** 5 x 2s covers the 11-second resolve measured on Dewi's phone. */
        const val SLOW_RESOLVE_TICKS = 5

        const val VIDEO_TITLE = "A video"
        const val EPISODE_TITLE = "An episode"

        fun playingVideo(id: String) = PlaybackState(
            itemId = MediaItemId(id),
            title = VIDEO_TITLE,
            artist = "A channel",
            artworkUrl = null,
            kind = MediaKind.VIDEO,
            isPlaying = true,
            positionMs = 1_000,
            durationMs = 2_247_445,
            speed = 1f,
            hasVideo = true,
        )

        /** The gap: the next item is resolving, so there is no track list yet. */
        fun resolvingNextItem() = playingVideo("Cyl3X88KEgg").copy(
            hasVideo = false,
            isPlaying = false,
            isBuffering = true,
            positionMs = 0,
        )

        /** Idle after `Expired`: still no video, and no longer buffering. The bug's fingerprint. */
        fun streamFailedAndReResolving() = resolvingNextItem().copy(isBuffering = false)

        fun playingPodcast() = playingVideo("an-episode").copy(
            title = EPISODE_TITLE,
            kind = MediaKind.PODCAST,
            hasVideo = false,
            isPlaying = true,
        )
    }
}
