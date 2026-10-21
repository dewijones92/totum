package com.dewijones92.totum.ui.cast

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.mediarouter.app.MediaRouteButton
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext

/**
 * The standard Cast button. It manages its own visibility — invisible until a
 * Cast device is on the network — and renders nothing at all when Google Play
 * Services / the Cast framework aren't available (e.g. a non-GMS device), so it's
 * safe to place unconditionally.
 */
@Composable
fun CastButton(modifier: Modifier = Modifier, onDark: Boolean = false) {
    val context = LocalContext.current
    val available = remember {
        runCatching { CastContext.getSharedInstance(context) }.isSuccess
    }
    if (!available) return
    val button = remember { arrayOfNulls<MediaRouteButton>(1) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Diag.log("cast", "local network access ${if (granted) "granted" else "refused"} when Cast was tapped")
        if (granted) button[0]?.let { it.post { it.performClick() } }
    }
    val askNow = rememberUpdatedState { ask.launch(LOCAL_NETWORK) }
    AndroidView(
        factory = { ctx ->
            // The AppCompat attributes MediaRouter needs come from the activity theme
            // (see themes.xml) — a per-button ContextThemeWrapper used to live here, and it
            // was not enough, because the picker dialog is themed from the activity. An
            // empty view if Cast still can't initialise, so a GMS-less device shows nothing
            // rather than crashing.
            runCatching {
                val themed = if (onDark) ContextThemeWrapper(ctx, R.style.ThemeOverlay_Totum_CastOnDark) else ctx
                LocalNetworkCastButton(themed) { askNow.value() }
                    .also { CastButtonFactory.setUpMediaRouteButton(themed, it) }
                    .also { button[0] = it }
            }.getOrElse { View(ctx) }
        },
        modifier = modifier,
    )
}

@SuppressLint("ViewConstructor")
private class LocalNetworkCastButton(context: Context, private val ask: () -> Unit) : MediaRouteButton(context) {
    override fun performClick(): Boolean {
        if (needsLocalNetwork(context)) {
            Diag.log("cast", "Cast tapped without local network access; asking for it before looking for TVs")
            ask()
            return true
        }
        return super.performClick()
    }
}

private fun needsLocalNetwork(context: Context): Boolean =
    Build.VERSION.SDK_INT >= ANDROID_17 &&
        ContextCompat.checkSelfPermission(context, LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED

private const val ANDROID_17 = 37
private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
