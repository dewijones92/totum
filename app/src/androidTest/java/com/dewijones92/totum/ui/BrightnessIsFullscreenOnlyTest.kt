package com.dewijones92.totum.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.player.ChosenBrightness
import com.dewijones92.totum.ui.player.VideoSettings
import com.dewijones92.totum.ui.player.VideoStageWithControls
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The brightness you set by swipe applies **in fullscreen and nowhere else**.
 *
 * Dewi, 2026-08-09: *"The brightness needs to be only applied if the video has been played in full
 * screen. Otherwise it needs to use my phone brightness … similar to PipePipe."*
 *
 * The swipe was always fullscreen-only; applying its result was not. The windowed player sits on
 * screen for most of the app's life — under the queue, the comments, the description — so a
 * brightness set while watching leaked onto the whole app. Report 0.1.374 has it to the
 * millisecond: `fullscreen active=false` at 18:51:39.009, then `stage on screen (1), window
 * brightness 1.0` at 18:51:39.010.
 *
 * **Why this has to be an instrumented test.** The defect is not in any decision the code makes —
 * every step is right on its own. It is in the ORDER Compose runs them. Toggling fullscreen swaps
 * one whole subtree for another (`FullPlayer.kt`), so the outgoing stage is disposed and a fresh
 * one created; the new one applies during *composition* and the old one releases in its
 * `onDispose`, in the *effects* phase afterwards. Later wins. Nothing about that is visible in any
 * single function — only in a real composition, asking a real window what it is showing.
 */
