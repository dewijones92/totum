package com.dewijones92.totum.ui.player

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class StageDragLetsUpwardSwipesScrollTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val dismissed = AtomicInteger()
    private val scroll = ScrollState(0)

    private fun show() {
        composeTestRule.setContent {
            val drag = rememberStageDragDismiss { dismissed.incrementAndGet() }
            Column(Modifier.testTag("page").height(600.dp).then(drag.contentOffset).verticalScroll(scroll)) {
                Box(drag.handle.testTag("stage").fillMaxWidth().height(400.dp).background(Color.Black))
                Box(Modifier.fillMaxWidth().height(1200.dp).background(Color.Gray))
            }
        }
    }

    @Test
    fun anUpwardSwipeOnTheStageScrollsTheDetailsAndDoesNotDismiss() {
        show()

        composeTestRule.onNodeWithTag("stage").performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        assertTrue("the page scrolled, was ${scroll.value}", scroll.value > 0)
        assertEquals(0, dismissed.get())
    }

    @Test
    fun aDownwardSwipeOnTheStageStillDismisses() {
        show()

        composeTestRule.onNodeWithTag("stage").performTouchInput { swipeDown() }
        composeTestRule.waitForIdle()

        assertEquals(1, dismissed.get())
        assertEquals(0, scroll.value)
    }
}
