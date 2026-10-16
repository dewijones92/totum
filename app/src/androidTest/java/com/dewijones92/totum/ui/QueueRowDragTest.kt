package com.dewijones92.totum.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.queue.QueueScreen
import com.dewijones92.totum.ui.queue.queueRowGripTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class QueueRowDragTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val container = FakeAppContainer()
    private val queue get() = container.playbackQueue
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

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

    private fun ids() = queue.state.value.entries.map { it.item.item.id.value }

    private fun grip(label: String, at: Int = 0) =
        composeTestRule.onAllNodesWithContentDescription(label, useUnmergedTree = true)[at]

    private fun SemanticsNodeInteraction.top() = fetchSemanticsNode().boundsInRoot.top

    private fun show() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent { TotumTheme { QueueScreen(container) } }
        composeTestRule.mainClock.advanceTimeBy(FRAME_MS * SETTLE_FRAMES)
    }

    private fun drag(
        node: () -> SemanticsNodeInteraction,
        dy: Float,
        steps: Int,
        framesPerStep: Long = 2,
        pressFirst: Boolean = true,
        onStep: (Float) -> Unit = {},
    ) {
        if (pressFirst) {
            node().performTouchInput { down(center) }
            composeTestRule.mainClock.advanceTimeBy(FRAME_MS)
        }
        repeat(steps) { step ->
            node().performTouchInput { moveBy(Offset(0f, dy / steps)) }
            composeTestRule.mainClock.advanceTimeBy(FRAME_MS * framesPerStep)
            onStep(dy / steps * (step + 1))
        }
    }

    private fun release(node: () -> SemanticsNodeInteraction) {
        node().performTouchInput { up() }
        composeTestRule.mainClock.advanceTimeBy(FRAME_MS * SETTLE_FRAMES)
    }

    @Test
    fun draggingTheGripMovesTheRowItBelongsTo() {
        queue.playAll(ROWS.map(::playable))
        show()
        val label = context.getString(R.string.queue_reorder)
        val density = composeTestRule.density.density

        drag({ grip(label) }, ROW_DP * 2 * density, STEPS)
        release { grip(label) }

        assertTrue("two rows of travel should be about two places: ${ids()}", ids().indexOf("first") in 1..3)
    }

    @Test
    fun theDraggedRowStaysUnderTheFingerAcrossGroupHeaders() {
        queue.playAll(listOf(playable("first")))
        queue.enqueueAll(listOf("g1", "g2").map(::playable), QueueGroup("album:g", "Group G"))
        queue.enqueueAll(listOf("h1", "h2").map(::playable), QueueGroup("album:h", "Group H"))
        queue.enqueueAll(listOf("last1", "last2").map(::playable))
        show()
        val density = composeTestRule.density.density
        val node = { composeTestRule.onNodeWithTag(queueRowGripTag("first"), useUnmergedTree = true) }
        val startTop = node().top()
        val worst = mutableListOf<Float>()

        drag(node, ROW_DP * 4 * density, STEPS * 2, framesPerStep = 1) { travelled ->
            worst += abs(node().top() - (startTop + travelled))
        }
        release(node)

        val tolerance = SLOP_DP * density
        println("dewidebug drift dp ${worst.map { (it / density).toInt() }}")
        assertTrue(
            "the dragged row drifted from the finger by up to ${worst.max() / density}dp " +
                "(tolerance $SLOP_DP dp): ${worst.map { (it / density).toInt() }} -> ${ids()}",
            worst.max() <= tolerance,
        )
        assertTrue("four rows of travel should move it past the first group: ${ids()}", ids().indexOf("first") >= 2)
    }

    @Test
    fun aRowOfYourOwnDraggedIntoAGroupStaysUnderTheFinger() {
        queue.playAll(listOf("g1", "g2", "g3").map(::playable), QueueGroup("album:g", "Group G"))
        queue.enqueueAll(listOf(playable("mine")))
        show()
        val density = composeTestRule.density.density
        val node = { composeTestRule.onNodeWithTag(queueRowGripTag("mine"), useUnmergedTree = true) }
        val startTop = node().top()
        val worst = mutableListOf<Float>()

        drag(node, -ROW_DP * 2 * density, STEPS * 2, framesPerStep = 1) { travelled ->
            worst += abs(node().top() - (startTop + travelled))
        }
        release(node)

        val tolerance = SLOP_DP * density
        assertTrue(
            "the dragged row drifted from the finger by up to ${worst.max() / density}dp " +
                "(tolerance $SLOP_DP dp): ${worst.map { (it / density).toInt() }} -> ${ids()}",
            worst.max() <= tolerance,
        )
        assertTrue("two rows up should put it inside the group: ${ids()}", ids().indexOf("mine") < ids().indexOf("g3"))
    }

    @Test
    fun aDragSurvivesTheQueueGrowingUnderIt() {
        queue.playAll(ROWS.map(::playable))
        show()
        val density = composeTestRule.density.density
        val node = { composeTestRule.onNodeWithTag(queueRowGripTag("first"), useUnmergedTree = true) }

        drag(node, ROW_DP * density, STEPS / 2, framesPerStep = 1)
        queue.enqueueAll(listOf("late1", "late2").map(::playable))
        composeTestRule.mainClock.advanceTimeBy(FRAME_MS * 2)
        drag(node, ROW_DP * 2 * density, STEPS, framesPerStep = 1, pressFirst = false)
        release(node)

        assertTrue("the drag kept going after the queue grew: ${ids()}", ids().indexOf("first") >= 2)
    }

    @Test
    fun draggingAGroupHeaderMovesTheWholeGroup() {
        queue.playAll(listOf(playable("a")))
        queue.enqueueAll(listOf("g1", "g2", "g3").map(::playable), QueueGroup("album:g", "Group G"))
        queue.enqueueAll(listOf("b", "c", "d").map(::playable))
        show()
        val label = context.getString(R.string.queue_group_reorder, "Group G")
        val density = composeTestRule.density.density

        drag({ grip(label) }, ROW_DP * 2 * density, STEPS)
        release { grip(label) }

        val order = ids()
        val g = order.indexOf("g1")
        assertTrue("the group stays together and in order: $order", order.subList(g, g + 3) == listOf("g1", "g2", "g3"))
        assertTrue("two rows of travel should carry it below b: $order", order.indexOf("b") < g)
    }

    @Test
    fun holdingADragAtTheBottomScrollsPastTheScreen() {
        queue.playAll(ROWS.map(::playable) + (1..LONG_QUEUE).map { playable("more$it") })
        show()
        val label = context.getString(R.string.queue_reorder)
        val listBottom = composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot.bottom

        val node = { grip(label, at = 0) }
        val startY = node().fetchSemanticsNode().boundsInRoot.center.y
        drag(node, listBottom - startY - EDGE_PX, STEPS)
        repeat(HOLD_FRAMES) { composeTestRule.mainClock.advanceTimeBy(FRAME_MS) }
        release(node)

        assertTrue("holding at the edge should carry it past a screenful: ${ids()}", ids().indexOf("first") > SCREENFUL)
    }

    private companion object {
        const val FRAME_MS = 16L
        const val SETTLE_FRAMES = 30L
        const val STEPS = 20
        const val ROW_DP = 85f
        const val SLOP_DP = 24f
        const val EDGE_PX = 20f
        const val HOLD_FRAMES = 300
        const val LONG_QUEUE = 34
        const val SCREENFUL = 10
        val ROWS = listOf("first", "second", "third", "fourth", "fifth", "sixth")
    }
}
