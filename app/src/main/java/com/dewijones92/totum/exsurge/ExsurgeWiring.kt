package com.dewijones92.totum.exsurge

import android.content.Context
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.playback.PlaybackInterruption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun exsurgeController(
    context: Context,
    playback: PlaybackController,
    scope: CoroutineScope,
    alarmRinging: () -> Boolean = { (context.applicationContext as TotumApplication).container.dailyAlarms.ringingNow },
): ExsurgeController {
    val interruption by lazy {
        PlaybackInterruption(playback, ExsurgeController.TAG).also { interruption ->
            scope.launch { playback.state.collect(interruption::observe) }
        }
    }
    return ExsurgeController(
        store = SharedPrefsExsurgeStore(context),
        ports = AndroidExsurgePorts(context, { playback }, alarmRinging) { interruption },
        sensorStepsAvailable = { ExsurgeBannerService.countingSteps },
    )
}
