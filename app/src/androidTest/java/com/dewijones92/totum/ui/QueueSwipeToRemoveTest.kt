package com.dewijones92.totum.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.up
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.queue.QUEUE_ROW_SWIPE_TAG
import com.dewijones92.totum.ui.queue.QueueScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Removing from the queue is a swipe, and a swipe can be taken back. */
class QueueSwipeToRemoveTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun playable(id: String) = PlayableItem(
        item = MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("s"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://x.test/$id.mp3"),
        ),
        handle = PlayHandle.Podcast(),
    )

    private val container = FakeAppContainer()

    private fun show() {
        container.playbackQueue.playAll(listOf(playable("first"), playable("second"), playable("third")))
        composeTestRule.setContent { TotumTheme { QueueScreen(container) } }
    }

    private fun ids() = container.playbackQueue.state.value.entries.map { it.item.item.id.value }

    @Test
    fun swipingARowTowardsTheStartRemovesIt() {
        show()
        composeTestRule.onAllNodesWithTag(QUEUE_ROW_SWIPE_TAG)[1].performTouchInput { swipeLeft() }
        composeTestRule.waitUntil(5_000) { ids() == listOf("first", "third") }
        assertEquals(listOf("first", "third"), ids())
    }

    /**
     * A finger that wobbles across the dismiss threshold confirms the gesture more than once. On e713eb8
     * each confirmation removed "the row at this index", so one swipe took several items (Dewi,
     * 2026-09-07). Removal is by identity now, and this pins it: one gesture, one row, whatever the finger did.
     */
    @Test
    fun aWobblySwipeRemovesExactlyOneRow() {
        show()
        composeTestRule.onAllNodesWithTag(QUEUE_ROW_SWIPE_TAG)[1].performTouchInput {
            down(centerRight)
            moveBy(Offset(-width * 0.7f, 0f))
            moveBy(Offset(width * 0.5f, 0f))
            moveBy(Offset(-width * 0.6f, 0f))
            moveBy(Offset(width * 0.4f, 0f))
            moveBy(Offset(-width * 0.7f, 0f))
            up()
        }
        composeTestRule.waitUntil(5_000) { ids().size == 2 }
        composeTestRule.mainClock.advanceTimeBy(1_000)
        assertEquals(listOf("first", "third"), ids())
    }

    @Test
    fun undoPutsTheRowBackWhereItWas() {
        show()
        composeTestRule.onAllNodesWithTag(QUEUE_ROW_SWIPE_TAG)[1].performTouchInput { swipeLeft() }
        composeTestRule.waitUntil(5_000) { ids() == listOf("first", "third") }
        composeTestRule.onNodeWithText("Undo").assertIsDisplayed().performClick()
        composeTestRule.waitUntil(5_000) { ids() == listOf("first", "second", "third") }
        assertEquals(listOf("first", "second", "third"), ids())
    }
}
