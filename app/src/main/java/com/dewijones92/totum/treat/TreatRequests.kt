package com.dewijones92.totum.treat

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag

object TreatRequests {
    const val ACTION_TREAT = "com.dewijones92.totum.TREAT"

    fun handle(intent: Intent, context: Context, treat: Treat?, via: String) {
        if (intent.action != ACTION_TREAT) return
        intent.action = null
        if (treat == null) {
            return Diag.warn("treat", "dewidebug the treat shortcut was used via $via but there is no treat here")
        }
        Diag.log("treat", "dewidebug treat asked for by the app-icon shortcut via $via; status ${treat.status.value}")
        if (treat.status.value is TreatStatus.NotSetUp) {
            Toast.makeText(context, R.string.treat_tile_set_up, Toast.LENGTH_LONG).show()
            return
        }
        treat.tap("the app-icon shortcut")
    }
}
