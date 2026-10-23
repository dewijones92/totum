package com.dewijones92.totum.playback

public fun PlaybackState.isLoading(starting: Boolean = false): Boolean = starting || (isBuffering && wantsToPlay)
