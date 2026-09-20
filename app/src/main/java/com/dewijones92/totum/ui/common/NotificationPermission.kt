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
 * Asks for POST_NOTIFICATIONS once, as the app opens. Without the grant, Android 13+ never
 * shows Media3's playback notification, so the lock screen and Bluetooth controls have nothing
 * to drive.
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
 * buys a dialog that can interrupt nothing, because nothing is playing yet.
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
