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

/**
 * A subprocess's output, or an explanation of why there is none — never a hang, never a survivor.
 *
 * Split out of `CrashReporter.logcatTail` so the give-up path can be driven by a test. It had none:
 * nothing asserted that a child which never answers is killed, nor that the report carries a reason
 * in the field itself rather than only in a log the report does not contain. An adversarial review
 * pointed out that a constant's KDoc claimed a test that did not exist.
 */
internal fun outputOf(start: () -> Process, seconds: Long, what: String, maxChars: Int): String =
    runCatching {
        val process = start()
        val output = drainWithin(seconds, what) {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        if (output == null) {
            // Killed, or it sits there holding a pipe for the rest of the process's life.
            process.destroyForcibly()
            unavailable(what, seconds)
        } else {
            // The child's stdin is a descriptor of ours, and Settings can ask for a report
            // repeatedly, so the happy path tidies up as well.
            runCatching { process.outputStream.close() }
            process.destroy()
            output.takeLast(maxChars)
        }
    }.getOrElse { "$what unavailable: ${it.javaClass.simpleName}: ${it.message}" }

/** The exact words a report uses to explain its own gap, so a test cannot drift from them. */
internal fun unavailable(what: String, seconds: Long): String =
    "$what unavailable: it did not answer in ${seconds}s"
