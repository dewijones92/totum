package com.dewijones92.totum.ui.common

import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The arithmetic that decides when a drag becomes a move.
 *
 * Worth testing apart from the gesture because it is where the off-by-ones live — the ends of
 * the list especially — and because auto-scroll feeds this same function. A scroll of N pixels
 * and a finger travelling N pixels are the same event to a list, and this is where that claim
 * is either true or not.
 */
class ReorderStateTest {

    private val moves = mutableListOf<Pair<Int, Int>>()

    /** The distance Dewi asked about by name, and the row height these cases use. */
    private companion object {
        const val TEN = 10
        const val ROW = 100

        /** A 1-line row and a 4-line one, roughly as the queue measures them at 3x density. */
        const val SHORT = 100
        const val TALL = 250
    }

    private fun state(startIndex: Int, count: Int, rowHeight: Int = ROW) =
        ReorderState({ from, to -> moves += from to to }, LazyListState(), TestScope()).apply {
            this.rowHeight = rowHeight
            this.itemCount = count
            this.draggingIndex = startIndex
        }

    /**
     * Ten places in one continuous motion, which is what Dewi asked about by name (2026-08-06):
     * *"make sure the items in the queue dragging drag successfully????? in one motion 10
     * places?????"*.
     *
     * Here rather than as a gesture, and that is a deliberate retreat. The instrumented version
     * failed on CI twice for reasons that had nothing to do with the code: ten rows at 64dp is more
     * travel than CI's emulator is tall, so the finger was clamped into the auto-scroll edge zone
     * and the test measured the clock (30 moves); shortening the rows then made the gesture too
     * small for the injector's frame timing there (1 move). Both passed locally. A test that
     * reports a swap-rate bug because of the screen it ran on is worse than no test.
     *
     * What actually needed proving is the ACCUMULATOR over distance — any per-swap error is
     * invisible over three rows and glaring over ten — and that is arithmetic, which belongs here.
     * The gesture-to-swap wiring stays covered instrumented by `draggingThreeRowsMovesExactlyThree
     * Places`, and long-distance travel by `draggingToTheBottomEdgeKeepsMovingPastTheVisibleRows`.
     */
    @Test
    fun `ten rows of continuous travel move exactly ten places`() {
        val reorder = state(startIndex = 0, count = 40)

        // Stepped, as a real finger arrives: one row of travel per event.
        repeat(TEN) { reorder.applyDrag(ROW.toFloat()) }

        assertEquals("ten rows must be ten moves, not thirty", TEN, moves.size)
        assertEquals("and each move must be by exactly one place", (0 until TEN).map { it to it + 1 }, moves)
        assertEquals("the item must finish ten places down", TEN, reorder.draggingIndex)
    }

    /** The same distance arriving as ONE event must not behave differently from ten. */
    @Test
    fun `ten rows arriving in a single event still move ten places`() {
        val reorder = state(startIndex = 0, count = 40)

        reorder.applyDrag(ROW.toFloat() * TEN)

        assertEquals(TEN, moves.size)
        assertEquals(TEN, reorder.draggingIndex)
    }

    /**
     * And the remainder is not thrown away between events.
     *
     * Ten drags of half a row is five rows of travel. An accumulator that reset per event would
     * report nothing at all; one that rounded each event up would report ten.
     */
    @Test
    fun `half-row steps accumulate rather than being dropped or rounded`() {
        val reorder = state(startIndex = 0, count = 40)

        repeat(TEN) { reorder.applyDrag(ROW / 2f) }

        assertEquals("five rows of travel is five places", TEN / 2, moves.size)
        assertEquals(TEN / 2, reorder.draggingIndex)
    }

    // ---- the drag continues after the row it holds has moved -----------------------------------

