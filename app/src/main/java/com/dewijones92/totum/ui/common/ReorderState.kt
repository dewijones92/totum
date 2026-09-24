package com.dewijones92.totum.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Long-press-and-drag reordering for a list, including dragging past what is on screen.
 *
 * Hand-rolled rather than pulling in a dependency, and deliberately simple: instead of mapping
 * pointer positions onto item bounds, it accumulates the drag and swaps one position each time
 * the accumulated distance passes the height of **the row being crossed**. That reads identically
 * to the user, survives lists whose lazy indices don't line up with the data (the queue interleaves
 * group headers), and has no measurement edge cases.
 *
 * **Per row, not one height for the list**, since 2026-09-21. Rows were near-uniform while titles
 * were capped at two lines; now nothing in the app truncates, so a queue holds rows from ~100dp to
 * ~250dp at once. One shared height means the step is whichever row happened to measure last — so
 * dragging a short row past a tall one needed twice the travel, and the reverse produced two swaps
 * for one row of movement. Same quantity, same class of bug as the handle-height defect recorded on
 * [reorderable] below, which is why the heights are swapped along with the rows.
 *
 * **It auto-scrolls at the edges**, which is the difference between a toy and something usable.
 * Without it a drag could only move an item as far as the viewport, and Dewi's queue is 74 items
 * long — so "move this to the end" was impossible by dragging, however long you were willing to
 * spend. Hold a row near the top or bottom and the list now scrolls under it for as long as you
 * hold it there.
 *
 * The mechanism is the neat part: scrolling the list by N pixels moves the content under a
 * stationary finger by exactly N pixels, so auto-scroll feeds those pixels into the SAME
 * accumulator a real drag uses. Swapping therefore continues while scrolling, through one code
 * path, rather than needing a second rule for "moved because the list moved underneath".
 *
 * [onMove] is called as the drag crosses each boundary, so the list reorders live and the
 * underlying store stays the single source of truth — nothing to commit on release.
 */
