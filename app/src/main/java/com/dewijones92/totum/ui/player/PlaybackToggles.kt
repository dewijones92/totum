package com.dewijones92.totum.ui.player

/** The full player's on/off playback preferences, bundled so they thread as one. */
data class PlaybackToggles(
    val skipSilence: Boolean = false,
    val onSetSkipSilence: (Boolean) -> Unit = {},
    val autoPlayNext: Boolean = true,
    val onSetAutoPlayNext: (Boolean) -> Unit = {},
    /** Experimental fast start over SABR. Off by default; it cannot seek yet. */
    val sabrPlayback: Boolean = false,
    val onSetSabrPlayback: (Boolean) -> Unit = {},
    val onSetVolumeBoost: (com.dewijones92.totum.playback.VolumeBoost) -> Unit = {},
)
