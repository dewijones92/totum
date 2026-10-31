package com.dewijones92.totum.ui.cast

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.playback.CastStage
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.theme.TotumTheme
import org.junit.Rule
import org.junit.Test

class CastStageLineTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun casting(stage: CastStage?, percent: Int? = null) = PlaybackState(
        itemId = MediaItemId("film"),
        title = "A film",
        artist = null,
        artworkUrl = null,
        isPlaying = false,
        positionMs = 0,
        durationMs = null,
        speed = 1f,
        onCastDevice = true,
        castDeviceName = "Living Room",
        castStage = stage,
        castStagePercent = percent,
    )

    @Test
    fun theStripSaysWhatCastingIsDoing() {
        composeTestRule.setContent { TotumTheme { CastStrip(casting(CastStage.REPACKAGING, 40)) } }

        composeTestRule.onNodeWithText("📦 Repackaging it for Living Room: 40%").assertExists()
    }

    @Test
    fun theTvStartingIsSaidToo() {
        composeTestRule.setContent { TotumTheme { CastStrip(casting(CastStage.TV_STARTING)) } }

        composeTestRule.onNodeWithText("⏳ Living Room is starting it…").assertExists()
    }

    @Test
    fun nothingIsSaidOnceTheTvIsFetching() {
        composeTestRule.setContent { TotumTheme { CastStrip(casting(null)) } }

        composeTestRule.onNodeWithTag(CAST_STAGE_TAG).assertDoesNotExist()
    }
}
