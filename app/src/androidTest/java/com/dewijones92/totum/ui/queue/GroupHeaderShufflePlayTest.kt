package com.dewijones92.totum.ui.queue

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.dewijones92.totum.theme.TotumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GroupHeaderShufflePlayTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show(count: Int, onShuffleAndPlay: () -> Unit) {
        composeTestRule.setContent {
            TotumTheme {
                GroupHeader(
                    GroupHeaderModel(
                        groupId = "album:a",
                        isFirstRun = true,
                        title = "An album",
                        count = count,
                        collapsed = false,
                        containsNowPlaying = false,
                        draggable = false,
                    ),
                    GroupHeaderActions(
                        onToggle = {},
                        onRemove = {},
                        onMove = {},
                        onShuffle = {},
                        onShuffleAndPlay = onShuffleAndPlay,
                    ),
                    drag = null,
                )
            }
        }
    }

    @Test
    fun theShuffleButtonShufflesAndPlaysTheGroup() {
        var taps = 0
        show(count = 3) { taps++ }

        composeTestRule.onNodeWithTag(queueGroupShufflePlayTag("album:a")).performClick()

        assertEquals(1, taps)
    }

    @Test
    fun aGroupOfOneHasNoShuffleButton() {
        show(count = 1) {}

        composeTestRule.onNodeWithTag(queueGroupShufflePlayTag("album:a")).assertDoesNotExist()
    }
}
