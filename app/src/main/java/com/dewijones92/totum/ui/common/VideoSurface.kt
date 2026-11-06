package com.dewijones92.totum.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Display
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import com.dewijones92.totum.common.Diag
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(player: Player, modifier: Modifier = Modifier) {
    PlayerSurface(player = player, surfaceType = SURFACE_TYPE_SURFACE_VIEW, modifier = modifier)
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(player, activity) {
        val preference = RefreshPreference(activity, player)
        player.addListener(preference)
        preference.update()
        onDispose {
            player.removeListener(preference)
            preference.clear()
        }
    }
}

private class RefreshPreference(private val activity: Activity, private val player: Player) : Player.Listener {
    private var chosen = NO_PREFERENCE

    override fun onTracksChanged(tracks: Tracks) = update()

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = update()

    fun update() {
        val content = contentFrameRate(player)
        val current = activity.display?.mode
        val modes = activity.display?.supportedModes.orEmpty()
            .filter { it.physicalWidth == current?.physicalWidth && it.physicalHeight == current.physicalHeight }
        val wanted = content?.let { rate -> evenRefreshFor(rate, modes.map { it.modeId to it.refreshRate }) }
            ?: NO_PREFERENCE
        if (wanted == chosen) return
        chosen = wanted
        prefer(wanted)
        Diag.log("video", "screen refresh preference: ${describe(modes, wanted)} for content at ${fps(content)}")
    }

    fun clear() {
        if (chosen == NO_PREFERENCE) return
        chosen = NO_PREFERENCE
        prefer(NO_PREFERENCE)
        Diag.log("video", "screen refresh preference cleared: the video surface left the screen")
    }

    private fun prefer(modeId: Int) {
        val window = activity.window
        window.attributes = window.attributes.also { it.preferredDisplayModeId = modeId }
    }

    private fun describe(modes: List<Display.Mode>, wanted: Int): String {
        val rate = modes.firstOrNull { it.modeId == wanted }?.refreshRate
        val offered = modes.joinToString { "${it.refreshRate.roundToInt()}Hz" }
        return (rate?.let { "${it.roundToInt()}Hz (mode $wanted)" } ?: "none, the system decides") + " of $offered"
    }

    private fun fps(content: Float?): String =
        content?.let { "%.2f fps (%sx)".format(it, player.playbackParameters.speed) } ?: "an unknown frame rate"
}

private fun contentFrameRate(player: Player): Float? {
    val group = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
        ?: return null
    val format = (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) ?: return null
    return format.frameRate.takeIf { it > 0f }?.let { it * player.playbackParameters.speed }
}

internal fun evenRefreshFor(contentFps: Float, modes: List<Pair<Int, Float>>): Int? {
    fun whole(x: Float) = abs(x - x.roundToInt()) < EVEN_TOLERANCE && x.roundToInt() >= 1
    val showsEachFrameEvenly = modes.filter { (_, hz) -> whole(hz / contentFps) }.maxByOrNull { it.second }
    val dropsEvenly = modes.filter { (_, hz) -> whole(contentFps / hz) }.maxByOrNull { it.second }
    return (showsEachFrameEvenly ?: dropsEvenly)?.first
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val NO_PREFERENCE = 0
private const val EVEN_TOLERANCE = 0.02f
