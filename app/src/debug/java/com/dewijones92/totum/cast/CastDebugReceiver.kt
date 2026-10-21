package com.dewijones92.totum.cast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.cast.CastStandIn
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext

class CastDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        Diag.log("cast", "dewidebug DEBUG broadcast action=${intent.action} name=${intent.getStringExtra("name")}")
        when (intent.action) {
            ACTION_CAST -> castTo(app, intent.getStringExtra("name").orEmpty())
            ACTION_STOP -> CastContext.getSharedInstance(app).sessionManager.endCurrentSession(true)
            ACTION_SERVE -> Diag.log(
                "cast",
                "dewidebug STANDIN url=${CastStandIn.serveCurrent?.invoke(intent.getStringExtra("model")) ?: "none (no hook)"}",
            )
        }
    }

    private fun castTo(context: Context, wanted: String) {
        val router = MediaRouter.getInstance(context)
        val selector = MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID),
            )
            .build()
        val callback = object : MediaRouter.Callback() {}
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        val main = Handler(Looper.getMainLooper())
        var tries = 0
        val look = object : Runnable {
            override fun run() {
                val route = router.routes.firstOrNull { it.matchesSelector(selector) && it.name.contains(wanted, true) }
                when {
                    route != null -> {
                        Diag.log("cast", "dewidebug DEBUG selecting ${route.name}")
                        router.selectRoute(route)
                        router.removeCallback(callback)
                    }
                    tries++ < MAX_TRIES -> main.postDelayed(this, TRY_MS)
                    else -> {
                        Diag.warn("cast", "dewidebug DEBUG no route named like '$wanted' in ${MAX_TRIES * TRY_MS}ms")
                        router.removeCallback(callback)
                    }
                }
            }
        }
        main.post(look)
    }

    private companion object {
        const val ACTION_CAST = "com.dewijones92.totum.DEBUG_CAST"
        const val ACTION_STOP = "com.dewijones92.totum.DEBUG_CAST_STOP"
        const val ACTION_SERVE = "com.dewijones92.totum.DEBUG_CAST_SERVE"
        const val MAX_TRIES = 40
        const val TRY_MS = 500L
    }
}
