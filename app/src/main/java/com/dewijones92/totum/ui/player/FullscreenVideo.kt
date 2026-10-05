package com.dewijones92.totum.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dewijones92.totum.common.Diag

/**
 * While [active], hides the system bars and locks landscape only when [lockLandscape] is true,
 * (immersive), restoring both when the video leaves fullscreen or the player
 * closes. The player is an in-activity overlay, so this targets the activity's
 * own window — the one that rotates — which is why fullscreen fills the screen;
 * a Dialog sub-window would stay portrait-sized and leave the video stranded.
 * (The [DialogWindowProvider] lookup is a harmless fallback for any dialog host.)
 */
@Composable
internal fun FullscreenEffect(active: Boolean, lockLandscape: Boolean = true) {
    val activity = LocalContext.current.findActivity()
    val view = LocalView.current
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window
    val window = dialogWindow ?: activity?.window

    DisposableEffect(active, lockLandscape) {
        Diag.log(
            "fullscreen",
            "active=$active lockLandscape=$lockLandscape " +
                "dialogWindow=${dialogWindow != null} activity=${activity != null}",
        )
        val insets = window?.let { WindowInsetsControllerCompat(it, view) }
        if (active) {
            activity?.requestedOrientation = if (lockLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            insets?.apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insets?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insets?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** Walks the context wrapper chain to the hosting [Activity], if any. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
