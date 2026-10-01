package com.dewijones92.totum.exsurge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag

class ExsurgeDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val exsurge = (context.applicationContext as TotumApplication).container.exsurge
        val total = intent.getLongExtra("total", -1)
        Diag.log(ExsurgeController.TAG, "dewidebug exsurge DEBUG broadcast action=${intent.action} total=$total")
        when (intent.action) {
            "com.dewijones92.totum.exsurge.DEBUG_STEPS" -> if (total >= 0) exsurge.simulateSteps(total)
            "com.dewijones92.totum.exsurge.DEBUG_TICK" -> exsurge.dispatch(ExsurgeEvent.Tick, "debug")
            "com.dewijones92.totum.exsurge.DEBUG_SUMMON" -> exsurge.dispatch(ExsurgeEvent.SummonNow, "debug")
        }
    }
}
