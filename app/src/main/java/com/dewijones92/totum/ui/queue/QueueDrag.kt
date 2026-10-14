package com.dewijones92.totum.ui.queue

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.queue.QueueDrag
import com.dewijones92.totum.queue.dragMove
import com.dewijones92.totum.queue.moveGroupPast
import com.dewijones92.totum.ui.common.GripHold
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

private const val SHOWN_WAIT_MS = 200L

internal class QueueDragSession(val grip: GripHold) {
    var draggingGroup by mutableStateOf(false)
        private set
    var dragging by mutableStateOf(false)
        private set
    private var pickedUp: String? = null
    private var moves = 0

    fun pickedUp(key: String, group: Boolean) {
        pickedUp = key
        moves = 0
        draggingGroup = group
        dragging = true
        Diag.log("queue", "drag picked up ${if (group) "group" else "item"} $key")
    }

    fun moved(fromKey: String, toKey: String, drag: QueueDrag) {
        moves++
        if (drag is QueueDrag.Ignored) Diag.log("queue", "drag $fromKey over $toKey ignored: ${drag.why}")
    }

    fun released(key: String) {
        Diag.log(
            "queue",
            "drag released $key after $moves move(s)${if (key != pickedUp) " (picked up $pickedUp)" else ""}"
        )
        pickedUp = null
        draggingGroup = false
        dragging = false
    }
}

internal class QueueRowDrag(
    val scope: ReorderableCollectionItemScope,
    val isDragging: Boolean,
    val key: String,
    val session: QueueDragSession,
)

@Composable
internal fun rememberQueueReorder(
    listState: LazyListState,
    queue: PlaybackQueue,
    session: QueueDragSession,
    shown: () -> QueueSnapshot,
): ReorderableLazyListState {
    val haptics = LocalHapticFeedback.current
    return rememberReorderableLazyListState(listState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        val drag = queue.state.value.dragMove(fromKey, toKey)
        session.moved(fromKey, toKey, drag)
        when (drag) {
            is QueueDrag.Item -> queue.move(drag.from, drag.to)
            is QueueDrag.Group -> queue.moveGroupPast(drag.groupId, drag.target)
            is QueueDrag.Ignored -> return@rememberReorderableLazyListState
        }
        val target = queue.state.value
        val caughtUp = withTimeoutOrNull(SHOWN_WAIT_MS) { snapshotFlow(shown).first { it == target } } != null
        if (!caughtUp) {
            Diag.log(
                "queue",
                "drag $fromKey over $toKey: the list had not shown the move after ${SHOWN_WAIT_MS}ms"
            )
        }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
}

@Composable
internal fun rememberQueueDragSession(): QueueDragSession = remember { QueueDragSession(GripHold()) }

@Composable
internal fun QueueDragHandle(
    drag: QueueRowDrag,
    description: String,
    modifier: Modifier = Modifier,
    group: Boolean = false,
    onPickedUp: () -> Unit = {},
    onDropped: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    Icon(
        Icons.Filled.DragHandle,
        contentDescription = description,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = with(drag.scope) {
            with(drag.session.grip) { modifier.holdsGrip() }
                .draggableHandle(
                    onDragStarted = {
                        drag.session.pickedUp(drag.key, group)
                        onPickedUp()
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    },
                    onDragStopped = {
                        drag.session.released(drag.key)
                        onDropped()
                        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                    },
                )
                .padding(horizontal = 8.dp)
        },
    )
}
