package com.dewijones92.totum.jobs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import com.dewijones92.totum.TotumApplication
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

class MorningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TotumApplication).container
        return JOB.run(container.lastRuns) {
            val features = container.aiFeatures
            val state = features.morningStore.state.value
            val job = features.morningJob
            when {
                job == null -> JobOutcome.Skipped("no morning job in this container")
                !state.digest && !state.forYou -> JobOutcome.Skipped("the digest and For you are both switched off")
                else -> JobOutcome.Done(job.detail(job.runAndTell("scheduled")))
            }
        }
    }

    companion object {
        const val JOB_NAME = "morning"
        private val MORNING = LocalTime.of(7, 30)
        private val JOB = PeriodicJob(
            JOB_NAME,
            Duration.ofDays(1),
            NetworkType.CONNECTED,
            skipsUnderInstrumentation = true,
        )

        fun schedule(context: Context) = JOB.schedule(context, MorningWorker::class.java, untilMorning())

        internal fun untilMorning(now: LocalDateTime = LocalDateTime.now()): Duration {
            val today = now.toLocalDate().atTime(MORNING)
            val next = if (now.isBefore(today)) today else today.plusDays(1)
            return Duration.between(now, next)
        }
    }
}
