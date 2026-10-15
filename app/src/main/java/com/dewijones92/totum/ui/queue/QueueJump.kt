package com.dewijones92.totum.ui.queue

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal fun estimatedListIndex(entries: List<QueueEntry>, target: Int, collapsed: Set<String>): Int {
    var items = 0
    val seen = mutableSetOf<String>()
    for ((at, entry) in entries.withIndex()) {
        val group = entry.group?.id
        val startsRun = group != null && entries.getOrNull(at - 1)?.group?.id != group
        val firstRun = group != null && seen.add(group)
        if (startsRun && (group !in collapsed || firstRun)) items++
        if (at == target) return items
        if (group == null || group !in collapsed) items++
    }
    return items
}

internal fun toggledAll(groups: Set<String>, collapsed: Set<String>): Set<String> =
    if (groups.all { it in collapsed }) collapsed - groups else collapsed + groups

internal suspend fun LazyListState.jumpTo(estimate: Int, key: Any, why: String) {
    scrollToItem(estimate.coerceAtLeast(0))
    val exact = layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.index
    if (exact != null) scrollToItem(exact)
    Diag.log(
        "queue",
        "jumped to the playing item ($why): estimate $estimate, " +
            (exact?.let { "found at $it" } ?: "not on screen after the estimate"),
    )
}

@Composable
internal fun rememberJumpToPlaying(
    listState: LazyListState,
    snapshot: QueueSnapshot,
    collapsed: Set<String>,
    onCollapsedChange: (Set<String>) -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(Triple(snapshot.entries, snapshot.currentIndex, collapsed))
    fun go(why: String) {
        val (now, at, hidden) = latest
        val entry = now.getOrNull(at) ?: return
        val shown = entry.group?.id?.takeIf { it in hidden }?.let { hidden - it } ?: hidden
        if (shown != hidden) {
            onCollapsedChange(shown)
            Diag.log("queue", "expanded \"${entry.group?.title}\" to show the playing item")
        }
        scope.launch {
            withFrameNanos { }
            listState.jumpTo(estimatedListIndex(now, at, shown), entry.item.item.id.value, why)
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        go("the queue opened")
    }
    return remember { { go("the jump button") } }
}

@Composable
internal fun JumpToPlayingButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(JUMP_TO_PLAYING_TAG)) {
        Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.queue_jump_to_playing))
    }
}

@Composable
internal fun CollapseAllButton(allCollapsed: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(COLLAPSE_ALL_TAG)) {
        Icon(
            if (allCollapsed) Icons.Filled.UnfoldMore else Icons.Filled.UnfoldLess,
            contentDescription = stringResource(
                if (allCollapsed) R.string.queue_expand_all else R.string.queue_collapse_all
            ),
        )
    }
}

const val JUMP_TO_PLAYING_TAG = "queue-jump-to-playing"
const val COLLAPSE_ALL_TAG = "queue-collapse-all"
