package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.SkipDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class QueueSkips(
    private val scope: CoroutineScope,
    private val next: suspend () -> Unit,
    private val previous: suspend () -> Unit,
) {
    private val pressed = AtomicInteger()
    private val inFlight = AtomicInteger()

    fun skip(direction: SkipDirection, from: String) {
        val press = pressed.incrementAndGet()
        Diag.log(TAG, "skip #$press ${direction.name} from $from; ${inFlight.get()} earlier skip(s) still starting")
        scope.launch {
            inFlight.incrementAndGet()
            try {
                when (direction) {
                    SkipDirection.NEXT -> next()
                    SkipDirection.PREVIOUS -> previous()
                }
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    fun follow(requests: Flow<SkipDirection>) = scope.launch {
        requests.collect { direction -> skip(direction, "a media key or the notification") }
    }

    private companion object {
        const val TAG = "queue"
    }
}
