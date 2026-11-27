package com.dewijones92.totum.ui.report

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CornerHoldTest {
    @get:Rule
    val compose = createComposeRule()

    private var held = 0
    private var cornerClicks = 0
    private var middleClicks = 0

    private fun screen(enabled: Boolean = true) {
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("screen").cornerHold(enabled, topInsetPx = 0) { held++ }) {
                Button(
                    onClick = { cornerClicks++ },
                    modifier = Modifier.align(Alignment.TopEnd).size(48.dp).testTag("corner"),
                ) {}
                Button(
                    onClick = { middleClicks++ },
                    modifier = Modifier.align(Alignment.Center).size(96.dp).testTag("middle"),
                ) {}
            }
        }
    }

    @Test
    fun aTapOnAButtonInTheCornerStillClicksIt() {
        screen()

        compose.onNodeWithTag("corner").performClick()

        assertEquals(1, cornerClicks)
        assertEquals(0, held)
    }

    @Test
    fun holdingTheCornerOpensTheReportAndDoesNotClickTheButtonUnderIt() {
        screen()

        compose.onNodeWithTag("corner").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(HOLD_PLUS_MS)
        compose.waitForIdle()
        assertEquals(1, held)
        compose.onNodeWithTag("corner").performTouchInput { up() }
        compose.waitForIdle()

        assertEquals(0, cornerClicks)
        assertEquals(1, held)
    }

    @Test
    fun aHoldJustShortOfTheTimeIsAnOrdinaryPress() {
        screen()

        compose.onNodeWithTag("corner").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(SHORT_OF_HOLD_MS)
        compose.onNodeWithTag("corner").performTouchInput { up() }
        compose.waitForIdle()

        assertEquals(0, held)
        assertEquals(1, cornerClicks)
    }

    @Test
    fun holdingAnywhereElseDoesNothing() {
        screen()

        compose.onNodeWithTag("middle").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(HOLD_PLUS_MS)
        compose.onNodeWithTag("middle").performTouchInput { up() }
        compose.waitForIdle()

        assertEquals(0, held)
        assertEquals(1, middleClicks)
    }

    @Test
    fun draggingOutOfTheCornerBeforeTheTimeCancelsIt() {
        screen()

        compose.onNodeWithTag("screen").performTouchInput {
            down(Offset(width - 20f, 20f))
            moveTo(Offset(width - 20f, 400f))
        }
        compose.mainClock.advanceTimeBy(HOLD_PLUS_MS)
        compose.onNodeWithTag("screen").performTouchInput { up() }
        compose.waitForIdle()

        assertEquals(0, held)
    }

    @Test
    fun withTheGestureOffAHoldDoesNothingAndTheButtonStillWorks() {
        screen(enabled = false)

        compose.onNodeWithTag("corner").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(HOLD_PLUS_MS)
        compose.onNodeWithTag("corner").performTouchInput { up() }
        compose.waitForIdle()

        assertEquals(0, held)
        assertEquals(1, cornerClicks)
    }

    @Test
    fun itCanBeHeldAgainAfterTheFirstTime() {
        screen()

        repeat(2) {
            compose.onNodeWithTag("corner").performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(HOLD_PLUS_MS)
            compose.waitForIdle()
            compose.onNodeWithTag("corner").performTouchInput { up() }
            compose.waitForIdle()
        }

        assertEquals(2, held)
    }

    private companion object {
        const val HOLD_PLUS_MS = 1_700L
        const val SHORT_OF_HOLD_MS = 1_200L
    }
}
