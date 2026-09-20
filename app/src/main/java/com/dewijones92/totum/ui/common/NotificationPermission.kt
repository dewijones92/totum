package com.dewijones92.totum.ui.common

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Asks for POST_NOTIFICATIONS as the app opens, once per composition.
 *
 * **"Once" is per composition, not per install**, and the name is the shorthand rather than the
 * guarantee. In practice that is once per cold start: the manifest declares
 * `configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden|uiMode"`,
 * so rotating or switching theme does not recreate the activity and cannot re-prompt. Someone who
 * denies is asked again on the next cold start until Android stops showing the dialog after two
 * denials, at which point `launch()` returns denied without showing anything.
 *
 * Without the grant, Android 13+ never shows Media3's playback notification, so the lock screen
 * and Bluetooth controls have nothing to drive.
 *
 * **It used to ask at first PLAY, and that was a defect.** The dialog is another activity: it
 * pauses ours, which releases the video surface. So the reward for pressing play on a video was
 * a permission prompt and a picture that had stopped — and it reproduces every time on a fresh
 * install, which is the first thing anyone does. CI caught it before Dewi did: the captured
 * logcat of the 2026-09-20 live run shows `START … REQUEST_PERMISSIONS`, then
 * `video size=0x0 hasVideo=false`, then `MainActivity in: PAUSED`, four milliseconds apart, and
 * the live tests had been red on it for five days reading as "the stream stopped".
 *
 * Asking as the shell composes costs the in-context moment the platform guidance prefers, and
 * buys a dialog that opens before playback rather than over it.
 *
 * "Nothing is playing yet" is only true because `MainActivity` makes it true: a launch carrying a
 * shared link composes the shell and THEN plays, so it passes no-op here and the ask waits for an
 * ordinary launch. Without that this would open a dialog over the very video it was launched to
 * play — the same defect, one entry point along.
 */
@Composable
fun RequestNotificationPermissionOnce(
    granted: () -> Boolean = defaultGranted(),
    request: (() -> Unit)? = null,
) {
    val fallback = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Denial is respected; playback still works, just without a notification. */ }
    val ask = request ?: { fallback.launch(Manifest.permission.POST_NOTIFICATIONS) }

    // Keyed on Unit so it runs once per composition's lifetime, not once per recomposition:
    // asking on every frame would queue a dialog behind every dismissal.
    LaunchedEffect(Unit) {
        if (!granted()) ask()
    }
}

@Composable
private fun defaultGranted(): () -> Boolean {
    val context = LocalContext.current
    return {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
