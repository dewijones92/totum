package com.dewijones92.totum.queue

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.NextStack
import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal fun keepTheQueuesPlace(queue: PlaybackQueue, context: Context, scope: CoroutineScope) {
    val prefs = context.getSharedPreferences("totum_queue_place", Context.MODE_PRIVATE)
    scope.launch {
        queue.hydrated.first { it }
        prefs.getString(KEY, null)?.let { saved ->
            Diag.log("queue", "restoring the queue's place from before the restart: after $saved")
            queue.restorePlace(MediaItemId(saved))
        }
        savedNextStack(prefs)?.let { stack ->
            Diag.log(
                "queue",
                "restoring the play-next chain from before the restart: tail ${stack.tail.value} " +
                    "behind ${stack.behind?.value ?: "nothing"}",
            )
            queue.restoreNextStack(stack)
        }
        launch {
            queue.state.map { it.anchor }.distinctUntilChanged().collect { anchor ->
                prefs.edit { if (anchor == null) remove(KEY) else putString(KEY, anchor.value) }
            }
        }
        queue.state.map { it.nextStack }.distinctUntilChanged().collect { stack ->
            prefs.edit {
                if (stack == null) {
                    remove(STACK_TAIL)
                    remove(STACK_BEHIND)
                } else {
                    putString(STACK_TAIL, stack.tail.value)
                    val behind = stack.behind
                    if (behind == null) remove(STACK_BEHIND) else putString(STACK_BEHIND, behind.value)
                }
            }
            Diag.log("queue", "dewidebug play-next chain saved: tail ${stack?.tail?.value ?: "none"}")
        }
    }
}

private fun savedNextStack(prefs: SharedPreferences): NextStack? =
    prefs.getString(STACK_TAIL, null)?.let { tail ->
        NextStack(MediaItemId(tail), prefs.getString(STACK_BEHIND, null)?.let(::MediaItemId))
    }

private const val KEY = "anchor"
private const val STACK_TAIL = "stack_tail"
private const val STACK_BEHIND = "stack_behind"
