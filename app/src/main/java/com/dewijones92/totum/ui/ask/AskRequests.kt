package com.dewijones92.totum.ui.ask

import android.content.Intent
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AskRequests {
    const val ACTION_ASK = "com.dewijones92.totum.ASK"

    private val pending = MutableStateFlow<String?>(null)
    val requested: StateFlow<String?> = pending.asStateFlow()

    fun handle(intent: Intent, via: String) {
        if (intent.action != ACTION_ASK) return
        intent.action = null
        Diag.log("nav", "✨ Ask asked for by the home-screen shortcut via $via")
        pending.value = "the home-screen shortcut"
    }

    fun consumed() {
        pending.value = null
    }
}
