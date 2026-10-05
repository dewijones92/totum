package com.dewijones92.totum.ui.player

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.PlaybackState

internal enum class FullscreenMode { WINDOWED, MANUAL, ROTATED }

@Composable
internal fun rememberFullscreenMode(state: PlaybackState): MutableState<FullscreenMode> {
    val mode = rememberSaveable { mutableStateOf(FullscreenMode.WINDOWED) }
    val orientation = LocalConfiguration.current.orientation
    LaunchedEffect(orientation, state.hasVideo) {
        when {
            orientation == Configuration.ORIENTATION_LANDSCAPE && state.hasVideo &&
                mode.value == FullscreenMode.WINDOWED -> {
                Diag.log(
                    "fullscreen",
                    "rotation enters fullscreen: item=${state.itemId.value} hasVideo=${state.hasVideo}"
                )
                mode.value = FullscreenMode.ROTATED
            }
            orientation == Configuration.ORIENTATION_PORTRAIT && mode.value == FullscreenMode.ROTATED -> {
                Diag.log("fullscreen", "portrait rotation leaves automatic fullscreen: item=${state.itemId.value}")
                mode.value = FullscreenMode.WINDOWED
            }
        }
    }
    return mode
}

@Composable
internal fun OpenVideoOnLandscape(state: PlaybackState?, enabled: Boolean, onOpen: () -> Unit) {
    val orientation = LocalConfiguration.current.orientation
    LaunchedEffect(orientation, state?.hasVideo, enabled) {
        if (orientation == Configuration.ORIENTATION_LANDSCAPE && state?.hasVideo == true && enabled) {
            Diag.log("fullscreen", "landscape video opens the player from the shell: item=${state.itemId.value}")
            onOpen()
        }
    }
}
