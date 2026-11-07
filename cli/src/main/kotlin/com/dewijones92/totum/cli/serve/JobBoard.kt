package com.dewijones92.totum.cli.serve

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

internal class JobBoard<S>(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    concurrent: Int = CONCURRENT,
    private val keepMs: Long = KEEP_MS,
) {
    class Held<S>(val started: Long, val status: S)

    private val held = ConcurrentHashMap<String, Held<S>>()
    private val permits = Semaphore(concurrent)

    val size: Int get() = held.size

    fun start(id: String, running: S, work: suspend (started: Long) -> S, onSettled: (S) -> Unit = {}) {
        prune()
        val started = clock()
        held[id] = Held(started, running)
        scope.launch {
            val done = permits.withPermit { work(started) }
            held[id] = Held(started, done)
            onSettled(done)
        }
    }

    fun find(id: String): Held<S>? = held[id]

    fun sinceStart(job: Held<S>): Long = clock() - job.started

    private fun prune() {
        val cutoff = clock() - keepMs
        held.entries.removeIf { it.value.started < cutoff }
    }

    companion object {
        const val CONCURRENT = 2
        const val KEEP_MS = 30 * 60_000L
    }
}
