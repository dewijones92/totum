package com.dewijones92.totum.ui.cast

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.playback.CastVolume
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.theme.TotumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class CastVolumeRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val control = FakePlaybackController()

    private fun casting(volume: CastVolume?) = PlaybackState(
        itemId = MediaItemId("film"),
        title = "A film",
        artist = null,
        artworkUrl = null,
        isPlaying = true,
        positionMs = 0,
        durationMs = null,
        speed = 1f,
        onCastDevice = true,
        castDeviceName = "Living Room",
        castVolume = volume,
    )

    private fun show(state: PlaybackState, withControl: Boolean = true) {
        compose.setContent {
            TotumTheme {
                CompositionLocalProvider(LocalScreenControl provides control.takeIf { withControl }) {
                    CastStrip(state)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theStripSaysTheTvVolumeInPercent() {
        show(casting(CastVolume(level = 7, max = 20, muted = false)))

        compose.onNodeWithTag(CAST_VOLUME_TAG).assertIsDisplayed()
        compose.onNodeWithText("🔊 Volume 35% on Living Room").assertIsDisplayed()
    }

    @Test
    fun aMutedTvSaysSoInsteadOfAPercent() {
        show(casting(CastVolume(level = 7, max = 20, muted = true)))

        compose.onNodeWithText("🔇 Living Room is muted").assertIsDisplayed()
    }

    @Test
    fun noVolumeFromTheReceiverMeansNoSlider() {
        show(casting(volume = null))

        compose.onNodeWithTag(CAST_VOLUME_TAG).assertDoesNotExist()
    }

    @Test
    fun withNothingToControlTheTvThereIsNoSlider() {
        show(casting(CastVolume(7, 20, false)), withControl = false)

        compose.onNodeWithTag(CAST_VOLUME_TAG).assertDoesNotExist()
    }

    @Test
    fun movingTheSliderAsksTheTvForThatLevelAndLettingGoShowsWhatTheTvReports() {
        show(casting(CastVolume(level = 7, max = 20, muted = false)))
        assertNull(control.castVolumeAsked)

        val slider = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
        compose.onNode(slider).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.waitForIdle()

        assertNotNull(control.castVolumeAsked)
        assertEquals(0.5f, control.castVolumeAsked!!, 0.06f)
        compose.onNodeWithText("🔊 Volume 35% on Living Room").assertIsDisplayed()
    }
}
