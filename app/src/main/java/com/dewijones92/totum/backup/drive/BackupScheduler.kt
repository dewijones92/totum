package com.dewijones92.totum.backup.drive

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class BackupScheduler(
    private val scope: CoroutineScope,
    private val backUp: suspend (String) -> Unit,
    private val now: () -> Long,
    private val settleMs: Long = SETTLE_MS,
    private val maxWaitMs: Long = MAX_WAIT_MS,
) {
    fun follow(changes: Flow<String>): Job = scope.launch {
        val signals = Channel<String>(Channel.UNLIMITED)
        launch { changes.collect { signals.send(it) } }
        while (isActive) {
            val reasons = linkedSetOf(signals.receive())
            val since = now()
            gatherUntilSettled(signals, reasons, since)
            val why = reasons.joinToString()
            Diag.log("backup", "changes settled ($why, ${now() - since}ms after the first); backing up")
            backUp(why)
        }
    }

    private suspend fun gatherUntilSettled(signals: Channel<String>, reasons: MutableSet<String>, since: Long) {
        var lastAt = since
        do {
            val wait = minOf(settleMs - (now() - lastAt), maxWaitMs - (now() - since))
            val more = if (wait > 0) withTimeoutOrNull(wait) { signals.receive() } else null
            if (more != null) {
                reasons += more
                lastAt = now()
            }
        } while (more != null)
    }

    companion object {
        const val SETTLE_MS = 30_000L
        const val MAX_WAIT_MS = 300_000L
    }
}
