package com.dewijones92.totum.ui.videos

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItem

internal data class RowsMoved(val fromRowShown: Int, val was: List<String>, val now: List<String>, val anchorRow: Int)

internal fun rowsMoved(before: List<String>, now: List<String>, visible: List<String>): RowsMoved? {
    if (before.isEmpty() || before == now) return null
    val anchor = visible.firstOrNull { it in before && it in now } ?: return null
    val was = before.drop(before.indexOf(anchor)).take(visible.size)
    val after = now.drop(now.indexOf(anchor)).take(visible.size)
    if (was == after) return null
    val first = was.zip(after).indexOfFirst { (a, b) -> a != b }.takeIf { it >= 0 } ?: minOf(was.size, after.size)
    return RowsMoved(first, was.drop(first), after.drop(first), now.indexOf(anchor))
}

private class Seen(var ids: List<String> = emptyList())

@Composable
internal fun ReportRowsMovingOnScreen(name: String, listState: LazyListState, shown: List<MediaItem>) {
    val seen = remember { Seen() }
    LaunchedEffect(shown) {
        val ids = shown.map { it.id.value }
        val before = seen.ids
        seen.ids = ids
        val visible = listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }
        val moved = rowsMoved(before, ids, visible) ?: return@LaunchedEffect
        Diag.log(
            "feed",
            "$name rows on screen moved: from row ${moved.fromRowShown + 1} on screen, " +
                "was ${moved.was.take(ROWS_IN_THE_LOG)} now ${moved.now.take(ROWS_IN_THE_LOG)} " +
                "(list ${before.size} -> ${ids.size}, first row on screen is " +
                "${moved.anchorRow} of the list, scroll=${listState.firstVisibleItemIndex})",
        )
    }
}

private const val ROWS_IN_THE_LOG = 3
