package com.dewijones92.totum.ui.common

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.navigation.TopLevelDestination
import com.dewijones92.totum.queue.Starting
import org.junit.Rule
import org.junit.Test

class DockStartingTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun `the first tap of a session shows what is starting even before anything has played`() {
        composeTestRule.setContent {
            Dock(
                state = null,
                starting = Starting(MediaItemId("a"), "First song of the day", 0),
                selected = TopLevelDestination.entries.first(),
                onSelect = {},
                onTogglePlayPause = {},
                onExpand = {},
                onSkipNext = {},
            )
        }
        composeTestRule.onNodeWithTag(STARTING_TAG).assertExists()
        composeTestRule.onNodeWithText("First song of the day").assertExists()
    }
}
