package com.dewijones92.totum.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.Recompositions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlaybackTicksRedrawNoScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val playing = PlaybackState(
        itemId = MediaItemId("tick"),
        title = "Ticking along",
        artist = "Someone",
        artworkUrl = null,
        isPlaying = true,
        positionMs = 1_000,
        durationMs = 600_000,
        speed = 1f,
        hasVideo = true,
    )

    private val screens = listOf("shell", "queue", "player", "stage", "dock")

    @Test
    fun positionTicksRedrawNoScreenWithTheQueueUnderThePlayer() {
        val container = FakeAppContainer()
        val controller = container.playbackController as FakePlaybackController
        controller.emitState(playing)
        composeTestRule.setContent { TotumTheme { AppShell(container) } }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeTestRule.onNodeWithText(context.getString(R.string.destination_queue)).performClick()
        composeTestRule.onAllNodesWithText(playing.title).onFirst().performClick()
        composeTestRule.waitForIdle()
        val before = screens.associateWith(Recompositions::of)

        repeat(TICKS) { tick ->
            composeTestRule.runOnIdle { controller.emitState(playing.copy(positionMs = 1_000L + (tick + 1) * 500)) }
            composeTestRule.waitForIdle()
        }

        assertEquals(before, screens.associateWith(Recompositions::of))
    }

    private companion object {
        const val TICKS = 5
    }
}
