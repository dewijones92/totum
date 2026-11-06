package com.dewijones92.totum.workapps

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.dailyalarms.PostedNotification
import com.dewijones92.totum.dailyalarms.QuietDecision
import java.time.Duration
import java.time.Instant

class WorkAppsListenerService : NotificationListenerService() {
    private val workApps: WorkAppsQuiet
        get() = (application as TotumApplication).container.workApps

    override fun onListenerConnected() {
        Diag.log(WorkAppsQuiet.TAG, "dewidebug workapps listener connected")
        workApps.accessChanged("listener connected")
    }

    override fun onListenerDisconnected() {
        Diag.warn(
            WorkAppsQuiet.TAG,
            "dewidebug workapps listener disconnected; notifications pass through until it is back",
        )
        workApps.accessChanged("listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val posted = PostedNotification(
            packageName = sbn.packageName,
            ongoing = sbn.isOngoing || sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            category = sbn.notification.category,
        )
        val decision = workApps.decide(posted)
        if (decision !is QuietDecision.Snooze) return
        runCatching { snoozeNotification(sbn.key, Duration.between(Instant.now(), decision.until).toMillis()) }
            .onFailure { workApps.snoozeFailed(sbn.packageName, it) }
    }
}
