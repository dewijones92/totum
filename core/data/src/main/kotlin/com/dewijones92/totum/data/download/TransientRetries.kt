package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.isPermanent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

public data class RetryPlan(public val attempt: Int, public val of: Int, public val atMs: Long)

public class TransientRetries(
    private val scope: CoroutineScope,
    private val retry: suspend (MediaItemId) -> Unit,
    private val nowMs: () -> Long,
    private val mayRetryNow: () -> Boolean = { true },
    private val firstWaitMs: Long = FIRST_WAIT_MS,
    private val maxAttempts: Int = MAX_ATTEMPTS,
) {
    private val attempts = ConcurrentHashMap<MediaItemId, Int>()
    private val waiting = ConcurrentHashMap<MediaItemId, Job>()
    private val plans = MutableStateFlow<Map<MediaItemId, RetryPlan>>(emptyMap())

    public val planned: StateFlow<Map<MediaItemId, RetryPlan>> = plans.asStateFlow()

    public fun failed(id: MediaItemId, title: String, failure: DownloadState.Failed) {
        if (failure.isPermanent) {
            Diag.log(
                "download",
                "\"$title\" failed for good (${failure.reason.take(REASON_CHARS)}); not retrying by itself"
            )
            forget(id)
            return
        }
        val attempt = (attempts[id] ?: 0) + 1
        if (attempt > maxAttempts) {
            Diag.warn("download", "\"$title\" failed again after $maxAttempts automatic retries; left for Retry")
            forget(id)
            return
        }
        attempts[id] = attempt
        val waitMs = firstWaitMs * FACTOR.pow(attempt - 1)
        plans.update { it + (id to RetryPlan(attempt, maxAttempts, nowMs() + waitMs)) }
        Diag.log(
            "download",
            "\"$title\" failed (${failure.reason.take(REASON_CHARS)}); retrying by itself in ${waitMs / MS_PER_S}s, " +
                "resuming from what is kept (retry $attempt of $maxAttempts)",
        )
        waiting.remove(id)?.cancel()
        waiting[id] = scope.launch {
            delay(waitMs)
            while (!mayRetryNow()) {
                Diag.log("download", "\"$title\" waits for a usable network before its retry $attempt")
                delay(NETWORK_POLL_MS)
            }
            plans.update { it - id }
            waiting.remove(id)
            retry(id)
        }
    }

    public fun succeeded(id: MediaItemId) {
        if (attempts.remove(id) != null) Diag.log("download", "${id.value} landed after automatic retries")
        forget(id)
    }

    public fun forget(id: MediaItemId) {
        waiting.remove(id)?.cancel()
        attempts.remove(id)
        plans.update { it - id }
    }

    private fun Long.pow(times: Int): Long = (1..times).fold(1L) { acc, _ -> acc * this }

    public companion object {
        public const val FIRST_WAIT_MS: Long = 30_000L
        public const val MAX_ATTEMPTS: Int = 4
        private const val FACTOR = 4L
        private const val NETWORK_POLL_MS = 60_000L
        private const val MS_PER_S = 1_000L
        private const val REASON_CHARS = 120
    }
}
