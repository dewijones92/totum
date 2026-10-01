package com.dewijones92.totum.exsurge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag

class ExsurgeActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val exsurge = (context.applicationContext as TotumApplication).container.exsurge
        val action = intent.action.orEmpty()
        Diag.log(ExsurgeController.TAG, "dewidebug exsurge receiver action=$action")
        when (action) {
            TICK -> tickAfterFlush(exsurge)
            SNOOZE -> exsurge.dispatch(ExsurgeEvent.Snooze, "notification")
            SKIP -> exsurge.dispatch(ExsurgeEvent.Skip, "notification")
            JUST_WALK -> exsurge.dispatch(ExsurgeEvent.JustWalk, "notification")
            RESTART_CLOCK -> exsurge.dispatch(ExsurgeEvent.RestartClock, "banner")
            SUMMON_NOW -> exsurge.dispatch(ExsurgeEvent.SummonNow, "banner")
            PAUSE_HOUR -> exsurge.dispatch(ExsurgeEvent.PauseHour, "banner")
            REPOST -> exsurge.refresh()
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> exsurge.dispatch(ExsurgeEvent.Tick, "system:$action")
            else -> Diag.warn(ExsurgeController.TAG, "dewidebug exsurge receiver ignored unknown action=$action")
        }
    }

    private fun tickAfterFlush(exsurge: ExsurgeController) {
        if (!ExsurgeBannerService.flushSteps()) return exsurge.dispatch(ExsurgeEvent.Tick, "alarm")
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed(
            {
                exsurge.dispatch(ExsurgeEvent.Tick, "alarm, after flushing steps")
                pending.finish()
            },
            FLUSH_WAIT_MS,
        )
    }

    companion object {
        private const val FLUSH_WAIT_MS = 750L
        const val TICK = "com.dewijones92.totum.exsurge.TICK"
        const val SNOOZE = "com.dewijones92.totum.exsurge.SNOOZE"
        const val SKIP = "com.dewijones92.totum.exsurge.SKIP"
        const val SUMMON_NOW = "com.dewijones92.totum.exsurge.SUMMON_NOW"
        const val PAUSE_HOUR = "com.dewijones92.totum.exsurge.PAUSE_HOUR"
        const val REPOST = "com.dewijones92.totum.exsurge.REPOST"
        const val JUST_WALK = "com.dewijones92.totum.exsurge.JUST_WALK"
        const val RESTART_CLOCK = "com.dewijones92.totum.exsurge.RESTART_CLOCK"
        private val requestCodes = listOf(TICK, SNOOZE, SKIP, SUMMON_NOW, PAUSE_HOUR, REPOST, JUST_WALK, RESTART_CLOCK)

        fun pending(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_BASE + requestCodes.indexOf(action),
            Intent(context, ExsurgeActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private const val REQUEST_BASE = 7320
    }
}
