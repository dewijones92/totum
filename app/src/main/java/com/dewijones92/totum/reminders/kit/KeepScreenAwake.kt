package com.dewijones92.totum.reminders.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import com.dewijones92.totum.common.Diag

@Composable
fun KeepScreenAwake(where: String) {
    HoldScreen(
        keepOn = true,
        tag = "reminders",
        entered = "$where keeps the screen awake",
        left = "$where left; the screen may sleep again",
    )
}

@Composable
fun HoldScreen(keepOn: Boolean, tag: String, entered: String, left: String? = null) {
    val view = LocalView.current
    DisposableEffect(view, keepOn, entered) {
        view.keepScreenOn = keepOn
        Diag.log(tag, entered)
        onDispose {
            view.keepScreenOn = false
            left?.let { Diag.log(tag, it) }
        }
    }
}