    /**
     * The state half of the one-place-only bug.
     *
     * A drag is a sequence of events against a row whose index is changing under it — each swap
     * moves it. The gesture layer was cancelling itself on that change (see
     * `ReorderAutoScrollTest.aDragSurvivesTheRowChangingIndexUnderIt`); this pins the other half,
     * that the arithmetic tracks the item rather than the position it started at. Without it, five
     * events would move five DIFFERENT rows one place each.
     */
    @Test
    fun `each event continues the same item from where the last one left it`() {
        val reorder = state(startIndex = 3, count = 40)

        repeat(TEN / 2) { reorder.applyDrag(ROW.toFloat()) }

        assertEquals(
            "every move must hand off to the next, which is one item travelling five places",
            listOf(3 to 4, 4 to 5, 5 to 6, 6 to 7, 7 to 8),
            moves,
        )
        assertEquals(8, reorder.draggingIndex)
    }

    @Test
    fun `a drag reverses mid-gesture without losing its place`() {
        val reorder = state(startIndex = 5, count = 40)

        repeat(3) { reorder.applyDrag(ROW.toFloat()) }
        repeat(2) { reorder.applyDrag(-ROW.toFloat()) }

        assertEquals(listOf(5 to 6, 6 to 7, 7 to 8, 8 to 7, 7 to 6), moves)
        assertEquals("three down and two back up is one place down", 6, reorder.draggingIndex)
    }

    /**
     * The list growing under a drag must not end it.
     *
     * `itemCount` was the second key on the gesture, so a download finishing — which changes the
     * queue's size — would have cancelled a drag in progress just as a swap did. Nothing else here
     * would have noticed: the arithmetic simply reads the new count on the next event.
     */
    @Test
    fun `the drag carries on when the list grows under it`() {
        val reorder = state(startIndex = 8, count = 10)

        reorder.applyDrag(ROW.toFloat())
        reorder.itemCount = 40
        repeat(3) { reorder.applyDrag(ROW.toFloat()) }

        assertEquals("the extra rows must become reachable, not end the drag", 4, moves.size)
        assertEquals(12, reorder.draggingIndex)
    }

    /** And shrinking must clamp rather than run off the new end. */
    @Test
    fun `the drag stops at the new end when the list shrinks under it`() {
        val reorder = state(startIndex = 8, count = 40)

        reorder.applyDrag(ROW.toFloat())
        reorder.itemCount = 10
        repeat(5) { reorder.applyDrag(ROW.toFloat()) }

        assertEquals("index 9 is the last of ten", 9, reorder.draggingIndex)
        assertEquals(listOf(8 to 9), moves)
    }

    @Test
    fun `a drag shorter than a row moves nothing`() {
        val reorder = state(startIndex = 2, count = 10)

        reorder.applyDrag(60f)

        assertEquals(emptyList<Pair<Int, Int>>(), moves)
        assertEquals(2, reorder.draggingIndex)
    }

    @Test
    fun `crossing one row swaps once`() {
        val reorder = state(startIndex = 2, count = 10)

        reorder.applyDrag(100f)

        assertEquals(listOf(2 to 3), moves)
        assertEquals(3, reorder.draggingIndex)
    }

    /**
     * The case auto-scroll depends on: a single large travel must move several positions, not
     * one. Scrolling a long way in one tick, or a fast flick of the finger, both arrive here.
     */
    @Test
    fun `one large travel moves several positions`() {
        val reorder = state(startIndex = 0, count = 10)

        reorder.applyDrag(350f)

        assertEquals(listOf(0 to 1, 1 to 2, 2 to 3), moves)
        assertEquals(3, reorder.draggingIndex)
    }

    @Test
    fun `dragging upwards moves the other way`() {
        val reorder = state(startIndex = 5, count = 10)

        reorder.applyDrag(-250f)

        assertEquals(listOf(5 to 4, 4 to 3), moves)
        assertEquals(3, reorder.draggingIndex)
    }

    /**
     * Held against the end of the list, it must stop rather than keep accumulating — otherwise
     * the row drifts off under the finger and comes back only after an equal drag the other way,
     * which feels broken. This matters far more now that auto-scroll can hold a row at the
     * bottom edge indefinitely.
     */
    @Test
    fun `it stops at the bottom instead of drifting`() {
        val reorder = state(startIndex = 8, count = 10)

        reorder.applyDrag(1_000f)

        assertEquals(listOf(8 to 9), moves)
        assertEquals(9, reorder.draggingIndex)
        assertEquals("no leftover travel, or the row drifts", 0f, reorder.offsetFor(9), 0.01f)
    }

