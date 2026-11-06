package com.dewijones92.totum.playback

import android.os.Bundle
import androidx.core.os.bundleOf

public data class PlaybackContext(val repeatOne: Boolean = false) {
    internal fun toBundle(): Bundle = bundleOf(EXTRA_CONTEXT_REPEAT_ONE to repeatOne)

    internal companion object {
        fun from(args: Bundle): PlaybackContext =
            PlaybackContext(repeatOne = args.getBoolean(EXTRA_CONTEXT_REPEAT_ONE, false))
    }
}

internal const val ACTION_PLAYBACK_CONTEXT: String = "com.dewijones92.totum.PLAYBACK_CONTEXT"
internal const val EXTRA_CONTEXT_REPEAT_ONE: String = "context_repeat_one"
