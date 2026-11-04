package com.dewijones92.totum.notifications

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

/**
 * Periodically refreshes every subscription (both pillars) and notifies the user
 * of genuinely-new content. All the pillar logic lives behind
 * [com.dewijones92.totum.di.AppContainer.contentRefresher]; this worker only
 * wires that seam to WorkManager and the notifier.
 */
public class NewContentWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TotumApplication).container
        // Said before the work, so a run that never returns is distinguishable from one that was
        // never scheduled — WorkManager can defer this for hours and nothing else records that
        // it woke up at all.
        Diag.log("content", "background refresh starting (every $INTERVAL_HOURS hours)")
        val check = NewContentCheck(
            refresher = container.contentRefresher,
            notify = { NewContentNotifier(applicationContext) { container.appPreferences.settings.value }.notify(it) },
        )
        return when (check.run()) {
            is NewContentCheck.Outcome.NothingNew, is NewContentCheck.Outcome.Notified -> Result.success()
            // Both leave the items unseen deliberately, so the next run finds them again.
            is NewContentCheck.Outcome.Undelivered, is NewContentCheck.Outcome.Failed -> Result.retry()
        }
    }

    public companion object {
        private const val UNIQUE_NAME = "new-content-refresh"
        internal const val INTERVAL_HOURS = 6L

        /** Schedules the periodic refresh, keeping any already-scheduled instance. */
        public fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NewContentWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
