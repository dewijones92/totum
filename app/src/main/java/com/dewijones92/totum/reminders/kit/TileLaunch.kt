package com.dewijones92.totum.reminders.kit

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

fun TileService.collapseAndOpen(intent: Intent, requestCode: Int) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startActivityAndCollapse(
            PendingIntent.getActivity(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    } else {
        @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
        startActivityAndCollapse(intent)
    }
}