    @Test
    fun `it stops at the top instead of drifting`() {
        val reorder = state(startIndex = 1, count = 10)

        reorder.applyDrag(-1_000f)

        assertEquals(listOf(1 to 0), moves)
        assertEquals(0, reorder.draggingIndex)
        assertEquals(0f, reorder.offsetFor(0), 0.01f)
    }

    // ---- rows of different heights, which is every queue since titles stopped truncating -------

    /**
     * A tall row costs MORE travel to cross than a short one, and the step follows the row being
     * crossed rather than whichever row measured last.
     *
     * This is the defect the wrap change introduced (2026-09-21). Rows were near-uniform while
     * titles capped at two lines; now a 4-line episode title next to a 1-line one is ~250dp
     * against ~100dp in the same viewport, and one shared height made the step whichever of them
     * Compose measured last — so half the queue dragged at the wrong rate. Same quantity, same
     * class of bug as the handle-height defect, and the reason it survived the existing tests is
     * that both of them make every row the same height by construction: `ReorderStateTest` set one
     * number and `ReorderAutoScrollTest` pins `.height(rowHeight.dp)` on every row.
     */
    @Test
    fun `crossing a tall row takes its height, not the previous row's`() {
        val reorder = state(startIndex = 0, count = 3, rowHeight = 0).apply {
            setRowHeight(0, SHORT)
            setRowHeight(1, TALL)
            setRowHeight(2, SHORT)
        }

        // A short row's worth of travel is not enough to get past the TALL row below it.
        reorder.applyDrag(SHORT.toFloat())
        assertEquals("a short step must not cross a tall row", emptyList<Pair<Int, Int>>(), moves)

        // The rest of the tall row's height completes exactly one move, and no more.
        reorder.applyDrag((TALL - SHORT).toFloat())
        assertEquals(listOf(0 to 1), moves)
        assertEquals(1, reorder.draggingIndex)
    }

    /**
     * And the other way round: dragging DOWN past a short row must not need a tall row's travel.
     * With one shared height this took 250px to move one place and then jumped two at once.
     */
    @Test
    fun `crossing a short row takes only its height`() {
        // Measured with the TALL row LAST on purpose. The fallback is the most recent measurement,
        // so a version that ignores the per-index heights would demand a tall row's travel here —
        // which is what makes this case discriminate rather than pass on the luck of the order. It
        // passed the one-shared-height mutant until the order was fixed.
        val reorder = state(startIndex = 0, count = 3, rowHeight = 0).apply {
            setRowHeight(1, SHORT)
            setRowHeight(2, SHORT)
            setRowHeight(0, TALL)
        }

        reorder.applyDrag(SHORT.toFloat())

        assertEquals("one short row of travel is one move", listOf(0 to 1), moves)
    }

    /**
     * Two rows of different heights in one motion, each costing its own height.
     *
     * NOTE: this does **not** guard the height-swap, though an earlier version of this KDoc claimed
     * it did, and claimed the wrong mechanism for it too (the swap writes `from` and `to`; the next
     * crossing reads `to + 1`, which it never touched). Simulated with and without the swap, this
     * case and both of the ones above are byte-identical. The two cases below are the ones that see
     * it.
     */
    @Test
    fun `a two-row drag measures each row it crosses`() {
        val reorder = state(startIndex = 0, count = 4, rowHeight = 0).apply {
            setRowHeight(0, SHORT)
            setRowHeight(1, SHORT)
            setRowHeight(2, TALL)
            setRowHeight(3, SHORT)
        }

        // Short + tall in one continuous motion: exactly two moves, with nothing left over.
        reorder.applyDrag((SHORT + TALL).toFloat())

        assertEquals(listOf(0 to 1, 1 to 2), moves)
        assertEquals(2, reorder.draggingIndex)
        assertEquals("no leftover travel", 0f, reorder.offsetFor(2), 0.01f)
    }