class ReorderState internal constructor(
    private val onMove: (from: Int, to: Int) -> Unit,
    private val listState: LazyListState,
    private val scope: CoroutineScope,
) {
    internal var draggingIndex by mutableIntStateOf(NONE)

    private var gripHolds by mutableIntStateOf(0)

    val gripHeld: Boolean get() = gripHolds > 0
    private var accumulated by mutableFloatStateOf(0f)

    /**
     * Fallback height, used only for an index nothing has measured yet — and set directly by the
     * unit tests that model a uniform list.
     */
    internal var rowHeight by mutableStateOf(0)

    /** Measured height per index, so the step can be the height of the row actually being crossed. */
    private val rowHeights = mutableMapOf<Int, Int>()

    /**
     * What the drag just did, summarised on release — travel in, places out, and the step sizes it
     * was measured against.
     *
     * One line per GESTURE, never per event: a drag emits pointer deltas by the hundred, and this
     * file's own bounded report buffer is the reason nothing here logs per move.
     *
     * It exists because the wrong-step defect was completely unloggable. "The dragger moves too
     * far" and "the dragger moves too little" produced identical silence, and the quantity that
     * decides both — the step — appeared nowhere. Two separate defects in this one class have now
     * been about that number (a handle's height, then one shared height for unequal rows), and both
     * had to be found by reading the code. A report from a phone can settle the third: travel 720px
     * against steps 358..411 and 2 places is right; the same travel against step=24 is the handle
     * bug; against one step for a list of visibly different rows it is this one.
     */
    private var startedAt = NONE
    private var travelled = 0f
    private val stepsUsed = mutableSetOf<Int>()
    internal var itemCount = 0
        set(value) {
            field = value
            // A shortened list leaves measurements behind for indices that no longer exist, and a
            // stale one at the END is not harmless: the step is read before the bounds check, so
            // the accumulator is allowed to grow by a height that is no longer there and the row
            // drifts past the end before snapping back. It also made the drag log's "of N measured
            // row(s)" a count of rows that had gone.
            if (rowHeights.isNotEmpty()) rowHeights.keys.retainAll { it in 0 until value }
        }

    /** The list's own top and bottom in window coordinates, so "near the edge" is answerable. */
    private var listTop = 0f
    private var listBottom = 0f

    /** Runs for as long as the finger stays in an edge zone. */
    private var autoScroll: Job? = null

    /** True while [index] is the row being dragged. */
    fun isDragging(index: Int): Boolean = draggingIndex == index

    /** How far to visually shift the dragged row. */
    fun offsetFor(index: Int): Float = if (isDragging(index)) accumulated else 0f

    /**
     * Attach to the scrolling container, so the edge zones are known.
     *
     * Without it the state cannot tell where the list ends, and it fails SAFE rather than
     * silently wrong: [listBottom] stays zero, no zone is ever entered, and dragging behaves
     * exactly as it did before auto-scroll existed.
     */
    fun Modifier.reorderContainer(): Modifier = onGloballyPositioned { coordinates ->
        listTop = coordinates.positionInWindow().y
        listBottom = listTop + coordinates.size.height
    }

    /**
     * Attach to a row's grip to make it draggable. [index] is the row's index **in the data**,
     * which is what [onMove] receives.
     */
    @Composable
    fun Modifier.dragHandle(index: Int, itemCount: Int): Modifier {
        this@ReorderState.itemCount = itemCount
        // The index as of the LATEST composition, read only when a drag begins.
        //
        // The gesture must NOT be keyed on it. Every swap moves this row, so its index changes, so
        // a `pointerInput(index, …)` is torn down and rebuilt — cancelling the drag in flight. The
        // result is exactly one swap per touch, which is what Dewi reported on 0.1.359: *"i am only
        // able to drag the items in the queue by 1 position"*. `itemCount` was a key for the same
        // reason and is just as wrong: a download finishing while you drag would drop the item.
        //
        // Keyed on Unit instead, so the gesture outlives every recomposition of the row it started
        // on, and the current index is read through a holder rather than captured.
        val latestIndex = rememberUpdatedState(index)
        // Remembered, not a local: the pointer-input lambda is created once now, so a plain `var`
        // would leave it reading the first composition's copy while `onGloballyPositioned` wrote to
        // the newest one, and auto-scroll would aim at where the grip used to be.
        val handleTop = remember { mutableFloatStateOf(0f) }
        return this
            .onGloballyPositioned { handleTop.floatValue = it.positionInWindow().y }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    gripHolds++
                    Diag.log("reorder", "dewidebug grip down, holds=$gripHolds")
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                    } finally {
                        gripHolds--
                        Diag.log("reorder", "dewidebug grip up, holds=$gripHolds")
                    }
                }
            }
            .pointerInput(Unit) {
                // Drag starts on TOUCH, not after a long press.
                //
                // Two reasons, and the first is a bug Dewi hit: the row beneath carries
                // `combinedClickable(onLongClick = …)` for its context menu, so a long press on
                // the grip fired BOTH — the sheet opened over the drag that had just begun.
                // A grip is unambiguous by construction; making it wait 500ms to decide what an
                // unambiguous control meant was only ever a cost.
                detectDragGestures(
                    onDragStart = {
                        draggingIndex = latestIndex.value
                        accumulated = 0f
                        startedAt = draggingIndex
                        travelled = 0f
                        stepsUsed.clear()
                    },
                    onDragEnd = {
                        describeDrag("released")
                        reset()
                    },
                    onDragCancel = {
                        describeDrag("cancelled")
                        reset()
                    },
                    onDrag = { change, delta ->
                        // Consumed so the LazyColumn does not scroll the list out from under a
                        // drag that is already moving it.
                        change.consume()
                        travelled += delta.y
                        applyDrag(delta.y)
                        // The finger in window space: where the grip is, plus where the touch
                        // sits within it. Edge detection only needs to be right to within a row.
                        updateAutoScroll(handleTop.floatValue + change.position.y)
                    },
                )
            }
    }

    /** One line per drag: what went in, what came out, and the quantity that decided it. */
    private fun describeDrag(ending: String) {
        val from = startedAt
        if (from == NONE) return
        val places = draggingIndex - from
        Diag.log(
            "queue",
            "drag $ending: $from -> $draggingIndex ($places place(s)) after ${travelled.toInt()}px, " +
                "steps=${stepsUsed.sorted()}px of ${rowHeights.size} measured row(s)",
        )
    }

    /**
     * Moves the dragged row by [dy] pixels of travel, swapping as it crosses each boundary.
     *
     * Shared by the finger and the auto-scroll because they are the same event: content moving
     * relative to the row. A separate path for scrolling would be a second definition of "when
     * does this become a swap", and two definitions of one rule drift.
     */
    internal fun applyDrag(dy: Float) {
        accumulated += dy
        while (true) {
            val direction = if (accumulated > 0) 1 else -1
            val from = draggingIndex
            val to = from + direction
            // The row being CROSSED sets the distance, because that is how far the finger has to
            // travel for the two to change places. Recomputed each iteration: a single step read
            // once before the loop is only right while every row is the same height.
            val step = (rowHeights[to] ?: rowHeights[from] ?: rowHeight).takeIf { it > 0 } ?: return
            if (abs(accumulated) < step) return
            if (to !in 0 until itemCount) {
                // At an end: stop accumulating so the row doesn't drift away.
                accumulated = 0f
                return
            }
            onMove(from, to)
            stepsUsed += step
            // The heights move with the rows, or the next step would be measured against whatever
            // used to be there. Composition re-measures and corrects this anyway; not relying on
            // that is what keeps a fast multi-row drag stepping by the right distances throughout.
            val crossed = rowHeights[to]
            rowHeights[from]?.let { rowHeights[to] = it } ?: rowHeights.remove(to)
            crossed?.let { rowHeights[from] = it } ?: rowHeights.remove(from)
            draggingIndex = to
            accumulated -= direction * step
        }
    }

    /** What [reorderable] reports for each row it measures. */
    internal fun setRowHeight(index: Int, height: Int) {
        rowHeights[index] = height
        // Kept in step so an unmeasured index still has something sane to fall back to.
        rowHeight = height
    }

    /**
     * Starts, stops, or leaves running the scroll that happens while a row is held at an edge.
     *
     * One job for as long as the finger stays in a zone, rather than a scroll per drag event: a
     * finger held perfectly still emits NO pointer events, and that is precisely the gesture
     * this exists to serve — put the row at the bottom of the screen and wait.
     */
    private fun updateAutoScroll(fingerY: Float) {
        val direction = when {
            listBottom <= listTop -> 0 // container never measured; behave as before
            fingerY < listTop + EDGE_ZONE_PX -> -1
            fingerY > listBottom - EDGE_ZONE_PX -> 1
            else -> 0
        }
        if (direction == 0) {
            if (autoScroll != null) Diag.log("queue", "drag left the edge; auto-scroll stopped")
            stopAutoScroll()
            return
        }
        if (autoScroll?.isActive == true) return
        // Logged because a drag that will not travel is otherwise unanswerable from a report:
        // "it did not scroll" could equally mean the zone was never entered, the container was
        // never measured, or the list was already at its end.
        Diag.log(
            "queue",
            "drag held at the ${if (direction < 0) "top" else "bottom"} edge; scrolling " +
                "(finger=${fingerY.toInt()} list=${listTop.toInt()}..${listBottom.toInt()})",
        )
        autoScroll = scope.launch {
            while (isActive && draggingIndex != NONE) {
                val moved = listState.scrollBy(direction * SCROLL_STEP_PX)
                // The list can run out: at the very top or bottom nothing moves, and feeding
                // zero into the accumulator forever would just spin.
                if (moved == 0f) {
                    Diag.log("queue", "auto-scroll stopped: the list is already at its end")
                    break
                }
                // Counted as travel like a finger's pixels are, because to the list they ARE the
                // same event. Without this a drag completed by holding at the edge logs a small
                // distance against genuine steps — which reads exactly like the wrong-step defect
                // the line exists to tell apart.
                travelled += moved
                applyDrag(moved)
            }
        }
    }

    private fun stopAutoScroll() {
        autoScroll?.cancel()
        autoScroll = null
    }

    private fun reset() {
        stopAutoScroll()
        draggingIndex = NONE
        accumulated = 0f
    }

    private companion object {
        const val NONE = -1

        /**
         * How close to an edge counts as "held there".
         *
         * About a finger's width, so it can be reached deliberately without being entered by
         * accident while dragging between two rows that happen to be near the end of the list.
         */
        const val EDGE_ZONE_PX = 140f

        /** Per tick — roughly a frame's travel, so scrolling reads as continuous, not steppy. */
        const val SCROLL_STEP_PX = 12f
    }
}

