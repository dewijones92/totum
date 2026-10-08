package com.dewijones92.totum.reminders.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import com.dewijones92.totum.common.Diag

@Composable
fun KeepScreenAwake(where: String) {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        Diag.log("reminders", "$where keeps the screen awake")
        onDispose {
            view.keepScreenOn = false
            Diag.log("reminders", "$where left; the screen may sleep again")
        }
    }
}
