package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class BudgetedOpen(
    private val budgetMs: Long = BUDGET_MS,
    private val workers: Executor = DAEMONS,
) {
    class Outcome<T>(val value: T, val usedFallback: Boolean)

    fun <T : Any> run(
        label: String,
        primary: () -> T,
        fallback: () -> T,
        upstreamAsked: () -> Boolean,
        discard: (T) -> Unit,
    ): Outcome<T> {
        val attempt = CompletableFuture.supplyAsync(primary, workers)
        try {
            return Outcome(attempt.get(budgetMs, TimeUnit.MILLISECONDS), usedFallback = false)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (_: TimeoutException) {
            if (upstreamAsked()) {
                Diag.log(
                    "cast",
                    "dewidebug $label: no answer after ${budgetMs}ms, but upstream has been asked, so waiting on it",
                )
                return Outcome(waitFor(attempt), usedFallback = false)
            }
        }
        Diag.warn(
            "cast",
            "dewidebug $label: the cache gave no answer in ${budgetMs}ms and upstream was never asked " +
                "(a span of it is locked by another reader or writer), so reading upstream directly, uncached",
        )
        attempt.whenComplete { late, _ -> if (late != null) runCatching { discard(late) } }
        return Outcome(fallback(), usedFallback = true)
    }

    private fun <T> waitFor(attempt: CompletableFuture<T>): T = try {
        attempt.get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    companion object {
        const val BUDGET_MS: Long = 6_000L

        private val DAEMONS: Executor = Executors.newCachedThreadPool { task ->
            Thread(task, "cast-relay-open").apply { isDaemon = true }
        }
    }
}
