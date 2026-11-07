package com.dewijones92.totum.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The player's backdrop, tinted by whatever is playing.
 *
 * Dewi, 2026-08-07, asked which redesign option was "more sexy" and this is the answer: the surface
 * takes its colour from the artwork, so a Novara episode and a tennis highlight no longer look
 * identical. It is what makes a player feel like a product rather than a screen.
 *
 * **One flag, because it cuts against a recorded decision.** `CLAUDE.md` says dynamic colour is OFF
 * by default so the tangerine/cyan brand is actually seen; that decision was about Material You
 * substituting the *wallpaper's* palette across the whole app, which is a different thing from one
 * screen tinting itself with the thing it is playing. Still, the brand is quieter here than
 * elsewhere. [SOURCE] is the single switch: set it to [BackdropSource.Brand] and the player uses a
 * fixed brand gradient instead, with nothing else to change.
 *
 * The gradient fades to the ordinary surface colour a third of the way down, so everything below —
 * description, comments, up-next — sits on the normal background and stays readable. A full-height
 * tint looks impressive in a screenshot and makes body text hard work.
 */
internal enum class BackdropSource { Artwork, Brand }

/** Flip to [BackdropSource.Brand] for a fixed brand gradient. See [PlayerBackdrop]. */
internal val SOURCE: BackdropSource = BackdropSource.Artwork

/**
 * A [Modifier] painting the tinted gradient, and the colour it settled on.
 *
 * The colour is animated rather than swapped: an abrupt change on every track advance is jarring,
 * and the fade is most of why this reads as considered rather than gimmicky.
 */
@Composable
internal fun rememberPlayerBackdrop(artworkUrl: String?, fallback: Color): PlayerBackdrop {
    val context = LocalContext.current
    var extracted by remember(artworkUrl) { mutableStateOf<Color?>(null) }

    LaunchedEffect(artworkUrl, SOURCE) {
        if (SOURCE != BackdropSource.Artwork || artworkUrl.isNullOrBlank()) return@LaunchedEffect
        extracted = withContext(Dispatchers.Default) { colourOf(context, artworkUrl) }
    }

    val target = extracted ?: fallback
    val tint by animateColorAsState(target, tween(FADE_MS), label = "player-backdrop")
    return PlayerBackdrop(
        tint = tint,
        modifier = Modifier.background(
            // Two stops and a stated end: the tint owns the top, and by [FADE_STOP] it is the
            // ordinary surface again so the text below is read on the colour it was designed for.
            Brush.verticalGradient(
                0f to tint.copy(alpha = TOP_ALPHA),
                FADE_STOP to Color.Transparent,
            ),
        ),
    )
}

internal data class PlayerBackdrop(val tint: Color, val modifier: Modifier) {
    /**
     * Whether content on top of the tint should be light.
     *
     * Asked of the tint rather than the theme, because the tint is whatever the artwork happened to
     * be — a pale thumbnail in dark mode genuinely needs dark text on it.
     */
    val prefersDarkContent: Boolean get() = tint.luminance() > LIGHT_TINT_LUMINANCE
}

/**
 * Reads the artwork and picks its colour.
 *
 * `allowHardware(false)` matters: Coil decodes to a hardware bitmap by default and those cannot be
 * read back with `getPixels`, so without it every image silently yields nothing. Downsampled hard to
 * [SAMPLE_PX] — the answer does not improve past a thumbnail and this runs on every track change.
 */
private suspend fun colourOf(context: android.content.Context, url: String): Color? {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(SAMPLE_PX, SAMPLE_PX)
        .allowHardware(false)
        .build()
    val bitmap = runCatching { SingletonImageLoader.get(context).execute(request).image?.toBitmap() }
        .onFailure { Diag.warn("player", "could not read artwork for its colour", it) }
        .getOrNull() ?: return null
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val picked = ArtworkColour.of(pixels) ?: run {
        // Said out loud: a null here is why a player looks un-tinted, and it is otherwise silent.
        Diag.log("player", "artwork had no usable colour; falling back to the brand")
        return null
    }
    return Color(picked)
}

/** Long enough to read as a fade rather than a flicker, short enough not to lag a track change. */
private const val FADE_MS = 600

/**
 * Strong enough to be unmistakable, weak enough that white text stays legible on any hue — a fully
 * saturated backdrop makes yellow and cyan artwork unreadable.
 */
private const val TOP_ALPHA = 0.45f

/** Back to the ordinary surface a third of the way down; below this is body text. */
private const val FADE_STOP = 0.35f

private const val SAMPLE_PX = 64

/** Above this the tint is pale enough that light content on it would disappear. */
private const val LIGHT_TINT_LUMINANCE = 0.5f
