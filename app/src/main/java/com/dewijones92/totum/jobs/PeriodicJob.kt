package com.dewijones92.totum.jobs

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.CancellationException
import java.time.Duration

sealed interface JobOutcome {
    data class Done(val detail: String = "") : JobOutcome

    data class Failed(val reason: String, val retry: Boolean = false) : JobOutcome

    data class Skipped(val why: String) : JobOutcome
}

class PeriodicJob(
    val name: String,
    private val interval: Duration,
    private val network: NetworkType,
    private val skipsUnderInstrumentation: Boolean = false,
) {
    fun schedule(context: Context, worker: Class<out ListenableWorker>, firstRunIn: Duration = Duration.ZERO) {
        val request = PeriodicWorkRequest.Builder(worker, interval)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setInitialDelay(firstRunIn)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.KEEP, request)
        val timing = "every ${interval.toMinutes()} min, first in ${firstRunIn.toMinutes()} min"
        Diag.log("jobs", "$name scheduled $timing [network=$network]")
    }

    suspend fun run(lastRuns: LastRuns, work: suspend () -> JobOutcome): ListenableWorker.Result {
        if (skipsUnderInstrumentation && !TotumApplication.warmEngineAfterLaunch) {
            Diag.log("jobs", "$name skipped: running under instrumentation")
            return ListenableWorker.Result.success()
        }
        // Said before the work, so a run that never returns is distinguishable from one that was
        // never scheduled — WorkManager can defer this for hours and nothing else records that
        // it woke up at all.
        Diag.log("jobs", "$name starting (every ${interval.toMinutes()} min, last ran ${describe(lastRuns.get(name))})")
        val outcome = try {
            work()
        } catch (cancelled: CancellationException) {
            Diag.log("jobs", "$name stopped by the system")
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            Diag.warn("jobs", "$name threw", failure)
            JobOutcome.Failed(failure.message ?: failure.javaClass.simpleName)
        }
        return when (outcome) {
            is JobOutcome.Done -> {
                lastRuns.succeeded(name, outcome.detail)
                ListenableWorker.Result.success()
            }
            is JobOutcome.Skipped -> {
                Diag.log("jobs", "$name skipped: ${outcome.why}")
                ListenableWorker.Result.success()
            }
            is JobOutcome.Failed -> {
                lastRuns.failed(name, outcome.reason)
                if (outcome.retry) ListenableWorker.Result.retry() else ListenableWorker.Result.success()
            }
        }
    }

    private fun describe(run: LastRun?): String = run?.let {
        "at ${it.ranAtMs} (${if (it.succeeded) "ok" else "failed"})"
    } ?: "never"
}
