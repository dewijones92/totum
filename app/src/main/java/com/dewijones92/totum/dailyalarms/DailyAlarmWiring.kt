package com.dewijones92.totum.dailyalarms

import android.content.Context
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.playback.PlaybackInterruption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun dailyAlarmController(context: Context, playback: PlaybackController, scope: CoroutineScope): DailyAlarmController {
    val interruption by lazy {
        PlaybackInterruption(playback, DailyAlarmController.TAG).also { interruption ->
            scope.launch { playback.state.collect(interruption::observe) }
        }
    }
    Diag.log(DailyAlarmController.TAG, "dewidebug dailyalarm controller created")
    return DailyAlarmController(SharedPrefsDailyAlarmStore(context), AndroidDailyAlarmPorts(context) { interruption })
}
