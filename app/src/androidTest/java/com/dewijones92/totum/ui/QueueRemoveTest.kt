package com.dewijones92.totum.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.ProvidePlayStates
import com.dewijones92.totum.ui.queue.QueueScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Removing a queue row: one row, from the row's own menu, and takeable back.
 *
 * Removal used to be a swipe, and a swipe removed several rows at once — `SwipeToDismissBox` confirms
 * the gesture on every crossing of its threshold, so a finger that wobbled fired it repeatedly (Dewi,
 * 2026-09-07). Dewi's call on 2026-09-15 was to drop the gesture rather than keep tuning it: removal is
 * a menu action beside "Move to top". So one test proves the action works, one proves Undo does, and
 * one pins the gesture as GONE — a swipe that still removed would be the old bug walking back in.
 */
@RunWith(AndroidJUnit4::class)
class QueueRemoveTest {

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

    private fun label(res: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(res)

    /** Opens the row's ⋮ menu and taps "Remove from queue". */
    private fun removeRow(index: Int) {
        composeTestRule.onAllNodesWithContentDescription(label(R.string.queue_menu))[index].performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(label(R.string.queue_remove)).performClick()
    }

    @Test
    fun theQueueMenuDoesNotOfferToAddWhatIsAlreadyQueued() {
        container.playbackQueue.playAll(listOf(playable("first"), playable("second"), playable("third")))
        composeTestRule.setContent {
            TotumTheme { ProvidePlayStates(container, onOpenSource = {}) { QueueScreen(container) } }
        }

        composeTestRule.onAllNodesWithContentDescription(label(R.string.queue_menu))[1].performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(label(R.string.queue_play_next)).assertExists()
        composeTestRule.onNodeWithText(label(R.string.queue_move_to_bottom)).assertExists()
        composeTestRule.onAllNodesWithText(label(R.string.queue_add)).assertCountEquals(0)
    }

    @Test
    fun removingFromTheMenuTakesExactlyThatRow() {
        show()

        removeRow(1)

        composeTestRule.waitUntil(TIMEOUT_MS) { ids() == listOf("first", "third") }
        assertEquals(listOf("first", "third"), ids())
    }

    @Test
    fun undoPutsTheRowBackWhereItWas() {
        show()

        removeRow(1)
        composeTestRule.waitUntil(TIMEOUT_MS) { ids() == listOf("first", "third") }
        composeTestRule.onNodeWithText(label(R.string.queue_undo)).assertIsDisplayed().performClick()

        composeTestRule.waitUntil(TIMEOUT_MS) { ids() == listOf("first", "second", "third") }
        assertEquals(listOf("first", "second", "third"), ids())
    }

    /** The gesture is gone, so a swipe must do nothing at all — not even to one row. */
    @Test
    fun swipingARowRemovesNothing() {
        show()

        composeTestRule.onNodeWithText("second").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        assertEquals(listOf("first", "second", "third"), ids())
        composeTestRule.onNodeWithText("second").assertIsDisplayed()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
