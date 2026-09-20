package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.common.Diag
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Reads something that might never answer, and gives up rather than hanging.
 *
 * Null means it did not answer in time — or answered by throwing — and the caller is expected to
 * kill whatever it was reading and say so in the artefact, not merely in a log.
 *
 * The thread is a daemon because the one caller runs while the process is dying, and a non-daemon
 * thread parked on an unreadable pipe would be one more thing keeping it alive.
 */
internal fun drainWithin(seconds: Long, what: String, read: () -> String): String? {
    val drain = FutureTask(read)
    Thread(drain, "drain-$what").apply { isDaemon = true }.start()
    return try {
        drain.get(seconds, TimeUnit.SECONDS)
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
        // Every way of not getting an answer is the same answer, and all of them must reach the
        // caller's cleanup. Catching only TimeoutException left an ExecutionException to fall
        // through to an outer handler that returned a message and never killed the child.
        Diag.warn("diagnostics", "$what did not answer within ${seconds}s", failure)
        drain.cancel(true)
        null
    }
}
