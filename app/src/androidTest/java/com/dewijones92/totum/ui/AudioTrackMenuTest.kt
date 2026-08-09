package com.dewijones92.totum.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dewijones92.totum.common.AudioTrackTag
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.player.QualityControl
import com.dewijones92.totum.ui.player.VideoSettings
import com.dewijones92.totum.ui.player.VideoSettingsControls
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The audio-track menu on the video overlay: it appears where there is a choice, and choosing
 * a row asks for that language.
 *
 * The control exists because report 0.1.373 played an automatic German dub of an English talk
 * and there was no way to say otherwise. The default is fixed separately (see
 * `AudioTrackSelectionTest`); this is the override.
 */
@RunWith(AndroidJUnit4::class)
class AudioTrackMenuTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val chosen = mutableListOf<String>()

    private fun show(tracks: List<AudioTrackTag>, playing: String? = null) {
        composeTestRule.setContent {
            TotumTheme {
                VideoSettingsControls(
                    VideoSettings(
                        quality = QualityControl.None.copy(
                            audioTracks = tracks,
                            audioLanguage = playing,
                            onSelectAudioTrack = { chosen += it },
                        ),
                        speed = 1f,
                        onSetSpeed = {},
                    ),
                )
            }
        }
    }

    @Test
    fun `choosing a track asks for that language`() {
        show(listOf(ENGLISH, GERMAN))

        composeTestRule.onNodeWithContentDescription("Audio track").performClick()
        composeTestRule.onNode(hasText("German", substring = true)).performClick()

        assertEquals(listOf("de-DE"), chosen)
    }

    @Test
    fun `both tracks are listed and say which is the dub`() {
        show(listOf(ENGLISH, GERMAN))

        composeTestRule.onNodeWithContentDescription("Audio track").performClick()

        composeTestRule.onNode(hasText("original", substring = true)).assertIsDisplayed()
        composeTestRule.onNode(hasText("dubbed", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `a video with one track offers no menu`() {
        // Same rule the quality menu follows: a menu with nothing to decide is clutter.
        show(listOf(ENGLISH))

        composeTestRule.onNodeWithContentDescription("Audio track").assertDoesNotExist()
    }

    @Test
    fun `a video with no tracks at all offers no menu`() {
        show(emptyList())

        composeTestRule.onNodeWithContentDescription("Audio track").assertDoesNotExist()
    }

    @Test
    fun `the speed control is still there beside it`() {
        // The audio menu was inserted into a row of existing controls; it must not displace one.
        show(listOf(ENGLISH, GERMAN))

        composeTestRule.onNodeWithText("1x").assertIsDisplayed()
    }

    private companion object {
        val ENGLISH = AudioTrackTag(languageCode = "en-US", original = true)
        val GERMAN = AudioTrackTag(languageCode = "de-DE", dubbed = true)
    }
}
