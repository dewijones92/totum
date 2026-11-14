package com.dewijones92.totum.playback

import android.os.Handler
import android.os.Looper

internal interface Ticker {
    fun later(task: Runnable, delayMs: Long)

    fun cancel(task: Runnable)
}

internal class HandlerTicker : Ticker {
    private val handler = Handler(Looper.getMainLooper())

    override fun later(task: Runnable, delayMs: Long) {
        handler.postDelayed(task, delayMs)
    }

    override fun cancel(task: Runnable) {
        handler.removeCallbacks(task)
    }
}
