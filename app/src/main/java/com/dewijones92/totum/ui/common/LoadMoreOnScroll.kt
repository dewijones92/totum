package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.common.Diag

/**
 * Asks for the next page as the list nears its end — written once, for every paged list.
 *
 * Fires while there are still [PREFETCH_ITEMS] rows below the fold, so the next page is
 * usually there before the user reaches the bottom; waiting for the true end makes
 * scrolling stutter on every page boundary.
 *
 * [shownCount] is how many CONTENT rows the list displays — after any filtering, and not
 * counting headers, chips or the loading footer. It is what stops this running away.
 *
 * A list shorter than the viewport is trivially "near its end": its last item is always
 * visible, so the condition never goes false and every arriving page immediately asks for
 * another. Usually that self-limits, because the pages fill the screen. But with a filter
 * on (Unplayed, In progress) the arriving rows can all be hidden, so the list never grows
 * and the feed pages itself to exhaustion — measured on a real account: **80 requests and
 * 1220 videos fetched at launch, to display one row.** So a page that adds no visible
 * content stops the chain until something actually changes: the user scrolls new rows in,
 * or the filter opens up.
 */
@Composable
internal fun LoadMoreOnScrollToEnd(
    listState: LazyListState,
    enabled: Boolean,
    shownCount: Int,
    loadMore: () -> Unit,
    pausedByFilter: Boolean = false,
) {
    val shouldLoad by remember(listState) {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            total > 0 && lastVisible >= total - 1 - PREFETCH_ITEMS
        }
    }
    var askedAt by remember(listState) { mutableIntStateOf(NEVER_ASKED) }
    // A SHRINKING list is a different list, so the previous one's high-water mark must go with
    // it. Report 0.1.295 caught this exactly: switching from Subscriptions (75 items) to Home
    // (14) left `askedAt=75`, so 14 was judged "no new items since we last asked", the feed was
    // taken for exhausted, and paging stopped dead — permanently, until the screen was left and
    // re-entered. Home does page infinitely; it just never got the chance after a switch.
    if (shownCount < askedAt) askedAt = NEVER_ASKED
    LaunchedEffect(listState, enabled, shownCount, pausedByFilter) {
        if (pausedByFilter) return@LaunchedEffect
        snapshotFlow { shouldLoad }
            .collect { near ->
                if (!near) return@collect
                val fresh = shownCount > askedAt
                // The numbers behind the decision, not just that it fired: a run of these is
                // how you tell "the user scrolled fast" from "the list is paging itself to
                // exhaustion", and those are indistinguishable from the outcome alone.
                Diag.log(
                    "load-more",
                    "shown=$shownCount askedAt=$askedAt enabled=$enabled -> " +
                        when {
                            !enabled -> "no more to fetch"
                            !fresh -> "stopping, the last page added nothing visible"
                            else -> "fetching"
                        },
                )
                if (enabled && fresh) {
                    askedAt = shownCount
                    loadMore()
                }
            }
    }
}

/** Footer spinner for a list that is fetching its next page. */
@Composable
internal fun LoadingMoreFooter(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(FOOTER_SPINNER))
    }
}

private const val PREFETCH_ITEMS = 4

/** Below any real count, so the first ask always goes through. */
private const val NEVER_ASKED = -1
private val FOOTER_SPINNER = 28.dp
