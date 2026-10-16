package com.dewijones92.totum.ui.cast

import android.view.ContextThemeWrapper
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.dewijones92.totum.R
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
    AndroidView(
        factory = { ctx ->
            // The AppCompat attributes MediaRouter needs come from the activity theme
            // (see themes.xml) — a per-button ContextThemeWrapper used to live here, and it
            // was not enough, because the picker dialog is themed from the activity. An
            // empty view if Cast still can't initialise, so a GMS-less device shows nothing
            // rather than crashing.
            runCatching {
                val themed = if (onDark) ContextThemeWrapper(ctx, R.style.ThemeOverlay_Totum_CastOnDark) else ctx
                MediaRouteButton(themed).also { CastButtonFactory.setUpMediaRouteButton(themed, it) }
            }.getOrElse { View(ctx) }
        },
        modifier = modifier,
    )
}
