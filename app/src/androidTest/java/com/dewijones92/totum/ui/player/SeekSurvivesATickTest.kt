package com.dewijones92.totum.ui.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.theme.TotumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SeekSurvivesATickTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val playing = PlaybackState(
        itemId = MediaItemId("seek"),
        title = "Seek me",
        artist = null,
        artworkUrl = null,
        isPlaying = true,
        positionMs = 10_000,
        durationMs = 100_000,
        speed = 1f,
    )

    private fun slider() =
        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))

    @Test
    fun aDragReleasedJustAfterAPositionTickStillSeeks() {
        val state = mutableStateOf(playing)
        val seeks = mutableListOf<Long>()
        composeTestRule.setContent {
            TotumTheme {
                SeekBar(state = state.value, onSeekTo = { seeks += it })
            }
        }

        slider().performTouchInput {
            down(centerLeft.copy(x = width * 0.1f))
            moveTo(centerLeft.copy(x = width * 0.8f))
        }
        composeTestRule.runOnIdle { state.value = playing.copy(positionMs = 10_500) }
        composeTestRule.waitForIdle()
        slider().performTouchInput { up() }
        composeTestRule.waitForIdle()

        assertEquals("the seek was lost: $seeks", 1, seeks.size)
        assertTrue("seeked to ${seeks.single()}, not near the drag", seeks.single() > 60_000)
    }

    @Test
    fun aDragCutOffByAReloadOfTheSameItemDoesNotFreezeTheBar() {
        val state = mutableStateOf(playing)
        composeTestRule.setContent { TotumTheme { SeekBar(state = state.value, onSeekTo = {}) } }

        slider().performTouchInput {
            down(centerLeft.copy(x = width * 0.1f))
            moveTo(centerLeft.copy(x = width * 0.8f))
        }
        composeTestRule.runOnIdle { state.value = playing.copy(durationMs = null) }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { state.value = playing.copy(positionMs = 11_000) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(formatTime(11_000)).assertExists()
    }
}
