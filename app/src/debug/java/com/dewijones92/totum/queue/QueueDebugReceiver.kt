package com.dewijones92.totum.queue

import com.dewijones92.totum.data.queue.QueueGroup
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
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
                    .plus(directItems(intent.getStringExtra("urls")))
                val group = intent.getStringExtra("group")?.let { QueueGroup(id = "debug:$it", title = it) }
                val appended = intent.getStringExtra("append").orEmpty().split(',').filter { it.isNotBlank() }.mapNotNull { id ->
                    val url = HttpUrl.of("https://www.youtube.com/watch?v=$id")
                    placeholderFor(url, SourceId("debug"))?.let { PlayableItem(it, PlayHandle.Video(url)) }
                }
                val appendGroup = intent.getStringExtra("appendGroup")?.let { QueueGroup(id = "debug:$it", title = it) }
                container.applicationScope.launch {
                    (items + appended).forEach { container.playbackProgressStore.setPlayed(it.item.id, false) }
                    Diag.log("queue", "dewidebug DEBUG play-all: ${items.size + appended.size} item(s) marked unplayed first")
                    container.playbackQueue.clear()
                    container.playbackQueue.playAll(items, group)
                    if (appended.isNotEmpty()) container.playbackQueue.enqueueAll(appended, appendGroup)
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
            ACTION_PLAY_ON_SCREEN -> {
                val screens = container.playbackController.screens.value
                val id = if (intent.getBooleanExtra("back", false)) {
                    null
                } else {
                    intent.getStringExtra("id") ?: screens.present.firstOrNull()?.id
                }
                Diag.log("screen", "dewidebug DEBUG play on screen $id of ${screens.present.map { it.name }}")
                container.playbackController.playOnScreen(id)
            }
            ACTION_KID_LOCK -> if (intent.getBooleanExtra("on", true)) {
                container.kidLock.lock("a debug broadcast")
            } else {
                container.kidLock.unlock("a debug broadcast")
            }
            ACTION_DOWNLOAD -> container.applicationScope.launch {
                val audioOnly = intent.getBooleanExtra("audioOnly", false)
                ids.mapNotNull { id ->
                    val url = HttpUrl.of("https://www.youtube.com/watch?v=$id")
                    placeholderFor(url, SourceId("debug"))?.let { PlayableItem(it, PlayHandle.Video(url)) }
                }.forEach { container.downloadManager.download(it, audioOnly) }
            }
        }
    }

    private fun directItems(urls: String?): List<PlayableItem> =
        urls.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).mapIndexed { index, address ->
            val url = HttpUrl.of(address)
            PlayableItem(
                MediaItem(
                    id = MediaItemId("debug:url:$index:${address.hashCode()}"),
                    sourceId = SourceId("debug"),
                    title = address.substringAfterLast('/').substringBefore('?'),
                    publishedAt = null,
                    duration = null,
                    mediaUrl = url,
                ),
                PlayHandle.Podcast(),
            )
        }

    private companion object {
        const val ACTION_PLAY_ALL = "com.dewijones92.totum.DEBUG_QUEUE_PLAY_ALL"
        const val ACTION_SEEK_NEAR_END = "com.dewijones92.totum.DEBUG_SEEK_NEAR_END"
        const val ACTION_SET_GAPLESS = "com.dewijones92.totum.DEBUG_SET_GAPLESS"
        const val ACTION_PLAY_ON_SCREEN = "com.dewijones92.totum.DEBUG_PLAY_ON_SCREEN"
        const val ACTION_DOWNLOAD = "com.dewijones92.totum.DEBUG_DOWNLOAD"
        const val ACTION_KID_LOCK = "com.dewijones92.totum.DEBUG_KID_LOCK"
        const val DEFAULT_BEFORE_END_MS = 35_000L
    }
}
