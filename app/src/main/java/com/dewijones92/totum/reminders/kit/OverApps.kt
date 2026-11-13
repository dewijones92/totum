package com.dewijones92.totum.reminders.kit

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.dewijones92.totum.common.Diag

object OverApps {
    fun granted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun start(context: Context, tag: String, intent: Intent, what: String): Boolean = runCatching {
        context.startActivity(intent)
    }.onFailure {
        Diag.warn(
            tag,
            "dewidebug $what: starting it directly over other apps was refused; the notification remains",
            it
        )
    }.isSuccess
}
