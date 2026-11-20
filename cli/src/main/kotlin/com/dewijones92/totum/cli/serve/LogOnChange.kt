package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.common.Diag
import java.util.concurrent.ConcurrentHashMap

internal class LogOnChange(
    private val tag: String,
    private val everyMs: Long = EVERY_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Seen(var signature: String, var loggedAtMs: Long, var repeats: Int)

    private val seen = ConcurrentHashMap<String, Seen>()

    fun log(key: String, signature: String, message: String) {
        val now = clock()
        val before = seen[key]
        if (before == null || before.signature != signature) {
            seen[key] = Seen(signature, now, 0)
            Diag.log(tag, message)
            return
        }
        before.repeats++
        if (now - before.loggedAtMs >= everyMs) {
            val minutes = (now - before.loggedAtMs) / MS_PER_MIN
            Diag.log(tag, "$message [unchanged: seen ${before.repeats} more time(s) in the last $minutes min]")
            before.loggedAtMs = now
            before.repeats = 0
        }
    }

    fun log(key: String, message: String) = log(key, message, message)

    private companion object {
        const val EVERY_MS = 30 * 60_000L
        const val MS_PER_MIN = 60_000L
    }
}
