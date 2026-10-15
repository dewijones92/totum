package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

internal class PlayingSignal {
    private val playing = MutableStateFlow(false)

    fun changed(isPlaying: Boolean) {
        playing.value = isPlaying
    }

    suspend fun await(isPlayingNow: () -> Boolean) {
        playing.value = isPlayingNow()
        if (playing.value) return
        Diag.log("playback", "position ticker idle until something plays")
        playing.first { it }
        Diag.log("playback", "position ticker running again")
    }
}
