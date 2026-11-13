package com.dewijones92.totum.ui.common

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.Refusal
import com.dewijones92.totum.live.startsAtText
import com.dewijones92.totum.playback.PlaybackProblem
import com.dewijones92.totum.playback.TrackKind
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.queue.RefusedTap
import com.dewijones92.totum.video.LivePhase
import com.dewijones92.totum.video.LiveState
import com.dewijones92.totum.video.LiveWaitSchedule

@Composable
fun RefusedTapToasts(queue: PlaybackQueue, signedIn: Boolean) {
    val context = LocalContext.current
    val signedInNow by rememberUpdatedState(signedIn)
    val liveStates = LocalLiveStates.current
    val offers by rememberUpdatedState(LocalSnackbarOffers.current)
    val actions by rememberUpdatedState(LocalItemActions.current)
    val listenLabel by rememberUpdatedState(stringResource(R.string.play_audio_only))
    LaunchedEffect(queue) {
        queue.refusedTaps.collect { tap ->
            val message = context.refusalMessage(
                tap,
                signedInNow,
                tap.videoId?.let { liveStates.value[it] }?.takeIf { it.phase == LivePhase.UPCOMING }
            )
            val rows = actions
            val listen = tap.listen?.takeIf { rows?.audioMode == false }
            val shell = offers
            if (listen != null && shell != null && rows != null) {
                Diag.log("ui", "snackbar with 🎧 Listen: $message")
                shell.offer(message, listenLabel, "listen", SnackbarDuration.Long) {
                    Diag.log("listen", "🎧 Listen tapped on the failure of ${listen.item.id.value}")
                    rows.switchMode(listen.item)
                }
            } else {
                Diag.log("ui", "toast: $message")
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal fun Context.refusalMessage(tap: RefusedTap, signedIn: Boolean, startsSoon: LiveState? = null): String {
    if (startsSoon != null) return startsSoonMessage(tap.title, startsSoon)
    tap.problem?.let { return getString(R.string.refused_tap, tap.title, problemWords(it)) }
    val why = getString(
        when (tap.reason) {
            Refusal.NotOnThisDevice -> R.string.refused_offline
            Refusal.StreamWillNotPlay -> R.string.refused_stream
            Refusal.NothingToPlay -> R.string.refused_nothing
            null -> if (signedIn) R.string.refused_no_stream else R.string.refused_no_stream_signed_out
        },
    )
    return getString(R.string.refused_tap, tap.title, why)
}

private fun Context.problemWords(problem: PlaybackProblem): String = when (problem) {
    is PlaybackProblem.CannotDecode -> getString(
        if (problem.track == TrackKind.VIDEO) {
            R.string.refused_cannot_decode_video
        } else {
            R.string.refused_cannot_decode_sound
        },
        problem.format,
    )
    is PlaybackProblem.Failed -> getString(R.string.refused_player_error, problem.errorName)
    is PlaybackProblem.NoTrackList -> getString(R.string.refused_no_track_list)
}

private fun Context.startsSoonMessage(title: String, soon: LiveState): String {
    val at = soon.startsAt ?: return getString(R.string.refused_live_soon, title)
    val tooFar = !LiveWaitSchedule.worthWaitingFor(at, System.currentTimeMillis())
    return getString(if (tooFar) R.string.refused_live_far else R.string.refused_live_at, title, startsAtText(at))
}
