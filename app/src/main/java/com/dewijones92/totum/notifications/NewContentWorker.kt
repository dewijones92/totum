package com.dewijones92.totum.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.jobs.JobOutcome
import com.dewijones92.totum.jobs.PeriodicJob
import com.dewijones92.totum.live.LiveNoticeLabels
import java.time.Duration

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
        return JOB.run(container.lastRuns) {
            val check = NewContentCheck(
                refresher = container.contentRefresher,
                notify = { updates ->
                    val notifier = NewContentNotifier(
                        applicationContext,
                        { container.appPreferences.settings.value },
                        LiveNoticeLabels(applicationContext, container.liveStates, container.aiFeatures.spoilerShield),
                    )
                    notifier.notify(updates)
                },
            )
            // Both leave the items unseen deliberately, so the next run finds them again.
            when (val outcome = check.run()) {
                is NewContentCheck.Outcome.NothingNew -> JobOutcome.Done("nothing new")
                is NewContentCheck.Outcome.Notified -> JobOutcome.Done("${outcome.items} new item(s) notified")
                is NewContentCheck.Outcome.Undelivered ->
                    JobOutcome.Failed("${outcome.items} new item(s) could not be notified", retry = true)
                is NewContentCheck.Outcome.Failed ->
                    JobOutcome.Failed(outcome.error.message ?: outcome.error.javaClass.simpleName, retry = true)
            }
        }
    }

    public companion object {
        internal val JOB = PeriodicJob("new-content-refresh", Duration.ofHours(6), NetworkType.CONNECTED)

        /** Schedules the periodic refresh, keeping any already-scheduled instance. */
        public fun schedule(context: Context) = JOB.schedule(context, NewContentWorker::class.java)
    }
}
