package com.dewijones92.totum.queue

import android.content.Context
import androidx.core.content.edit
import com.dewijones92.totum.common.Diag
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
        queue.state.map { it.anchor }.distinctUntilChanged().collect { anchor ->
            prefs.edit { if (anchor == null) remove(KEY) else putString(KEY, anchor.value) }
        }
    }
}

private const val KEY = "anchor"
