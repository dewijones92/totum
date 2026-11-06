package com.dewijones92.totum.jobs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import com.dewijones92.totum.R
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.backup.drive.BackupCheck
import com.dewijones92.totum.notifications.HousekeepingNotifier
import java.time.Duration

class BackupCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TotumApplication).container
        return JOB.run(container.lastRuns) {
            when (val check = container.driveBackup.checkLatest("weekly")) {
                BackupCheck.NotSetUp -> JobOutcome.Skipped("Drive backup is not set up")
                is BackupCheck.Readable -> JobOutcome.Done("${check.copy.name} from ${check.where}")
                is BackupCheck.Unreadable -> {
                    HousekeepingNotifier(applicationContext).tell(
                        HousekeepingNotifier.BACKUP_CHECK_ID,
                        applicationContext.getString(R.string.drive_backup_check_notification_title),
                        applicationContext.getString(R.string.drive_backup_check_notification_text, check.why),
                    )
                    JobOutcome.Failed(check.why)
                }
            }
        }
    }

    companion object {
        const val JOB_NAME = "backup-check"
        private val JOB = PeriodicJob(JOB_NAME, Duration.ofDays(7), NetworkType.CONNECTED)

        fun schedule(context: Context) = JOB.schedule(context, BackupCheckWorker::class.java)
    }
}
