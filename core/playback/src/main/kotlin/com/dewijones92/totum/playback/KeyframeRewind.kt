package com.dewijones92.totum.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.dewijones92.totum.common.Diag

@UnstableApi
internal fun ExoPlayer.rewindToKeyframeBefore(positionMs: Long) {
    val exact = seekParameters
    setSeekParameters(SeekParameters.PREVIOUS_SYNC)
    seekTo(positionMs)
    setSeekParameters(exact)
    Diag.log(
        "playback",
        "resume rewind to ${positionMs}ms, landing on the keyframe at or before it, so it starts from the back " +
            "buffer with no frames to decode up to it",
    )
}

internal const val ACTION_REWIND_TO: String = "com.dewijones92.totum.REWIND_TO"
internal const val EXTRA_REWIND_TO_MS: String = "rewindToMs"
