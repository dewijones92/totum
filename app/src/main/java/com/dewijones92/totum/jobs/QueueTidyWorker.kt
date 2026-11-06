package com.dewijones92.totum.jobs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import com.dewijones92.totum.R
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.notifications.HousekeepingNotifier
import com.dewijones92.totum.queue.QueueTidy
import com.dewijones92.totum.queue.TidyUndoStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration

class QueueTidyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TotumApplication).container
        return JOB.run(container.lastRuns) {
            val settings = container.appPreferences.settings.value
            if (!settings.tidyQueue) return@run JobOutcome.Skipped("switched off in Settings")
            val loaded = withTimeoutOrNull(HYDRATE_WAIT_MS) { container.playbackQueue.hydrated.first { it } }
            if (loaded == null) return@run JobOutcome.Failed("the queue had not loaded yet", retry = true)
            val result = container.queueTidy.tidy(settings.tidyAfterDays, "weekly")
            val detail = tidyDetail(applicationContext, result)
            if (result.movedAnything) {
                HousekeepingNotifier(applicationContext).tell(
                    HousekeepingNotifier.TIDY_ID,
                    applicationContext.getString(R.string.tidy_notification_title),
                    detail,
                    undo = result.playedMoved.takeIf { it > 0 }?.let {
                        HousekeepingNotifier.Undo(
                            label = applicationContext.getString(R.string.tidy_undo),
                            onTap = QueueTidyUndoReceiver.undoIntent(applicationContext),
                            onDismiss = QueueTidyUndoReceiver.dismissIntent(applicationContext),
                            timeoutMs = TidyUndoStore.UNDO_WINDOW_MS,
                        )
                    },
                )
            }
            JobOutcome.Done(detail)
        }
    }

    companion object {
        const val JOB_NAME = "queue-tidy"
        private const val HYDRATE_WAIT_MS = 30_000L
        private val JOB = PeriodicJob(JOB_NAME, Duration.ofDays(7), NetworkType.NOT_REQUIRED)

        fun schedule(context: Context) = JOB.schedule(context, QueueTidyWorker::class.java)
    }
}

fun tidyDetail(context: Context, result: QueueTidy.Result): String {
    val parts = buildList {
        if (result.playedMoved > 0) {
            add(context.resources.getQuantityString(R.plurals.tidy_played_left, result.playedMoved, result.playedMoved))
        }
        if (result.failedCleared > 0) {
            add(
                context.resources.getQuantityString(
                    R.plurals.tidy_failed_cleared,
                    result.failedCleared,
                    result.failedCleared,
                ),
            )
        }
    }
    return when (parts.size) {
        0 -> context.getString(R.string.tidy_nothing)
        1 -> parts.first()
        else -> context.getString(R.string.tidy_joined, parts[0], parts[1])
    }
}
