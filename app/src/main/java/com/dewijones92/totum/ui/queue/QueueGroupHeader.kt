package com.dewijones92.totum.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.music.RADIO_GROUP_PREFIX
import com.dewijones92.totum.queue.GroupDestination
import com.dewijones92.totum.ui.common.MediaSort
import com.dewijones92.totum.ui.common.SortAndFilterSheet
import com.dewijones92.totum.ui.common.destructiveTexts
import com.dewijones92.totum.ui.common.rememberHoldToConfirmAsk

internal data class GroupHeaderModel(
    val groupId: String,
    val isFirstRun: Boolean,
    val title: String,
    val count: Int,
    val collapsed: Boolean,
    val containsNowPlaying: Boolean,
    val draggable: Boolean,
    val playing: Boolean = false,
    val downloading: Boolean = true,
) {
    val endless: Boolean get() = groupId.startsWith(RADIO_GROUP_PREFIX)
}

internal data class GroupHeaderActions(
    val onToggle: () -> Unit,
    val onRemove: () -> Unit,
    val onMove: (GroupDestination) -> Unit,
    val onShuffle: () -> Unit,
    val onShuffleAndPlay: () -> Unit,
    val onSetDownload: (Boolean) -> Unit = {},
    val onPullNew: (() -> Unit)? = null,
    val onSort: ((MediaSort) -> Unit)? = null,
    val onSelect: (() -> Unit)? = null,
    val onStartAgain: (() -> Unit)? = null,
)

