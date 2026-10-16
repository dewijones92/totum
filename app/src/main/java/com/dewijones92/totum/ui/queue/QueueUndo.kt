package com.dewijones92.totum.ui.queue

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.queue.sortAfterCurrent
import com.dewijones92.totum.queue.undoSort
import com.dewijones92.totum.ui.common.MediaSort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Removal as the screen does it: drop the row BY IDENTITY, offer Undo on the snackbar, and put it back
 * at the same index if taken. Identity, not index, because the row a tap meant is the row that must go —
 * an index read at composition is stale the moment anything above it moves (see [PlaybackQueue.remove]).
 */
@Composable
internal fun rememberRemoveWithUndo(
    queue: PlaybackQueue,
    snackbar: SnackbarHostState,
    scope: CoroutineScope,
): (QueueEntry) -> Unit {
    val removedMessage = stringResource(R.string.queue_removed)
    val undoLabel = stringResource(R.string.queue_undo)
    return remember(queue, snackbar, scope) {
        {
                entry ->
            val index = queue.remove(entry)
            if (index != null) {
                scope.launch {
                    val result = snackbar.showSnackbar(
                        message = removedMessage,
                        actionLabel = undoLabel,
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) queue.restoreAt(index, entry)
                }
            }
        }
    }
}

@Composable
internal fun rememberSortWithUndo(
    queue: PlaybackQueue,
    snackbar: SnackbarHostState,
    scope: CoroutineScope,
): (MediaSort, String?) -> Unit {
    val resources = LocalResources.current
    return remember(queue, snackbar, scope, resources) {
        {
                order, groupId ->
            if (order != MediaSort.AS_LISTED) {
                val what = groupId ?: "up next, within each group"
                val before = queue.sortAfterCurrent("sort $what by $order", order.ofEntries()) {
                    groupId == null || it?.id == groupId
                }
                scope.launch {
                    val shown = snackbar.showSnackbar(
                        resources.getString(R.string.queue_sorted, resources.getString(order.labelRes)),
                        actionLabel = resources.getString(R.string.queue_sort_undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (shown == SnackbarResult.ActionPerformed) {
                        queue.undoSort(before)
                    } else {
                        Diag.log("queue", "sort by $order kept: its undo was not taken")
                    }
                }
            }
        }
    }
}

private fun MediaSort.ofEntries(): (List<QueueEntry>) -> List<QueueEntry> =
    { list -> sortedBy(list, added = { it.addedAt?.toEpochMilli() }) { it.item.item } }
