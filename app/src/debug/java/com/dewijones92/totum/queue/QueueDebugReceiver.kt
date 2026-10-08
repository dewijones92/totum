package com.dewijones92.totum.queue

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.placeholderFor
import kotlinx.coroutines.launch

class QueueDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as TotumApplication).container
        val ids = intent.getStringExtra("ids").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
        Diag.log("queue", "dewidebug DEBUG broadcast action=${intent.action} ids=$ids extras=${intent.extras?.keySet()}")
        when (intent.action) {
            ACTION_PLAY_ALL -> {
                val items = ids.mapNotNull { id ->
                    val url = HttpUrl.of("https://www.youtube.com/watch?v=$id")
                    placeholderFor(url, SourceId("debug"))?.let { PlayableItem(it, PlayHandle.Video(url)) }
                }
                container.applicationScope.launch {
                    container.playbackQueue.clear()
                    container.playbackQueue.playAll(items)
                }
            }
            ACTION_SEEK_NEAR_END -> container.applicationScope.launch {
                val state = container.playbackController.state.value ?: return@launch
                val duration = state.durationMs ?: return@launch
                val target = (duration - intent.getLongExtra("beforeEndMs", DEFAULT_BEFORE_END_MS)).coerceAtLeast(0)
                Diag.log("queue", "dewidebug DEBUG seek ${state.itemId.value} to ${target}ms of ${duration}ms")
                container.playbackController.seekTo(target)
            }
            ACTION_SET_GAPLESS -> container.appPreferences.setGaplessQueue(intent.getBooleanExtra("on", true))
        }
    }

    private companion object {
        const val ACTION_PLAY_ALL = "com.dewijones92.totum.DEBUG_QUEUE_PLAY_ALL"
        const val ACTION_SEEK_NEAR_END = "com.dewijones92.totum.DEBUG_SEEK_NEAR_END"
        const val ACTION_SET_GAPLESS = "com.dewijones92.totum.DEBUG_SET_GAPLESS"
        const val DEFAULT_BEFORE_END_MS = 35_000L
    }
}
