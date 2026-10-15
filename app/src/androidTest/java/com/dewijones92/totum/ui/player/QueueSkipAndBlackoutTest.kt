package com.dewijones92.totum.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.LocalQueueSkip
import com.dewijones92.totum.ui.common.QueueSkip
import com.dewijones92.totum.ui.common.SKIP_NEXT_TAG
import com.dewijones92.totum.ui.common.SKIP_PREVIOUS_TAG
import com.dewijones92.totum.ui.common.SkipNextButton
import com.dewijones92.totum.ui.common.SkipPreviousButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class QueueSkipAndBlackoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun previousAndNextReachTheQueue() {
        val pressed = mutableListOf<String>()
        composeTestRule.setContent {
            TotumTheme {
                CompositionLocalProvider(
                    LocalQueueSkip provides QueueSkip({ pressed += "previous" }, { pressed += "next" })
                ) {
                    Row {
                        SkipPreviousButton()
                        SkipNextButton()
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(SKIP_PREVIOUS_TAG).performClick()
        composeTestRule.onNodeWithTag(SKIP_NEXT_TAG).performClick()

        assertEquals(listOf("previous", "next"), pressed)
    }

    @Test
    fun withNoQueueThereAreNoSkipButtons() {
        composeTestRule.setContent { TotumTheme { SkipNextButton() } }

        composeTestRule.onNodeWithTag(SKIP_NEXT_TAG).assertDoesNotExist()
    }

    @Test
    fun aPlayingVideoLeftAloneGoesBlackAndATouchBringsThePageBack() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            TotumTheme {
                IdleBlackout(active = true) {
                    Box(Modifier.fillMaxSize()) { Text("the page") }
                }
            }
        }
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.onNodeWithTag(IDLE_BLACKOUT_TAG).assertDoesNotExist()

        composeTestRule.mainClock.advanceTimeBy(3_000)
        composeTestRule.onNodeWithTag(IDLE_BLACKOUT_TAG).assertExists()

        composeTestRule.onRoot().performTouchInput {
            down(center)
            up()
        }
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.onNodeWithTag(IDLE_BLACKOUT_TAG).assertDoesNotExist()
    }

    @Test
    fun aPausedVideoNeverGoesBlack() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent { TotumTheme { IdleBlackout(active = false) { Text("the page") } } }

        composeTestRule.mainClock.advanceTimeBy(10_000)

        composeTestRule.onNodeWithTag(IDLE_BLACKOUT_TAG).assertDoesNotExist()
    }

    @Test
    fun aTapJustAfterWakingReachesTheButtonUnderneath() {
        composeTestRule.mainClock.autoAdvance = false
        var clicks = 0
        composeTestRule.setContent {
            TotumTheme {
                IdleBlackout(active = true) {
                    Box(Modifier.fillMaxSize()) {
                        androidx.compose.material3.Button(
                            onClick = { clicks++ },
                            modifier = Modifier.testTag("under")
                        ) {
                            Text("pause")
                        }
                    }
                }
            }
        }
        composeTestRule.mainClock.advanceTimeBy(4_000)
        composeTestRule.onNodeWithTag(IDLE_BLACKOUT_TAG).assertExists()

        composeTestRule.onRoot().performTouchInput {
            down(center)
            up()
        }
        composeTestRule.mainClock.advanceTimeBy(100)
        composeTestRule.onNodeWithTag("under").performClick()
        composeTestRule.mainClock.advanceTimeBy(100)

        assertEquals(1, clicks)
    }
}
