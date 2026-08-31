package com.dewijones92.totum.downloads

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.notifications.DownloadNotifications

/**
 * Keeps the process alive while downloads are in flight. It downloads nothing itself.
 *
 * Downloads run in the application scope, which lives exactly as long as the process — and a
 * backgrounded app with nothing playing is a cached process Android reclaims whenever it wants the
 * memory. So "download the queue, put the phone in your pocket, get on a train" quietly did not
 * work: the fetch stopped wherever it had got to, and until 2026-08-31 the half-finished record was
 * deleted at the next launch, so there was not even a trace that it had been trying.
 *
 * A foreground service is the only thing on Android that says "this process is doing something the
 * person asked for". Playback already has one; downloading did not, which is why downloads survived
 * backgrounding only while music happened to be playing.
 *
 * Deliberately `START_NOT_STICKY`: if the process is killed anyway, the downloads died with it, and
 * a service restarted into an empty process would hold a notification over nothing. The record is
 * marked failed at the next launch and retried instead.
 */
internal class DownloadKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                DownloadNotifications.PROGRESS_ID,
                DownloadNotifications.holding(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }
        holdingProcess = started.isSuccess
        started.onFailure {
            // Android 12+ refuses a foreground service started from the background, and Android 15
            // caps dataSync at a few hours a day. Neither is recoverable here and neither may crash
            // the app: downloads simply fall back to lasting as long as the process does.
            Diag.warn("downloads", "could not hold the process open for downloads", it)
            stopSelf()
        }
        if (started.isSuccess) Diag.log("downloads", "holding the process open while downloads run")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        holdingProcess = false
        Diag.log("downloads", "no downloads left in flight — letting the process go")
        super.onDestroy()
    }

    companion object {
        /**
         * Whether the process is being held open for downloads right now.
         *
         * Reported in diagnostics because "downloads stopped when I put my phone away" and "the
         * hold was refused" look identical from the outside, and only one of them is a bug in this
         * app rather than an Android restriction.
         */
        @Volatile
        var holdingProcess: Boolean = false
            private set

        /**
         * Starts or stops the hold. Never throws: a refusal is a downgrade in reliability, never a
         * crash, and it is logged rather than swallowed so a report can say it happened.
         */
        fun hold(context: Context, holding: Boolean) {
            val intent = Intent(context, DownloadKeepAliveService::class.java)
            runCatching {
                if (holding) ContextCompat.startForegroundService(context, intent) else context.stopService(intent)
            }.onFailure {
                Diag.warn("downloads", "could not ${if (holding) "start" else "stop"} the download hold", it)
            }
        }
    }
}