    /**
     * A drag down and back comes home.
     *
     * This is what the height-swap is actually for, and the only shape in which its absence is
     * USER-VISIBLE: drag a tall row one place down and one place back, and without the swap the
     * return leg is measured against the tall row's height instead of the short one it is now
     * crossing — so the row is stranded one place from where it started with travel left over.
     * Simulated both ways: with the swap `[(0,1), (1,0)]` and home; without it `[(0,1)]` and
     * stranded at index 1 with -100 of accumulated travel.
     */
    @Test
    fun `dragging down and back returns the row to where it started`() {
        val reorder = state(startIndex = 0, count = 3, rowHeight = 0).apply {
            setRowHeight(0, TALL)
            setRowHeight(1, SHORT)
            setRowHeight(2, SHORT)
        }

        reorder.applyDrag(SHORT.toFloat())
        reorder.applyDrag(-SHORT.toFloat())

        assertEquals(listOf(0 to 1, 1 to 0), moves)
        assertEquals("the row must come home, not sit one place down", 0, reorder.draggingIndex)
        assertEquals("and with no travel left over", 0f, reorder.offsetFor(0), 0.01f)
    }

    /**
     * Crossing INTO a row nothing has measured yet uses the dragged row's own height — and the
     * swap is what keeps that true for the next crossing.
     *
     * Without it, the tall row's height stays attached to index 0 after the move, so the second
     * crossing is measured against a short fallback and fires immediately: one gesture, two moves,
     * where a finger travelled one row's worth.
     */
    @Test
    fun `crossing into an unmeasured row does not double-move`() {
        val reorder = state(startIndex = 0, count = 3, rowHeight = 0).apply {
            setRowHeight(0, TALL)
            setRowHeight(1, SHORT)
            // Index 2 is deliberately never measured — a row that has not been on screen yet.
        }

        reorder.applyDrag(SHORT.toFloat())
        reorder.applyDrag(SHORT.toFloat())

        assertEquals("one row of travel each time is one move each time", listOf(0 to 1), moves)
        assertEquals(1, reorder.draggingIndex)
    }

    /**
     * A measurement for a row the list no longer has must not decide anything.
     *
     * The step is read BEFORE the bounds check — deliberately, so an unmeasured list does not zero
     * the accumulator — which means a stale height at the end of a shortened list lets travel
     * accumulate against a row that is gone, and the dragged row drifts past the end before
     * snapping back. Setting `itemCount` prunes them.
     */
    @Test
    fun `heights for rows the list no longer has are dropped`() {
        val reorder = state(startIndex = 0, count = 5, rowHeight = 0).apply {
            setRowHeight(0, SHORT)
            setRowHeight(1, SHORT)
            // The stale height sits at exactly `itemCount` — the first index off the end, which is
            // the only one `to` can reach, and therefore the only one whose staleness can be read.
            // An earlier version put it at index 4 with `itemCount = 2`: unreachable, so the case
            // passed with the prune deleted and guarded nothing. Named after a mechanism it did not
            // pin, in the commit that fixed that same defect twice over.
            setRowHeight(2, TALL)
            // The list shrinks under the drag — a download finishing, an item auto-queued away.
            itemCount = 2
        }

        // Enough travel to reach the end and try to cross it.
        reorder.applyDrag(TALL.toFloat())

        assertEquals(listOf(0 to 1), moves)
        assertEquals(
            "a height for a row that is gone must not hold travel the row can drift on",
            0f,
            reorder.offsetFor(1),
            0.01f,
        )
    }

    /** Before the row has been measured there is no step size, so nothing can be decided yet. */
    @Test
    fun `an unmeasured row moves nothing`() {
        val reorder = state(startIndex = 2, count = 10, rowHeight = 0)

        reorder.applyDrag(500f)

        assertEquals(emptyList<Pair<Int, Int>>(), moves)
    }
}