@RunWith(AndroidJUnit4::class)
class BrightnessIsFullscreenOnlyTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var player: ExoPlayer

    private val state = PlaybackState(
        itemId = MediaItemId("abc123"),
        title = "Is this Gary Stevensons last EVER interview?",
        artist = "Novara Media",
        artworkUrl = null,
        kind = MediaKind.VIDEO,
        // Deliberately paused: playing would start the controls auto-hide timer, which has nothing
        // to do with this and only adds a way for the test to become flaky.
        isPlaying = false,
        positionMs = 60_000,
        durationMs = 2_520_000,
        speed = 1f,
        hasVideo = true,
    )

    @Before
    fun setUp() {
        composeTestRule.runOnUiThread {
            player = ExoPlayer.Builder(composeTestRule.activity).build()
        }
    }

    @After
    fun tearDown() {
        composeTestRule.runOnUiThread { player.release() }
        ChosenBrightness.forget()
    }

    /** What the window is actually showing, which is the only thing the user can see. */
    private fun windowBrightness(): Float =
        composeTestRule.activity.window.attributes.screenBrightness

    /**
     * Mirrors `FullPlayer`: fullscreen and windowed are two different subtrees, so toggling really
     * does dispose one stage and create another. The extra [Box] is not decoration — windowed, the
     * stage sits nested inside the draggable content, and it is that difference in position that
     * makes Compose treat them as different composables rather than reusing one.
     */
    @Composable
    private fun Harness(fullscreen: Boolean, player: Player) {
        TotumTheme {
            if (fullscreen) {
                Stage(player, fullscreen = true)
            } else {
                Box { Stage(player, fullscreen = false) }
            }
        }
    }

    @Composable
    private fun Stage(player: Player, fullscreen: Boolean) {
        VideoStageWithControls(
            state = state,
            player = player,
            settings = VideoSettings.None,
            fullscreen = fullscreen,
            onToggleFullscreen = {},
            onDismiss = {},
            onTogglePlayPause = {},
            onSeekTo = {},
            onSeekBackward = {},
            onSeekForward = {},
        )
    }

    /** THE BUG. A windowed player must leave the screen at the phone's own brightness. */
    @Test
    fun theWindowedPlayerNeverOverridesTheScreen() {
        ChosenBrightness.choose(CHOSEN)
        composeTestRule.setContent { Harness(fullscreen = false, player = player) }
        composeTestRule.waitForIdle()

        assertEquals(
            "a brightness set while watching leaked onto the windowed player, and so onto the app",
            FOLLOW_SYSTEM,
            windowBrightness(),
            TOLERANCE,
        )
    }

    /** And in fullscreen it must actually be applied, or the swipe does nothing. */
    @Test
    fun fullscreenAppliesTheChosenBrightness() {
        ChosenBrightness.choose(CHOSEN)
        composeTestRule.setContent { Harness(fullscreen = true, player = player) }
        composeTestRule.waitForIdle()

        assertEquals(CHOSEN, windowBrightness(), TOLERANCE)
    }

    /** Leaving fullscreen hands the screen straight back to the phone. */
    @Test
    fun leavingFullscreenGivesTheScreenBack() {
        ChosenBrightness.choose(CHOSEN)
        var fullscreen by mutableStateOf(true)
        composeTestRule.setContent { Harness(fullscreen, player) }
        composeTestRule.waitForIdle()
        assertEquals(CHOSEN, windowBrightness(), TOLERANCE)

        fullscreen = false
        composeTestRule.waitForIdle()

        assertEquals(FOLLOW_SYSTEM, windowBrightness(), TOLERANCE)
    }

    /**
     * The choice is REMEMBERED though — only the override is dropped. Going back into fullscreen
     * returns to the brightness you were watching at, rather than making you set it again on every
     * video in the queue.
     */
    @Test
    fun goingBackIntoFullscreenRestoresIt() {
        ChosenBrightness.choose(CHOSEN)
        var fullscreen by mutableStateOf(true)
        composeTestRule.setContent { Harness(fullscreen, player) }
        composeTestRule.waitForIdle()

        fullscreen = false
        composeTestRule.waitForIdle()
        fullscreen = true
        composeTestRule.waitForIdle()

        assertEquals(CHOSEN, windowBrightness(), TOLERANCE)
    }

    /**
     * Several times over, since a sitting means many transitions — and because this is where the
     * ORDER bug lives. It has to be right whichever way round Compose runs the two lifecycles, so
     * both directions are asserted on every pass rather than only the end state.
     */
    @Test
    fun repeatedTogglingIsStableInBothDirections() {
        ChosenBrightness.choose(CHOSEN)
        var fullscreen by mutableStateOf(false)
        composeTestRule.setContent { Harness(fullscreen, player) }

        repeat(TOGGLES) { pass ->
            fullscreen = true
            composeTestRule.waitForIdle()
            assertEquals("not applied on pass ${pass + 1}", CHOSEN, windowBrightness(), TOLERANCE)

            fullscreen = false
            composeTestRule.waitForIdle()
            assertEquals("not released on pass ${pass + 1}", FOLLOW_SYSTEM, windowBrightness(), TOLERANCE)
        }
    }

    /**
     * The video going away entirely while fullscreen — the player closing, the queue emptying —
     * must release it too. A fix that simply stopped releasing would pass the tests above.
     */
    @Test
    fun theOverrideIsReleasedWhenTheVideoGoesAwayCompletely() {
        ChosenBrightness.choose(CHOSEN)
        var showing by mutableStateOf(true)
        composeTestRule.setContent { if (showing) Harness(fullscreen = true, player = player) else TotumTheme {} }
        composeTestRule.waitForIdle()
        assertEquals(CHOSEN, windowBrightness(), TOLERANCE)

        showing = false
        composeTestRule.waitForIdle()

        assertEquals(FOLLOW_SYSTEM, windowBrightness(), TOLERANCE)
    }

    /** With nothing chosen, the app must not touch the window even in fullscreen. */
    @Test
    fun withNoChoiceTheWindowIsLeftAlone() {
        var fullscreen by mutableStateOf(false)
        composeTestRule.setContent { Harness(fullscreen, player) }
        composeTestRule.waitForIdle()

        fullscreen = true
        composeTestRule.waitForIdle()

        assertEquals(FOLLOW_SYSTEM, windowBrightness(), TOLERANCE)
    }

    private companion object {
        /** Distinct from 0, 1 and 0.5, so a passing test cannot be a coincidence of defaults. */
        const val CHOSEN = 0.87f
        const val FOLLOW_SYSTEM = -1f
        const val TOLERANCE = 0.001f
        const val TOGGLES = 6
    }
}