/** Remembers a [ReorderState] that reports moves to [onMove] and can scroll [listState]. */
@Composable
fun rememberReorderState(
    listState: LazyListState,
    onMove: (from: Int, to: Int) -> Unit,
): ReorderState {
    val scope = rememberCoroutineScope()
    return remember(onMove, listState, scope) { ReorderState(onMove, listState, scope) }
}

/**
 * Lifts the dragged row above its neighbours, follows the finger, and measures the ROW.
 *
 * The measurement belongs here and nowhere else. It used to live on the drag handle, which in
 * the real queue is a 24dp icon inside a ~95dp row — so the step a swap is measured against was
 * the handle's height rather than the row's, and items reordered roughly four times faster than
 * the finger moved. The synthetic test missed it entirely because it made the handle the whole
 * row; the real screen does not.
 *
 * Reported **per index** since rows stopped being uniform — the same measurement, no longer
 * flattened into one number for the whole list.
 */
fun Modifier.reorderable(state: ReorderState, index: Int): Modifier =
    this
        .onSizeChanged { if (it.height > 0) state.setRowHeight(index, it.height) }
        .graphicsLayer {
            translationY = state.offsetFor(index)
            // A little lift so it's obvious which row you picked up.
            shadowElevation = if (state.isDragging(index)) DRAG_ELEVATION else 0f
            scaleX = if (state.isDragging(index)) DRAG_SCALE else 1f
            scaleY = scaleX
        }

private const val DRAG_ELEVATION = 12f
private const val DRAG_SCALE = 1.02f
