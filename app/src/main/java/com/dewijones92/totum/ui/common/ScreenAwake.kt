package com.dewijones92.totum.ui.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.dewijones92.totum.common.Diag

internal data class ScreenAwake(val keepOn: Boolean, val reason: String)

internal fun screenAwakeDecision(playing: Boolean, fullscreen: Boolean, charging: Boolean): ScreenAwake = when {
    playing -> ScreenAwake(true, "the video is playing")
    fullscreen && charging -> ScreenAwake(true, "paused in full screen and charging")
    fullscreen -> ScreenAwake(false, "paused in full screen on battery")
    else -> ScreenAwake(false, "paused and not full screen")
}

internal fun isPluggedIn(plugged: Int): Boolean = plugged != 0

@Composable
private fun rememberIsCharging(): Boolean {
    val context = LocalContext.current
    var charging by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        fun read(intent: Intent?, how: String) {
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val now = isPluggedIn(plugged)
            if (now != charging) Diag.log("screenawake", "charging=$now plugged=$plugged ($how)")
            charging = now
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = read(intent, "plug or unplug")
        }
        read(context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)), "read at start")
        onDispose { context.unregisterReceiver(receiver) }
    }
    return charging
}

@Composable
fun KeepScreenOnForVideo(playing: Boolean, fullscreen: Boolean, where: String) {
    val view = LocalView.current
    val charging = rememberIsCharging()
    val decision = screenAwakeDecision(playing, fullscreen, charging)
    DisposableEffect(view, decision) {
        view.keepScreenOn = decision.keepOn
        Diag.log(
            "screenawake",
            "$where keepScreenOn=${decision.keepOn}: ${decision.reason} " +
                "[playing=$playing fullscreen=$fullscreen charging=$charging]",
        )
        onDispose { view.keepScreenOn = false }
    }
}
