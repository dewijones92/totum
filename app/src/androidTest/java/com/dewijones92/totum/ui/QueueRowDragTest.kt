package com.dewijones92.totum.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
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
import com.dewijones92.totum.ui.queue.QueueScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A grip on the REAL queue screen still drags its row.
 *
 * `ReorderAutoScrollTest` covers the gesture arithmetic against a synthetic list, so it cannot see
 * where `Modifier.reorderable` is attached in the actual screen — and that attachment has broken the
 * queue before: on cbf9916 it sat on the row INSIDE the swipe wrapper, so a dragged row slid within a
 * box that stayed put and the list looked frozen ("I can't drag them around any longer", Dewi). The
 * swipe wrapper is gone and the modifier moved onto `MediaItemRow` itself, which is the same hazard
 * from the other side. This drives the screen the app actually shows.
 */
@RunWith(AndroidJUnit4::class)
class QueueRowDragTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val container = FakeAppContainer()

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

    private fun ids() = container.playbackQueue.state.value.entries.map { it.item.item.id.value }

    /**
     * The UNMERGED tree, and that is not incidental: the row's `combinedClickable` merges its
     * descendants' semantics, so the merged tree hands back the whole row and a drag from its centre
     * never touches the 24dp grip. The first version of this test read as "dragging is broken".
     */
    private fun grip(label: String) =
        composeTestRule.onAllNodesWithContentDescription(label, useUnmergedTree = true)[0]

    @Test
    fun draggingTheGripMovesTheRowItBelongsTo() {
        composeTestRule.mainClock.autoAdvance = false
        container.playbackQueue.playAll(ROWS.map(::playable))
        composeTestRule.setContent { TotumTheme { QueueScreen(container) } }
        val gripLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.queue_reorder)

        // One gesture: pointer state does not survive being split across blocks.
        grip(gripLabel).performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            moveTo(center + Offset(0f, ROW_DP * ROWS_OF_TRAVEL * density))
        }
        composeTestRule.mainClock.advanceTimeBy(TICK_MS)
        grip(gripLabel).performTouchInput { up() }

        // Two rows of travel must be about two places. The range is honest slop — the long-press
        // detector absorbs the first movement — but it is narrow enough to catch the other half of the
        // hazard: a reorder modifier attached to something smaller than the row measures the swap rate
        // from THAT height, and this gesture would then carry the row to the bottom.
        assertTrue(
            "two rows of travel should be about two places, not none and not the bottom: ${ids()}",
            ids().indexOf("first") in EXPECTED_PLACES,
        )
    }

    private companion object {
        /** Comfortably past Compose's long-press threshold. */
        const val LONG_PRESS_MS = 1_000L
        const val TICK_MS = 16L

        /** About one real queue row, measured on the emulator. */
        const val ROW_DP = 85f
        const val ROWS_OF_TRAVEL = 2f

        /** Enough rows below the dragged one that landing at the bottom is distinguishable. */
        val ROWS = listOf("first", "second", "third", "fourth", "fifth", "sixth")
        val EXPECTED_PLACES = 1..3
    }
}