@Composable
internal fun GroupHeader(model: GroupHeaderModel, actions: GroupHeaderActions, drag: QueueRowDrag?) {
    var foldedByDrag by remember { mutableStateOf(false) }
    val expandOrCollapse = stringResource(
        if (model.collapsed) R.string.queue_group_expand_action else R.string.queue_group_collapse_action,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .graphicsLayer { shadowElevation = if (drag?.isDragging == true) DRAG_ELEVATION else 0f }
            .background(
                when {
                    drag?.isDragging == true -> MaterialTheme.colorScheme.surface
                    model.containsNowPlaying -> MaterialTheme.colorScheme.primaryContainer
                    else -> Color.Transparent
                },
            )
            .fillMaxWidth()
            .clickable(onClickLabel = expandOrCollapse, onClick = actions.onToggle)
            .then(if (model.isFirstRun) Modifier.testTag(queueGroupHeaderTag(model.groupId)) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
    ) {
        Icon(
            imageVector = if (model.collapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
            contentDescription = stringResource(
                if (model.collapsed) R.string.queue_group_expand else R.string.queue_group_collapse,
                model.title,
            ),
            tint = MaterialTheme.colorScheme.primary,
        )
        GroupTitle(model, Modifier.weight(1f).padding(start = 4.dp))
        if (model.count > 1) GroupShufflePlay(model, actions.onShuffleAndPlay)
        GroupMenu(model, actions)
        if (model.draggable && drag != null) {
            QueueDragHandle(
                drag = drag,
                description = stringResource(R.string.queue_group_reorder, model.title),
                modifier = Modifier.testTag(queueGroupHandleTag(model.groupId)),
                group = true,
                onPickedUp = {
                    foldedByDrag = !model.collapsed
                    if (foldedByDrag) actions.onToggle()
                },
                onDropped = {
                    if (foldedByDrag) actions.onToggle()
                    foldedByDrag = false
                },
            )
        }
    }
}

@Composable
internal fun UngroupedHeader(count: Int, containsNowPlaying: Boolean, playing: Boolean) {
    val model = GroupHeaderModel(
        groupId = UNGROUPED_ID,
        isFirstRun = true,
        title = pluralStringResource(R.plurals.queue_added_by_you, count, count),
        count = count,
        collapsed = false,
        containsNowPlaying = containsNowPlaying,
        draggable = false,
        playing = playing,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(if (containsNowPlaying) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .fillMaxWidth()
            .testTag(QUEUE_UNGROUPED_HEADER_TAG)
            .padding(start = 44.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    ) {
        GroupTitle(model, Modifier.weight(1f))
    }
}

@Composable
private fun GroupShufflePlay(model: GroupHeaderModel, onClick: () -> Unit) {
    val tag = if (model.isFirstRun) Modifier.testTag(queueGroupShufflePlayTag(model.groupId)) else Modifier
    IconButton(onClick = onClick, modifier = tag) {
        Icon(
            Icons.Filled.Shuffle,
            contentDescription = stringResource(R.string.queue_group_shuffle_play, model.title),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun GroupTitle(model: GroupHeaderModel, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(
            text = if (model.containsNowPlaying) {
                stringResource(
                    R.string.queue_group_title_playing,
                    model.title
                )
            } else {
                model.title
            },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (model.containsNowPlaying) {
            Text(
                text = stringResource(
                    if (model.playing) R.string.queue_group_playing_now else R.string.queue_group_paused_here
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.testTag(QUEUE_GROUP_PLAYING_TAG),
            )
        }
        if (model.endless) {
            Text(
                text = stringResource(R.string.queue_group_endless),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(QUEUE_GROUP_ENDLESS_TAG),
            )
        }
        if (model.collapsed) {
            Text(
                text = pluralStringResource(R.plurals.queue_group_hidden, model.count, model.count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GroupMenu(model: GroupHeaderModel, actions: GroupHeaderActions) {
    var open by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    val confirm = rememberHoldToConfirmAsk()
    val removeTexts = destructiveTexts(R.plurals.queue_group_remove_confirm, model.count, R.string.queue_remove_group)
    if (sorting) {
        actions.onSort?.let { sort ->
            SortAndFilterSheet(
                MediaSort.offered(addedKnown = true),
                MediaSort.AS_LISTED,
                { it.labelRes },
                sort,
                null,
                null
            ) {
                sorting = false
            }
        }
    }
    Box {
        IconButton(
            onClick = { open = true },
            modifier = if (model.isFirstRun) Modifier.testTag(queueGroupMenuTag(model.groupId)) else Modifier,
        ) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.queue_group_menu, model.title))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val close = { open = false }
            MenuItem(R.string.queue_play_next, close) { actions.onMove(GroupDestination.NEXT) }
            MenuItem(R.string.queue_move_to_top, close) { actions.onMove(GroupDestination.TOP) }
            MenuItem(R.string.queue_move_to_bottom, close) { actions.onMove(GroupDestination.BOTTOM) }
            if (model.count > 1) MenuItem(R.string.queue_group_shuffle, close, actions.onShuffle)
            if (model.count > 1 && actions.onSort != null) MenuItem(R.string.queue_group_sort, close) { sorting = true }
            actions.onPullNew?.let { MenuItem(R.string.queue_group_pull_new, close, it) }
            if (model.downloading) {
                MenuItem(R.string.queue_group_stop_download, close) { actions.onSetDownload(false) }
            } else {
                MenuItem(R.string.queue_group_download, close) { actions.onSetDownload(true) }
            }
            actions.onSelect?.let { MenuItem(R.string.queue_group_select, close, it) }
            actions.onStartAgain?.let { MenuItem(R.string.queue_group_start_again, close, it) }
            MenuItem(R.string.queue_remove_group, close) { confirm(removeTexts, actions.onRemove) }
        }
    }
}

@Composable
private fun MenuItem(label: Int, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
        close()
        action()
    })
}

@Composable
internal fun QueueShuffleButton(onShuffleUpNext: () -> Unit, onShuffleGroups: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { if (onShuffleGroups == null) onShuffleUpNext() else open = true }) {
            Icon(Icons.Filled.Shuffle, contentDescription = stringResource(R.string.queue_shuffle_up_next))
        }
        onShuffleGroups?.let { shuffleGroups ->
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                val close = { open = false }
                MenuItem(R.string.queue_shuffle_up_next, close, onShuffleUpNext)
                MenuItem(R.string.queue_shuffle_groups, close, shuffleGroups)
            }
        }
    }
}

internal fun queueGroupHeaderTag(groupId: String): String = "queue-group-header-$groupId"

internal fun queueGroupMenuTag(groupId: String): String = "queue-group-menu-$groupId"

internal fun queueGroupShufflePlayTag(groupId: String): String = "queue-group-shuffle-play-$groupId"

internal fun queueRowGripTag(itemId: String): String = "queue-grip-$itemId"

internal fun queueGroupHandleTag(groupId: String): String = "queue-group-handle-$groupId"

private const val DRAG_ELEVATION = 12f

internal const val QUEUE_GROUP_ENDLESS_TAG = "queue-group-endless"

internal const val QUEUE_GROUP_PLAYING_TAG = "queue-group-playing"
internal const val QUEUE_UNGROUPED_HEADER_TAG = "queue-ungrouped-header"
private const val UNGROUPED_ID = "added-by-you"
