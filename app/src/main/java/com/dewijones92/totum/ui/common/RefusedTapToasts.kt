package com.dewijones92.totum.ui.common

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.Refusal
import com.dewijones92.totum.live.startsAtText
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
    LaunchedEffect(queue) {
        queue.refusedTaps.collect { tap ->
            val message = context.refusalMessage(
                tap,
                signedInNow,
                tap.videoId?.let { liveStates.value[it] }?.takeIf { it.phase == LivePhase.UPCOMING }
            )
            Diag.log("ui", "toast: $message")
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}

internal fun Context.refusalMessage(tap: RefusedTap, signedIn: Boolean, startsSoon: LiveState? = null): String {
    if (startsSoon != null) return startsSoonMessage(tap.title, startsSoon)
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

private fun Context.startsSoonMessage(title: String, soon: LiveState): String {
    val at = soon.startsAt ?: return getString(R.string.refused_live_soon, title)
    val tooFar = !LiveWaitSchedule.worthWaitingFor(at, System.currentTimeMillis())
    return getString(if (tooFar) R.string.refused_live_far else R.string.refused_live_at, title, startsAtText(at))
}
