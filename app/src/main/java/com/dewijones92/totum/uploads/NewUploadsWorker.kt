package com.dewijones92.totum.uploads

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.jobs.JobOutcome
import com.dewijones92.totum.jobs.PeriodicJob
import java.time.Duration

class NewUploadsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TotumApplication).container
        return JOB.run(container.lastRuns) {
            container.newUploads.check("every $INTERVAL_HOURS h")
            JobOutcome.Done()
        }
    }

    companion object {
        private const val INTERVAL_HOURS = 2L
        private val JOB = PeriodicJob(
            "new-uploads",
            Duration.ofHours(INTERVAL_HOURS),
            NetworkType.UNMETERED,
            skipsUnderInstrumentation = true,
        )

        fun schedule(context: Context) = JOB.schedule(context, NewUploadsWorker::class.java)
    }
}
