package com.dewijones92.totum.ui.queue

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.common.LocalSetPlayed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun PlaybackQueue.idsIn(groupId: String): List<String> =
    state.value.entries.filter { it.group?.id == groupId }.map { it.item.item.id.value }

@Composable
internal fun rememberStartGroupAgain(
    queue: PlaybackQueue,
    snackbar: SnackbarHostState,
    scope: CoroutineScope,
): (String) -> Unit {
    val setPlayed = LocalSetPlayed.current
    val context = LocalContext.current
    return { groupId -> startAgain(queue, groupId, setPlayed, context, snackbar, scope) }
}

@Suppress("LongParameterList")
private fun startAgain(
    queue: PlaybackQueue,
    groupId: String,
    setPlayed: ((MediaItemId, Boolean) -> Unit)?,
    context: Context,
    snackbar: SnackbarHostState,
    scope: CoroutineScope,
) {
    val ids = queue.idsIn(groupId)
    if (setPlayed == null) {
        Diag.warn("queue", "start again for group $groupId: nothing here can mark items unplayed")
        return
    }
    ids.forEach { setPlayed(MediaItemId(it), false) }
    Diag.log("queue", "start again: ${ids.size} item(s) of group $groupId marked unplayed, positions cleared")
    scope.launch {
        snackbar.showSnackbar(
            context.resources.getQuantityString(R.plurals.queue_group_started_again, ids.size, ids.size)
        )
    }
}
