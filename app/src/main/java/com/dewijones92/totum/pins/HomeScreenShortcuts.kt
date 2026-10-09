package com.dewijones92.totum.pins

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag

class HomeScreenShortcuts(private val context: Context) {

    val canPin: Boolean get() = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    suspend fun requestPin(pin: Pin): Boolean {
        if (!canPin) {
            Diag.warn("pin", "this launcher does not accept pinned shortcuts; ${pin.key} stays in the app only")
            return false
        }
        val asked = ShortcutManagerCompat.requestPinShortcut(context, shortcut(pin, artwork(pin)), null)
        Diag.log("pin", "asked the launcher to pin ${pin.key} -> accepted the request=$asked")
        return asked
    }

    suspend fun publish(pins: List<Pin>) {
        val shown = pins.takeLast(
            ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(MAX_DYNAMIC)
        )
        val shortcuts = shown.map { shortcut(it, artwork(it)) }
        val ok = runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
            .onFailure { Diag.warn("pin", "could not publish app-icon shortcuts", it) }
            .getOrDefault(false)
        Diag.log("pin", "app-icon shortcuts: ${shortcuts.size} of ${pins.size} pin(s) published=$ok")
    }

    private fun shortcut(pin: Pin, art: Bitmap?): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, pin.key)
            .setShortLabel(pin.title.take(SHORT_LABEL))
            .setLongLabel(pin.title.take(LONG_LABEL))
            .setIcon(
                art?.let(IconCompat::createWithBitmap) ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher)
            )
            .setIntent(
                Intent(Intent.ACTION_VIEW, deepLink(pin.key), context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            .build()

    private suspend fun artwork(pin: Pin): Bitmap? {
        val url = pin.artUrl ?: return null
        val request = ImageRequest.Builder(context).data(url).size(ICON_PX).allowHardware(false).build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
        if (bitmap == null) Diag.log("pin", "no artwork for ${pin.key}; using the app icon")
        return bitmap?.let(::squared)
    }

    private fun squared(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        return Bitmap.createScaledBitmap(Bitmap.createBitmap(source, left, top, side, side), ICON_PX, ICON_PX, true)
    }

    companion object {
        private const val SCHEME = "totum"
        private const val HOST = "pin"
        private const val SHORT_LABEL = 24
        private const val LONG_LABEL = 60
        private const val ICON_PX = 192
        private const val MAX_DYNAMIC = 4

        fun deepLink(key: String): Uri = Uri.Builder().scheme(SCHEME).authority(HOST).appendPath(key).build()

        fun keyFrom(uri: Uri?): String? =
            uri?.takeIf { it.scheme == SCHEME && it.host == HOST }?.pathSegments?.firstOrNull()
    }
}
