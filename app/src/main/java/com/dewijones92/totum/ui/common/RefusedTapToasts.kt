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
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.queue.RefusedTap

@Composable
fun RefusedTapToasts(queue: PlaybackQueue, signedIn: Boolean) {
    val context = LocalContext.current
    val signedInNow by rememberUpdatedState(signedIn)
    LaunchedEffect(queue) {
        queue.refusedTaps.collect { tap ->
            val message = context.refusalMessage(tap, signedInNow)
            Diag.log("ui", "toast: $message")
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}

internal fun Context.refusalMessage(tap: RefusedTap, signedIn: Boolean): String {
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
