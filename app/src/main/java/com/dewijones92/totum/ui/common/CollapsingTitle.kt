package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp as lerpFloat

/**
 * A large title that shrinks into a compact bar as the list scrolls under it.
 *
 * The feeds previously had either no header or a plain one, which left every screen looking the
 * same and gave the app no sense of place. A large title collapsing on scroll is the Material 3
 * expressive answer to exactly that: identity while you are at the top, out of the way once you
 * are reading.
 *
 * Driven by [LazyListState] rather than a nested-scroll connection because the feeds are all
 * LazyColumns whose state is already hoisted — a scroll connection would be a second source of
 * truth for the same number, and the two would drift.
 *
 * Only the first item's offset is consulted, deliberately. Summing scroll across items needs
 * every item's height, which a lazy list does not know until it has measured them, so a
 * cumulative version lurches as rows of differing heights come into view.
 */
@Composable
fun CollapsingTitle(
    title: String,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    // Moves only while the list is scrolled, or to expand at the very top: the title's height sets
    // the list's viewport, so following every position change fed back into itself, and a queue
    // that nearly fitted recomposed every frame for ever (ADR 61).
    var fraction by remember(listState) { mutableFloatStateOf(0f) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to collapseOf(listState) }
            .collect { (scrolling, target) -> if (scrolling || target == 0f) fraction = target }
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .height(lerp(ExpandedHeight, CollapsedHeight, fraction))
                .padding(start = 20.dp, end = 8.dp, bottom = 10.dp),
        ) {
            Text(
                text = title,
                // Size changes, weight does not: animating both reads as two effects fighting
                // rather than one title shrinking.
                style = MaterialTheme.typography.headlineLarge,
                fontSize = lerpFloat(EXPANDED_SP, COLLAPSED_SP, fraction).sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Box(contentAlignment = Alignment.Center) { trailing() }
            SearchAction()
        }
    }
}

private fun collapseOf(listState: LazyListState): Float = when {
    listState.firstVisibleItemIndex > 0 -> 1f
    else -> (listState.firstVisibleItemScrollOffset / COLLAPSE_DISTANCE_PX).coerceAtMost(1f)
}

/** Pixels of scroll before the title is fully collapsed. */
private const val COLLAPSE_DISTANCE_PX = 160f
private const val EXPANDED_SP = 32f
private const val COLLAPSED_SP = 20f
private val ExpandedHeight = 104.dp
private val CollapsedHeight = 56.dp
