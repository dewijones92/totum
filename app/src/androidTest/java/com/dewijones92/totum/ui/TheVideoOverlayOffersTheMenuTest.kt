package com.dewijones92.totum.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.player.VideoSettings
import com.dewijones92.totum.ui.player.VideoStageWithControls
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TheVideoOverlayOffersTheMenuTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var player: ExoPlayer
    private var menuOpened = 0
    private var dismissed = 0

    private val state = PlaybackState(
        itemId = MediaItemId("abc123"),
        title = "A video",
        artist = "A channel",
        artworkUrl = null,
        kind = MediaKind.VIDEO,
        isPlaying = false,
        positionMs = 0,
        durationMs = 60_000,
        speed = 1f,
        hasVideo = true,
    )

    @Before
    fun setUp() {
        composeTestRule.runOnUiThread { player = ExoPlayer.Builder(composeTestRule.activity).build() }
    }

    @After
    fun tearDown() {
        composeTestRule.runOnUiThread { player.release() }
    }

    private fun show(fullscreen: Boolean) {
        composeTestRule.setContent {
            TotumTheme {
                VideoStageWithControls(
                    state = state,
                    player = player,
                    settings = VideoSettings.None,
                    fullscreen = fullscreen,
                    onToggleFullscreen = {},
                    onDismiss = { dismissed++ },
                    onTogglePlayPause = {},
                    onSeekTo = {},
                    onSeekBackward = {},
                    onSeekForward = {},
                    onMore = { menuOpened++ },
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private val menu get() = composeTestRule.activity.getString(R.string.queue_menu)

    @Test
    fun theWindowedVideoOverlayOpensTheItemMenu() {
        show(fullscreen = false)

        composeTestRule.onNodeWithContentDescription(menu).performClick()

        assertEquals(1, menuOpened)
    }

    @Test
    fun theWindowedVideoOverlayCloses() {
        show(fullscreen = false)

        val close = composeTestRule.onNodeWithContentDescription(composeTestRule.activity.getString(R.string.close))
        val node = close.fetchSemanticsNode()
        println("dewidebug overlay close node ${node.boundsInRoot} actions=${node.config.map { it.key.name }}")
        close.performClick()
        composeTestRule.waitForIdle()
        println(
            "dewidebug overlay after click: close exists=${composeTestRule.onAllNodesWithContentDescription(
                composeTestRule.activity.getString(R.string.close)
            ).fetchSemanticsNodes().size} dismissed=$dismissed"
        )

        assertEquals(1, dismissed)
    }

    @Test
    fun fullscreenLeavesTheMenuOut() {
        show(fullscreen = true)

        composeTestRule.onNodeWithContentDescription(menu).assertDoesNotExist()
    }
}
