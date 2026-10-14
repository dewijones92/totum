package com.dewijones92.totum.stats

import android.os.SystemClock
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.playback.PlaybackEvent
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.queue.PlaybackQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class ListenRecorder(
    private val controller: PlaybackController,
    private val queue: PlaybackQueue,
    private val log: StatsLog,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val ticks: () -> Long = SystemClock::elapsedRealtime,
) {
    private var play: StatEvent.Listen? = null
    private var lastTick: Long? = null
    private var sinceSave = 0L

    fun start() {
        scope.launch {
            log.pending()?.let { text ->
                runCatching { StatsCodec.read(sequenceOf(text)).events.single() as StatEvent.Listen }
                    .onSuccess { recovered ->
                        Diag.log(
                            TAG,
                            "recovered a play cut off by the process ending: ${recovered.title} ${recovered.heardMs}ms"
                        )
                        log.record(recovered)
                    }
                    .onFailure { Diag.warn(TAG, "dewidebug stats dropped an unreadable play in progress", it) }
                log.savePending(null)
            }
            launch {
                controller.events.collect {
                    if (it is PlaybackEvent.Ended && it.itemId.value == play?.itemId) {
                        finish(
                            "it ended"
                        )
                    }
                }
            }
            while (isActive) {
                tick(controller.state.value)
                delay(TICK_MS)
            }
        }
    }

    private fun tick(state: PlaybackState?) {
        val at = ticks()
        val elapsed = lastTick?.let { at - it } ?: 0L
        lastTick = at
        if (state == null) return
        if (play?.itemId != state.itemId.value) {
            finish("the item changed")
            if (state.isPlaying) play = begin(state)
            return
        }
        val current = play ?: return
        if (!state.isPlaying) return
        play = current.copy(
            heardMs = current.heardMs + elapsed.coerceIn(0, MAX_STEP_MS),
            watched = current.watched || state.hasVideo,
        )
        sinceSave += elapsed
        if (sinceSave >= SAVE_EVERY_MS) {
            sinceSave = 0
            log.savePending(play?.let(StatsCodec::line))
        }
    }

    private fun begin(state: PlaybackState): StatEvent.Listen {
        val item = queue.state.value.entries.firstOrNull { it.item.item.id == state.itemId }?.item?.item
        val kind = when {
            item?.contentKind == MediaContentKind.MUSIC -> ListenKind.MUSIC
            state.kind == MediaKind.PODCAST -> ListenKind.PODCAST
            else -> ListenKind.VIDEO
        }
        Diag.log(TAG, "listen began: ${state.title} as $kind (queued=${item != null})")
        return StatEvent.Listen(
            at = now(),
            playId = UUID.randomUUID().toString(),
            itemId = state.itemId.value,
            title = state.title,
            author = item?.author ?: state.artist,
            source = state.artist,
            album = item?.album?.title,
            kind = kind,
            watched = state.hasVideo,
            heardMs = 0,
            durationMs = state.durationMs,
        )
    }

    private fun finish(why: String) {
        val done = play ?: return
        play = null
        sinceSave = 0
        log.savePending(null)
        if (done.heardMs <= 0) return
        log.record(done)
        Diag.log(
            TAG,
            "listen ended ($why): ${done.title} heard ${done.heardMs}ms of ${done.durationMs}ms, counts=${done.counts}"
        )
    }

    private companion object {
        const val TAG = "stats"
        const val TICK_MS = 1_000L
        const val SAVE_EVERY_MS = 30_000L
        const val MAX_STEP_MS = 5_000L
    }
}
