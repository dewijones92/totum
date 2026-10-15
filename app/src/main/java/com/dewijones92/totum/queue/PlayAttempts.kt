package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.Refusal
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class RefusedTap(val title: String, val reason: Refusal?)

data class Starting(val id: MediaItemId, val title: String, val sinceMs: Long)

internal class PlayAttempts(private val now: () -> Long = System::currentTimeMillis) {
    private var last: Refusal? = null
    private val emitted = MutableSharedFlow<RefusedTap>(extraBufferCapacity = BUFFER)
    val taps: SharedFlow<RefusedTap> = emitted.asSharedFlow()
    private val startingNow = MutableStateFlow<Starting?>(null)
    val starting: StateFlow<Starting?> = startingNow.asStateFlow()

    fun begin(item: MediaItem): Starting {
        last = null
        val attempt = Starting(item.id, item.title, now())
        startingNow.value = attempt
        Diag.log("queue", "starting \"${item.title.take(TITLE_CHARS)}\": shown as ⏳ until it plays or fails")
        return attempt
    }

    fun end(attempt: Starting, played: Boolean) {
        if (startingNow.compareAndSet(attempt, null)) {
            Diag.log(
                "queue",
                "\"${attempt.title.take(TITLE_CHARS)}\" ${if (played) "started" else "did not start"} " +
                    "${now() - attempt.sinceMs}ms after the tap",
            )
        }
    }

    fun supersededBy(id: MediaItemId) {
        val waiting = startingNow.value ?: return
        if (waiting.id == id) return
        if (startingNow.compareAndSet(waiting, null)) {
            Diag.log(
                "queue",
                "\"${waiting.title.take(TITLE_CHARS)}\" no longer shown as starting: ${id.value} took over"
            )
        }
    }

    fun note(reason: Refusal?) {
        last = reason
    }

    fun announce(title: String) {
        val why = last ?: "it resolved to nothing"
        Diag.warn("queue", "told the user \"${title.take(TITLE_CHARS)}\" would not play ($why)")
        emitted.tryEmit(RefusedTap(title, last))
    }

    private companion object {
        const val BUFFER = 4
        const val TITLE_CHARS = 60
    }
}
