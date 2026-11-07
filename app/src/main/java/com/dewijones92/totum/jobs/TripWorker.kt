package com.dewijones92.totum.jobs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import com.dewijones92.totum.TotumApplication
import java.time.Duration

class TripWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TotumApplication).container
        return JOB.run(container.lastRuns) {
            val trip = container.tripMode
            when {
                trip == null -> JobOutcome.Skipped("trip mode is not available here")
                !trip.settings.value.armed -> JobOutcome.Skipped("trip mode is off")
                else -> JobOutcome.Done(trip.passNow("the 15-minute job"))
            }
        }
    }

    companion object {
        const val JOB_NAME = "trip-mode"
        private val JOB = PeriodicJob(JOB_NAME, Duration.ofMinutes(15), NetworkType.UNMETERED)

        fun schedule(context: Context) = JOB.schedule(context, TripWorker::class.java)
    }
}
