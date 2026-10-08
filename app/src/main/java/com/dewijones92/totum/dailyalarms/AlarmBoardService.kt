package com.dewijones92.totum.dailyalarms

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.LiveUpdate

class AlarmBoardService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) return stop("nothing set or being asked today")
        val board = latest ?: (application as TotumApplication).container.dailyAlarms.board()
        if (!board.pinned) return stop("started with nothing to pin")
        val notification = DailyAlarmNotifications(this).board(board)
        val pinned = runCatching {
            startForeground(
                DailyAlarmNotifications.BOARD_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        }.onFailure {
            Diag.warn(TAG, "dewidebug dailyalarm board could not be pinned; posting it unpinned", it)
            getSystemService(NotificationManager::class.java).notify(DailyAlarmNotifications.BOARD_ID, notification)
        }.isSuccess
        Diag.log(TAG, "dewidebug dailyalarm board pinned=$pinned ${LiveUpdate.describe(this, notification)}")
        return if (pinned) START_STICKY else stop("could not go foreground")
    }

    private fun stop(reason: String): Int {
        Diag.log(TAG, "dewidebug dailyalarm board service stopping: $reason")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = DailyAlarmController.TAG
        private const val ACTION_STOP = "com.dewijones92.totum.dailyalarms.STOP_BOARD"

        @Volatile
        private var running = false

        @Volatile
        private var latest: AlarmBoard? = null

        @Volatile
        private var lastLogged: String? = null

        private var leftoversCleared = false

        fun reconcile(context: Context, board: AlarmBoard) {
            latest = board
            val notifications = DailyAlarmNotifications(context)
            if (!leftoversCleared) {
                leftoversCleared = true
                board.rows.forEach { notifications.hideSet(it.alarmId) }
            }
            log(board)
            when {
                board.pinned && running -> context.getSystemService(NotificationManager::class.java)
                    .notify(DailyAlarmNotifications.BOARD_ID, notifications.board(board))
                board.pinned -> runCatching {
                    context.startForegroundService(Intent(context, AlarmBoardService::class.java))
                }.onFailure {
                    Diag.warn(TAG, "dewidebug dailyalarm board service refused; posting the board unpinned", it)
                    context.getSystemService(NotificationManager::class.java)
                        .notify(DailyAlarmNotifications.BOARD_ID, notifications.board(board))
                }
                running -> runCatching {
                    context.startService(Intent(context, AlarmBoardService::class.java).setAction(ACTION_STOP))
                }.onFailure { Diag.warn(TAG, "dewidebug dailyalarm board service could not be stopped", it) }
                else -> context.getSystemService(
                    NotificationManager::class.java
                ).cancel(DailyAlarmNotifications.BOARD_ID)
            }
        }

        private fun log(board: AlarmBoard) {
            val next = board.nextToRing?.let { "${it.label} ${it.time}" } ?: "none"
            val rows = board.rows.joinToString(" | ") { "${it.time} ${it.label} ${it.date} ${it.status}" }
            val line = "pinned=${board.pinned} next=$next rows=$rows"
            if (line == lastLogged) return
            lastLogged = line
            Diag.log(TAG, "dewidebug dailyalarm board $line")
        }
    }
}
