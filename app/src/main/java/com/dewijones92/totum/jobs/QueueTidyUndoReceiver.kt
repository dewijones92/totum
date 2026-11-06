package com.dewijones92.totum.jobs

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.dewijones92.totum.R
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.notifications.HousekeepingNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class QueueTidyUndoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as TotumApplication).container
        when (val action = intent.action) {
            UNDO -> {
                val pending = goAsync()
                container.applicationScope.launch {
                    try {
                        val loaded =
                            withTimeoutOrNull(HYDRATE_WAIT_MS) { container.playbackQueue.hydrated.first { it } }
                        val put = if (loaded == null) {
                            Diag.warn("tidy", "dewidebug undo from the notification: the queue had not loaded in time")
                            0
                        } else {
                            container.queueTidy.undo("the notification's Undo")
                        }
                        NotificationManagerCompat.from(context).cancel(HousekeepingNotifier.TIDY_ID)
                        val said = if (put > 0) {
                            context.resources.getQuantityString(R.plurals.tidy_undone, put, put)
                        } else {
                            context.getString(R.string.tidy_undo_nothing)
                        }
                        withContext(Dispatchers.Main) { Toast.makeText(context, said, Toast.LENGTH_LONG).show() }
                    } finally {
                        pending.finish()
                    }
                }
            }
            DISMISSED -> container.queueTidy.forgetUndo("the notification was dismissed")
            else -> Diag.warn("tidy", "dewidebug the undo receiver ignored an unknown action: $action")
        }
    }

    companion object {
        private const val UNDO = "com.dewijones92.totum.TIDY_UNDO"
        private const val DISMISSED = "com.dewijones92.totum.TIDY_DISMISSED"
        private const val HYDRATE_WAIT_MS = 30_000L

        fun undoIntent(context: Context): PendingIntent = broadcast(context, UNDO, requestCode = 1)

        fun dismissIntent(context: Context): PendingIntent = broadcast(context, DISMISSED, requestCode = 2)

        private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, QueueTidyUndoReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
