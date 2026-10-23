package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.SkipDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class QueueSkips(
    private val scope: CoroutineScope,
    private val next: suspend (count: Int) -> Unit,
    private val previous: suspend () -> Unit,
) {
    private var pressed = 0
    private var gathered = 0
    private var settling: Job? = null
    private var running: Job? = null

    @Synchronized
    fun skip(direction: SkipDirection, from: String) {
        pressed++
        when (direction) {
            SkipDirection.NEXT -> {
                gathered++
                settling?.cancel()
                Diag.log(
                    TAG,
                    "skip #$pressed NEXT from $from; $gathered gathered, landing ${SETTLE_MS}ms after the last"
                )
                settling = scope.launch {
                    delay(SETTLE_MS)
                    val count = take()
                    Diag.log(TAG, "skipping $count on at once, so only the item landed on is looked up")
                    start { next(count) }
                }
            }
            SkipDirection.PREVIOUS -> {
                settling?.cancel()
                val dropped = take()
                Diag.log(
                    TAG,
                    "skip #$pressed PREVIOUS from $from" + if (dropped > 0) "; $dropped gathered NEXT dropped" else "",
                )
                start { previous() }
            }
        }
    }

    fun follow(requests: Flow<SkipDirection>) = scope.launch {
        requests.collect { direction -> skip(direction, "a media key or the notification") }
    }

    @Synchronized
    private fun take(): Int = gathered.also { gathered = 0 }

    @Synchronized
    private fun start(block: suspend () -> Unit) {
        running?.let { earlier ->
            if (earlier.isActive) Diag.log(TAG, "an earlier skip still starting is dropped for this one")
            earlier.cancel()
        }
        running = scope.launch { block() }
    }

    companion object {
        const val SETTLE_MS = 500L
        private const val TAG = "queue"
    }
}
