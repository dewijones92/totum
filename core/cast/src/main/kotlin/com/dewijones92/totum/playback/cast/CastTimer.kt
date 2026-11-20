package com.dewijones92.totum.playback.cast

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal fun castLater(name: String = "cast-timer"): (Long, () -> Unit) -> Unit {
    val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, name).apply { isDaemon = true } }
    return { delayMs, run -> timer.schedule(run, delayMs, TimeUnit.MILLISECONDS) }
}
