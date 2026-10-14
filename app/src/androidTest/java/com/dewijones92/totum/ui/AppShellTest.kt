package com.dewijones92.totum.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.theme.TotumTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Each pillar is reachable from the bottom bar and shows its own empty state.
 *
 * Every string here is read from resources rather than typed out. The Library case was red for
 * an unknown length of time because its headline became "Nothing downloaded yet" while the test
 * still looked for "Your library is empty" — a failure that says nothing about the app and
 * everything about a copy of a string kept in two places. Reading the resource means renaming
 * the copy cannot break the test, and the test cannot quietly stop checking the screen it names.
 */
class AppShellTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun text(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Before
    fun setUp() {
        composeTestRule.setContent {
            TotumTheme { AppShell(FakeAppContainer()) }
        }
    }

    @Test
    fun videosPillar_isShownByDefault() {
        composeTestRule.onNodeWithText(text(R.string.videos_empty_headline)).assertIsDisplayed()
    }

    @Test
    fun tappingPodcasts_showsPodcastsPillar() {
        composeTestRule.onNodeWithText(text(R.string.destination_podcasts)).performClick()
        composeTestRule.onNodeWithText(text(R.string.podcasts_empty_headline)).assertIsDisplayed()
    }

    @Test
    fun tappingLibrary_showsLibraryPillar() {
        composeTestRule.onNodeWithText(text(R.string.destination_library)).performClick()
        composeTestRule.onNodeWithText(text(R.string.stats_tile)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.library_empty_headline)).assertExists()
    }
}
