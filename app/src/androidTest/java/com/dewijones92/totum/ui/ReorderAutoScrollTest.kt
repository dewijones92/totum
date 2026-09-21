package com.dewijones92.totum.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.ui.common.rememberReorderState
import com.dewijones92.totum.ui.common.reorderable
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Dragging an item further than the screen, which is the whole point of the feature.
 *
 * Dewi, 2026-08-01: *"able to drag and move while scrolling?? move big distances etc"*. His
 * queue is 74 items; before auto-scroll a drag could not move anything past the bottom of the
 * viewport, so "send this to the end" was simply not expressible as a gesture however patient
 * you were.
 *
 * Instrumented rather than a unit test because the thing under test is the WIRING: the swap
 * arithmetic is covered on the JVM by `ReorderStateTest`, and it stayed correct throughout —
 * what was missing was anything calling it while the finger sat still at an edge. A finger held
 * perfectly still emits no pointer events at all, so nothing but a real gesture on a real list
 * proves the scroll loop runs.
 *
 * The assertion is deliberately "more than a screenful" rather than an exact index: the precise
 * number depends on scroll timing, and pinning it would make the test fragile about the one
 * thing that does not matter. What matters is that the reach is no longer capped by the display.
 */
class ReorderAutoScrollTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val moves = mutableListOf<Pair<Int, Int>>()

    /** Rows added while a drag is in flight, so a test can change the list's size under it. */
    private val appended = mutableIntStateOf(0)

    /**
     * The same list, but able to GROW under a drag — its own setup rather than a flag on the shared
     * one. Threading the growth through [setUpList] changed what every other case composed and broke
     * the auto-scroll test, which is a bad trade for saving a few lines.
     */
    private fun setUpGrowableList() {
        composeTestRule.setContent {
            val order = remember { mutableStateListOf<Int>().apply { addAll(0 until ITEMS) } }
            val rows = order + List(appended.intValue) { ITEMS + it }
            val listState = rememberLazyListState()
            val reorder = rememberReorderState(listState) { from, to ->
                moves += from to to
                if (from in order.indices && to in order.indices) order.add(to, order.removeAt(from))
            }
            LazyColumn(
                state = listState,
                modifier = with(reorder) { Modifier.fillMaxSize().testTag(LIST).reorderContainer() },
            ) {
                itemsIndexed(rows, key = { _, item -> item }) { index, item ->
                    with(reorder) {
                        Row(
                            modifier = Modifier
                                .height(ROW_HEIGHT.dp)
                                .fillMaxWidth()
                                .reorderable(reorder, index)
                                .testTag("row-$item"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = "Item $item", modifier = Modifier.weight(1f))
                            Box(
                                Modifier
                                    .height(HANDLE_HEIGHT.dp)
                                    .width(HANDLE_HEIGHT.dp)
                                    .dragHandle(index, rows.size)
                                    .testTag("grip-$item"),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Rows of two very different heights, alternating — a real queue since titles stopped being
     * capped, where a one-line row is ~100dp beside a four-line one at ~250dp.
     *
     * Every other case here pins `.height(rowHeight.dp)` on every row, so all of them are uniform
     * by construction and none could see the step being measured against the wrong row. Same shape
     * of blind spot as the original version of this file making the handle the whole row.
     */
    private fun setUpMixedList() {
        composeTestRule.setContent {
            val order = remember { mutableStateListOf<Int>().apply { addAll(0 until ITEMS) } }
            val listState = rememberLazyListState()
            val reorder = rememberReorderState(listState) { from, to ->
                moves += from to to
                order.add(to, order.removeAt(from))
            }
            LazyColumn(
                state = listState,
                modifier = with(reorder) { Modifier.fillMaxSize().testTag(LIST).reorderContainer() },
            ) {
                itemsIndexed(order, key = { _, item -> item }) { index, item ->
                    with(reorder) {
                        Row(
                            modifier = Modifier
                                // Odd rows are TALL. The row being crossed is therefore a different
                                // height from the row doing the crossing, which is the whole point.
                                .height(if (item % 2 == 1) TALL_ROW.dp else ROW_HEIGHT.dp)
                                .fillMaxWidth()
                                .reorderable(reorder, index)
                                .testTag("row-$item"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = "Item $item", modifier = Modifier.weight(1f))
                            Box(
                                Modifier
                                    .height(HANDLE_HEIGHT.dp)
                                    .width(HANDLE_HEIGHT.dp)
                                    .dragHandle(index, order.size)
                                    .testTag("grip-$item"),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * A list of very unequal rows still drags sanely: one tall row of travel moves at most one place.
     *
     * **This is a smoke test, not the guard** — and it is labelled that way because I wrote it
     * believing it was the guard and then mutation-checked it. Replacing the per-row step with the
     * old single shared height and re-running it on this emulator gives **OK (1 test)**: the step
     * becomes whichever row measured LAST, which on an alternating list can perfectly well be the
     * 192dp one, and then 192dp of travel is one move either way. Which row measures last is not
     * something a test can control.
     *
     * So the step rule is pinned on the JVM instead (`ReorderStateTest`: crossing a tall row, a
     * short row, and two rows of different heights in one motion — all three fail against the shared
     * height). What this case is genuinely worth: a real GESTURE on a list whose rows differ in
     * height does not double-move, drift or throw, which no JVM test can claim. The repo already
     * learned the harder version of this lesson — a long-travel gesture assertion failed on CI twice
     * for screen-size reasons, which is why the ten-row claim lives on the JVM too.
     */
    @Test
    fun aMixedHeightListStillDragsOnePlaceAtATime() {
        composeTestRule.mainClock.autoAdvance = false
        setUpMixedList()

        composeTestRule.onNodeWithTag("grip-0").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            // Exactly the height of the TALL row below, and nowhere near an edge.
            moveTo(center + Offset(0f, TALL_ROW * density))
        }
        composeTestRule.mainClock.advanceTimeBy(TICK_MS)
        composeTestRule.onNodeWithTag("grip-0").performTouchInput { up() }

        // 0 or 1: the long-press detector absorbs the first movement, so one row of travel can land
        // short — the same honest slop the three-row case tolerates. What matters is that it is
        // nowhere near the three the shared-height version produces.
        assertTrue(
            "one tall row of travel must be at most one move, not three: $moves",
            moves.size <= 1,
        )
    }

    /** The list every test drives: a small grip inside a much taller row, as on the real queue. */
    private fun setUpList(rowHeight: Float = ROW_HEIGHT, handleHeight: Float = HANDLE_HEIGHT) {
        composeTestRule.setContent {
            val order = remember { mutableStateListOf<Int>().apply { addAll(0 until ITEMS) } }
            val listState = rememberLazyListState()
            val reorder = rememberReorderState(listState) { from, to ->
                moves += from to to
                order.add(to, order.removeAt(from))
            }
            LazyColumn(
                state = listState,
                modifier = with(reorder) { Modifier.fillMaxSize().testTag(LIST).reorderContainer() },
            ) {
                itemsIndexed(order, key = { _, item -> item }) { index, item ->
                    with(reorder) {
                        // Shaped like the REAL queue: a small grip inside a much taller row.
                        // The first version of this test made the handle the whole row, which
                        // hid a real bug — the row height was being measured from the handle,
                        // so items reordered about four times faster than the finger moved.
                        Row(
                            modifier = Modifier
                                .height(rowHeight.dp)
                                .fillMaxWidth()
                                .reorderable(reorder, index)
                                .testTag("row-$item"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = "Item $item", modifier = Modifier.weight(1f))
                            Box(
                                Modifier
                                    .height(handleHeight.dp)
                                    .width(handleHeight.dp)
                                    .dragHandle(index, order.size)
                                    .testTag("grip-$item"),
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun draggingToTheBottomEdgeKeepsMovingPastTheVisibleRows() {
        composeTestRule.mainClock.autoAdvance = false
        setUpList()

        // ONE gesture, injected on the LIST and hit-tested onto the row beneath — pointer state
        // does not survive being split across blocks, which is why the first attempt at this
        // recorded no movement whatsoever.
        composeTestRule.onNodeWithTag("grip-0").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            // Far enough down to sit inside the bottom edge zone and stay there.
            moveTo(center + Offset(0f, LONG_DRAG_PX))
        }
        // Time passing with the finger stationary IS the test: no further pointer events arrive,
        // so anything that moves from here moved because the list scrolled itself.
        repeat(HOLD_TICKS) { composeTestRule.mainClock.advanceTimeBy(TICK_MS) }
        composeTestRule.onNodeWithTag("grip-0").performTouchInput { up() }

        assertTrue(
            "expected the drag to reach past the visible rows; moves=${moves.size} $moves",
            moves.size > VISIBLE_ROWS,
        )
    }

    private companion object {
        const val ITEMS = 40
        const val ROW_HEIGHT = 64f

        /** Three times a short row: a four-line title beside a one-line one, roughly to scale. */
        const val TALL_ROW = 192f

        /** A grip much smaller than its row, as on the real queue screen. */
        const val HANDLE_HEIGHT = 24f

        /** Well inside the edge zone, so the hold unambiguously counts as "held there". */
        /** Well past the bottom of the viewport, so the finger sits in the edge zone. */
        const val LONG_DRAG_PX = 4_000f

        /** Three rows of travel, allowing one event of long-press slop either way. */
        val EXPECTED_MOVES = 2..4

        const val LIST = "list"

        /** Comfortably past Compose's long-press threshold. */
        const val LONG_PRESS_MS = 1_000L
        const val HOLD_TICKS = 40
        const val TICK_MS = 16L

        /** Far enough that "it only ever moves one" is unmistakable, short enough to stay on screen. */
        const val SURVIVAL_ROWS = 5

        /** A frame between moves, so recomposition happens mid-gesture as it does on a device. */
        const val FRAME_MS = 32L

        /**
         * A generous upper bound on what fits on screen at [ROW_HEIGHT]dp — the point is to
         * assert the drag went FURTHER than the display allows, without depending on the exact
         * size of whatever device this runs on.
         */
        const val VISIBLE_ROWS = 15

        /** One generous frame, so a freshly composed row has bounds to be tapped. */
        const val FRAME_SETTLE_MS = 500L
    }

    /**
     * A drag of exactly three rows must move exactly three places.
     *
     * This is the bug the auto-scroll test could not see. The row height was measured from the
     * DRAG HANDLE — a 24dp grip inside a 64dp row here, and 24dp inside ~95dp on the real queue
     * — so a swap fired every handle-height of travel instead of every row. Items reordered
     * roughly four times faster than the finger, which is precisely "the dragger doesn't work
     * well": you aim for three places down and land nine.
     *
     * Asserting the COUNT rather than "it moved" is the whole point; the old behaviour moved
     * too, just wrongly.
     */
    @Test
    fun draggingThreeRowsMovesExactlyThreePlaces() {
        composeTestRule.mainClock.autoAdvance = false
        setUpList()

        composeTestRule.onNodeWithTag("grip-0").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            // Three rows down, and nowhere near an edge, so auto-scroll cannot contribute.
            moveTo(center + Offset(0f, ROW_HEIGHT * 3 * density))
        }
        composeTestRule.mainClock.advanceTimeBy(TICK_MS)
        composeTestRule.onNodeWithTag("grip-0").performTouchInput { up() }

        // A range, and honestly so: the long-press detector absorbs the first movement before
        // deltas begin, so three rows of travel lands two or three places down depending on
        // event timing. That slop is worth tolerating because it does not blunt the test —
        // measuring from the 24dp handle instead of the 64dp row gives EIGHT moves for this
        // same gesture, which is nowhere near this range.
        assertTrue(
            "three rows of travel must be about three places, not eight: $moves",
            moves.size in EXPECTED_MOVES,
        )
    }

    /**
     * NOT here: the ten-places-in-one-motion claim.
     *
     * It lived here and failed on CI twice for reasons that had nothing to do with the code. Ten
     * rows at 64dp is more travel than CI's emulator is tall, so the finger was clamped into the
     * auto-scroll edge zone and the measurement became one of the clock — exactly 30 moves, reading
     * as the swap-rate bug this file exists to catch. Shortening the rows to fit then made the
     * gesture too small for the injector's frame timing there, and it reported 1 move. Both passed
     * on the emulator here, both times.
     *
     * A test that reports a swap-rate bug because of the screen it ran on is worse than no test, so
     * the claim moved to `ReorderStateTest` on the JVM, where the accumulator can be driven directly
     * over ten rows with no device in the way. What is genuinely instrumented-only stays here: that
     * a real gesture turns into swaps at the right rate (three rows, below) and that a drag can
     * travel further than the viewport (auto-scroll, above).
     *
     * Two other things learned while trying, worth keeping so nobody re-derives them. Asserting
     * "the list did not scroll" does not work — `LazyColumn` re-anchors on the dragged item's key,
     * so `firstVisibleItemIndex` follows the item and reports a scroll on a drag that never neared
     * an edge. And splitting a gesture to release the finger in a second `performTouchInput` costs
     * a second lookup of a grip that has by then moved, so Compose scrolls the list to bring it
     * into view before it will touch it.
     */

    /**
     * A drag must survive the row it is dragging changing index — which is what every swap does.
     *
     * Dewi, 2026-08-07, on 0.1.359: *"i am only able to drag the items in the queue by 1 position"*.
     * He was right and every test here said otherwise.
     *
     * The grip's `pointerInput` was keyed on `index`. The first swap moves the row, so its index
     * changes, so the key changes, so Compose **tears down the pointer input and cancels the gesture
     * in flight**. Exactly one swap per touch, forever.
     *
     * The reason no test caught it is the reason this one is written differently: every other case
     * here sets `mainClock.autoAdvance = false` so it can hold a finger still and watch auto-scroll.
     * A frozen clock means **no recomposition happens during the gesture**, so the index never
     * changes as far as the composition is concerned and the pointer input is never restarted. The
     * tests were structurally incapable of seeing this. CI saw it — a run reported exactly 1 move
     * where the local emulator reported 10 — and I put it down to frame timing rather than believing
     * it. It was the bug.
     *
     * So: the clock RUNS here, and the gesture is spread over real frames.
     */
    @Test
    fun aDragSurvivesTheRowChangingIndexUnderIt() {
        setUpList()

        // A SEPARATE injection per row, with the composition allowed to settle between them. That
        // gap is the whole point: inside one `performTouchInput` block every event is delivered
        // before Compose recomposes, so the row's index never changes mid-gesture and the defect is
        // invisible. On a device the queue recomposes constantly — a 500ms position ticker alone
        // guarantees it — so the gap is the realistic case, not the artificial one.
        val start = composeTestRule.onNodeWithTag("grip-0").fetchSemanticsNode().boundsInRoot.center
        val step = ROW_HEIGHT * composeTestRule.density.density

        composeTestRule.onNodeWithTag("grip-0").performTouchInput { down(start) }
        repeat(SURVIVAL_ROWS) { i ->
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag(LIST).performTouchInput {
                moveTo(Offset(start.x, start.y + step * (i + 1)))
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(LIST).performTouchInput { up() }
        composeTestRule.waitForIdle()

        // SURVIVAL, not accuracy. The exact count is short of the rows injected because the drag
        // detector's touch slop absorbs the opening movement, and pinning it would make this fragile
        // about the one thing it is not testing -- how many places a given travel is worth is
        // `ReorderStateTest`'s job, on the JVM, where there is no slop. What matters here is that a
        // gesture keeps producing swaps after the first, which before the fix it could not.
        assertTrue(
            "the drag stopped after ${moves.size} move(s): $moves. A gesture must survive its own " +
                "swaps -- the row's index changes with every one, and re-keying the pointer input " +
                "on it cancels the drag after the first, which is exactly one place per touch",
            moves.size >= SURVIVAL_ROWS - 2,
        )
        assertTrue(
            "every move must be by a single place, and each must continue the last: $moves",
            moves.zipWithNext().all { (a, b) -> b.first == a.second } && moves.all { it.second - it.first == 1 },
        )
    }

    /**
     * The other half of the same defect: the list CHANGING SIZE must not end a drag either.
     *
     * `itemCount` was the gesture's second key, so a download finishing, an item being auto-queued,
     * or playback advancing — anything that changes the queue's length — would tear the pointer
     * input down mid-drag exactly as a swap did. Dewi's queue auto-downloads while he uses it, so
     * this is not a contrived case; it simply never had a name because the swap-cancelling half fired
     * first every time.
     *
     * Driven by appending a row between pointer events, which is what those events do.
     */
    @Test
    fun aDragSurvivesTheListGrowingUnderIt() {
        setUpGrowableList()

        val start = composeTestRule.onNodeWithTag("grip-0").fetchSemanticsNode().boundsInRoot.center
        val step = ROW_HEIGHT * composeTestRule.density.density

        composeTestRule.onNodeWithTag("grip-0").performTouchInput { down(start) }
        repeat(SURVIVAL_ROWS) { i ->
            composeTestRule.waitForIdle()
            // The list gets longer under the finger, as an auto-download completing makes it.
            composeTestRule.runOnUiThread { appended.intValue += 1 }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag(LIST).performTouchInput {
                moveTo(Offset(start.x, start.y + step * (i + 1)))
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(LIST).performTouchInput { up() }
        composeTestRule.waitForIdle()

        assertTrue(
            "the drag stopped after ${moves.size} move(s) once the list changed size: $moves",
            moves.size >= SURVIVAL_ROWS - 2,
        )
    }

    // NOT tested here, honestly: a case driving the grip with `combinedClickable` also on the
    // row fails with "Failed to inject touch input" however it is arranged — three attempts,
    // including matching MediaItemRow's exact modifier order. The injector, not the code: the
    // two tests above drive the same grip happily without that modifier. The fix itself is
    // small and readable — the grip uses detectDragGestures rather than
    // detectDragGesturesAfterLongPress, so a long press on it is no longer a long press on the
    // row — and it was verified by hand on the emulator.
}
