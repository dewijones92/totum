package com.dewijones92.totum.reminders.kit

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import com.dewijones92.totum.common.Diag

enum class ForegroundKind { HEALTH, SYSTEM_EXEMPTED }

fun Service.startForegroundTyped(id: Int, notification: Notification, kind: ForegroundKind, tag: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val type = when (kind) {
            ForegroundKind.HEALTH -> ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            ForegroundKind.SYSTEM_EXEMPTED -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        }
        startForeground(id, notification, type)
    } else {
        Diag.log(
            tag,
            "dewidebug ${javaClass.simpleName} foreground on Android ${Build.VERSION.SDK_INT}: the manifest's type"
        )
        startForeground(id, notification)
    }
}

fun mayUseFullScreenIntent(manager: android.app.NotificationManager): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || manager.canUseFullScreenIntent()
