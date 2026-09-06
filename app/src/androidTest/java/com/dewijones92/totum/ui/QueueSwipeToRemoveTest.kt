package com.dewijones92.totum.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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
