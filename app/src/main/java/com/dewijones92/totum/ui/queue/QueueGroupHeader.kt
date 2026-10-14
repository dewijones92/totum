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
import com.dewijones92.totum.queue.GroupDestination

internal data class GroupHeaderModel(
    val groupId: String,
    val isFirstRun: Boolean,
    val title: String,
    val count: Int,
    val collapsed: Boolean,
    val containsNowPlaying: Boolean,
    val draggable: Boolean,
)

internal data class GroupHeaderActions(
    val onToggle: () -> Unit,
    val onRemove: () -> Unit,
    val onMove: (GroupDestination) -> Unit,
    val onShuffle: () -> Unit,
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
            .background(if (drag?.isDragging == true) MaterialTheme.colorScheme.surface else Color.Transparent)
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
private fun GroupTitle(model: GroupHeaderModel, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(
            text = model.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (model.collapsed) {
            Text(
                text = if (model.containsNowPlaying) {
                    pluralStringResource(R.plurals.queue_group_hidden_playing, model.count, model.count)
                } else {
                    pluralStringResource(R.plurals.queue_group_hidden, model.count, model.count)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GroupMenu(model: GroupHeaderModel, actions: GroupHeaderActions) {
    var open by remember { mutableStateOf(false) }
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
            MenuItem(R.string.queue_remove_group, close, actions.onRemove)
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

internal fun queueRowGripTag(itemId: String): String = "queue-grip-$itemId"

internal fun queueGroupHandleTag(groupId: String): String = "queue-group-handle-$groupId"

private const val DRAG_ELEVATION = 12f
