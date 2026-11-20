package com.dewijones92.totum.cli.testsupport

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext

class OwnedScope(private val joinTimeoutMs: Long = JOIN_TIMEOUT_MS) : CoroutineScope {

    private val logged = LoggedUncaughtCoroutines()
    private val reported = CopyOnWriteArrayList<Throwable>()

    val uncaught: List<Throwable> get() = reported.toList()

    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Default +
        CoroutineExceptionHandler { context, error ->
            reported += error
            logged.handleException(context, error)
        }

    fun shutDown() {
        val job = coroutineContext.job
        runBlocking { withTimeout(joinTimeoutMs) { job.cancelAndJoin() } }
        check(reported.isEmpty()) { "background work threw after the test body finished: $reported" }
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 10_000L
    }
}
