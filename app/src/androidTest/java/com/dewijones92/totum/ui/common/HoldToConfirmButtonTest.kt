package com.dewijones92.totum.ui.common

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HoldToConfirmButtonTest {

    @get:Rule val composeTestRule = createComposeRule()

    private var confirmed = 0

    private fun show() = composeTestRule.setContent {
        HoldToConfirmButton(label = "Download", hint = "Hold to download", onConfirmed = { confirmed++ })
    }

    @Test
    fun `a tap does not confirm and says to hold`() {
        show()
        composeTestRule.onNodeWithTag(HOLD_TO_CONFIRM_TAG).performClick()
        composeTestRule.waitForIdle()
        assertEquals(0, confirmed)
        composeTestRule.onNodeWithText("Hold to download").assertExists()
    }

    @Test
    fun `holding for the full time confirms once`() {
        show()
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onNodeWithTag(HOLD_TO_CONFIRM_TAG).performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(1_700)
        composeTestRule.onNodeWithTag(HOLD_TO_CONFIRM_TAG).performTouchInput { up() }
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
        assertEquals(1, confirmed)
    }

    @Test
    fun `letting go early does not confirm`() {
        show()
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onNodeWithTag(HOLD_TO_CONFIRM_TAG).performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(700)
        composeTestRule.onNodeWithTag(HOLD_TO_CONFIRM_TAG).performTouchInput { up() }
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
        assertEquals(0, confirmed)
    }
}
