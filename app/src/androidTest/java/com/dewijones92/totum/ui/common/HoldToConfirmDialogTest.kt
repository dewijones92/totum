package com.dewijones92.totum.ui.common

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HoldToConfirmDialogTest {

    @get:Rule val composeTestRule = createComposeRule()

    private var yes = 0
    private var no = 0

    private fun show() = composeTestRule.setContent {
        HoldToConfirmDialog(
            texts = HoldToConfirmTexts(
                null,
                "Download 3 items for offline?",
                "Download",
                "Hold to download",
                "Not now"
            ),
            onYes = { yes++ },
            onNo = { no++ },
        )
    }

    @Test
    fun `not now is one tap`() {
        show()
        composeTestRule.onNodeWithText("Not now").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(0, 1), listOf(yes, no))
    }

    @Test
    fun `a tap on download does nothing but a full hold confirms`() {
        show()
        composeTestRule.onNodeWithTag(HOLD_TO_CONFIRM_TAG, useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        assertEquals("a tap must not download", 0, yes)

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onNodeWithTag(HOLD_TO_CONFIRM_TAG, useUnmergedTree = true).performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(1_700)
        composeTestRule.onNodeWithTag(HOLD_TO_CONFIRM_TAG, useUnmergedTree = true).performTouchInput { up() }
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
        assertEquals(listOf(1, 0), listOf(yes, no))
    }
}
