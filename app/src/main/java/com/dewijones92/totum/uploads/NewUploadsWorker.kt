package com.dewijones92.totum.uploads

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import java.util.concurrent.TimeUnit

class NewUploadsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TotumApplication).container
        Diag.log("uploads", "background check (every $INTERVAL_HOURS h, Wi-Fi only)")
        runCatching { container.newUploads.check("every $INTERVAL_HOURS h") }
            .onFailure { Diag.warn("uploads", "background check failed", it) }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "new-uploads"
        private const val INTERVAL_HOURS = 2L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NewUploadsWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            Diag.log("uploads", "new-uploads check scheduled every $INTERVAL_HOURS h on unmetered networks")
        }
    }
}
