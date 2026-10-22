package com.dewijones92.totum.ui.queue

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private const val SETTLE_MS = 800L
private const val TOLERANCE_PX = 1
private const val LISTED = 3

@Composable
internal fun WatchForOverlappingRows(listState: LazyListState) {
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.firstVisibleItemIndex }
            .collectLatest { (scrolling, first) ->
                if (scrolling) return@collectLatest
                delay(SETTLE_MS)
                val rows = listState.layoutInfo.visibleItemsInfo
                val overlaps = rows.zipWithNext().filter { (a, b) -> a.offset + a.size > b.offset + TOLERANCE_PX }
                if (overlaps.isNotEmpty()) {
                    Diag.warn(
                        "queue",
                        "dewidebug ${overlaps.size} queue row(s) overlap in the layout at first=$first: " +
                            overlaps.take(LISTED).joinToString { (a, b) ->
                                "${a.key}@${a.offset}+${a.size} over ${b.key}@${b.offset}"
                            },
                    )
                }
            }
    }
}
