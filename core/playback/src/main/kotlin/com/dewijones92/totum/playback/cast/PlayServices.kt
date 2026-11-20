package com.dewijones92.totum.playback.cast

import android.content.Context
import com.google.android.gms.common.GoogleApiAvailability

public object PlayServices {

    public fun describe(context: Context): String = runCatching {
        val availability = GoogleApiAvailability.getInstance()
        val code = availability.isGooglePlayServicesAvailable(context)
        "play services version=${availability.getApkVersion(
            context
        )} status=${availability.getErrorString(code)}($code)"
    }.getOrElse { "play services version unreadable (${it.javaClass.simpleName}: ${it.message})" }
}
