package com.dewijones92.totum.workapps

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.dailyalarms.PostedNotification
import com.dewijones92.totum.dailyalarms.QuietDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

class WorkAppsListenerService : NotificationListenerService() {
    private val workApps: WorkAppsQuiet
        get() = (application as TotumApplication).container.workApps

    private val ours by lazy { SnoozedKeys(applicationContext) }

    private var scope: CoroutineScope? = null

    override fun onListenerConnected() {
        Diag.log(WorkAppsQuiet.TAG, "dewidebug workapps listener connected")
        val live = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope?.cancel()
        scope = live
        live.launch { workApps.quietEnded.collect { handBack("${it.source}: ${it.packages.sorted()}", it.packages) } }
        handBack("listener connected", null)
        workApps.accessChanged("listener connected")
    }

    override fun onListenerDisconnected() {
        Diag.warn(
            WorkAppsQuiet.TAG,
            "dewidebug workapps listener disconnected; notifications pass through until it is back",
        )
        scope?.cancel()
        scope = null
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
            .onSuccess { ours.add(sbn.key) }
            .onFailure { workApps.snoozeFailed(sbn.packageName, it) }
    }

    private fun handBack(why: String, only: Set<String>?) {
        val snoozed = runCatching { snoozedNotifications.toList() }
            .onFailure { Diag.warn(WorkAppsQuiet.TAG, "dewidebug workapps could not list snoozed notifications", it) }
            .getOrNull() ?: return
        ours.keepOnly(snoozed.mapTo(mutableSetOf()) { it.key })
        val mine = snoozed.filter { ours.contains(it.key) }
        val due = mine.filter { sbn ->
            if (only != null) sbn.packageName in only else !workApps.isQuietNow(sbn.packageName)
        }
        Diag.log(
            WorkAppsQuiet.TAG,
            "dewidebug workapps hand back ($why): ${snoozed.size} snoozed in all, ${mine.size} by this listener, " +
                "${due.size} due back ${due.groupingBy { it.packageName }.eachCount()}",
        )
        due.forEach { sbn ->
            runCatching { snoozeNotification(sbn.key, HAND_BACK_MS) }
                .onFailure { Diag.warn(WorkAppsQuiet.TAG, "dewidebug workapps could not hand back ${sbn.key}", it) }
        }
        if (due.isEmpty()) return
        val keys = due.mapTo(mutableSetOf()) { it.key }
        ours.remove(keys)
        scope?.launch {
            delay(CHECK_MS)
            val left = runCatching { snoozedNotifications.count { it.key in keys } }.getOrNull()
            Diag.log(
                WorkAppsQuiet.TAG,
                "dewidebug workapps hand back ($why): ${keys.size} asked, $left still snoozed ${CHECK_MS}ms later",
            )
        }
    }

    private companion object {
        const val HAND_BACK_MS = 1_000L
        const val CHECK_MS = 5_000L
    }
}
