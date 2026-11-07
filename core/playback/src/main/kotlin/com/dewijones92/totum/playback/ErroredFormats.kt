package com.dewijones92.totum.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException

@UnstableApi
internal fun PlaybackException.erroredFormat(): ErroredFormat? =
    (this as? ExoPlaybackException)?.rendererFormat?.let { ErroredFormat(it.sampleMimeType, it.codecs) }
        ?: ErroredFormat.fromMessage(message)

@UnstableApi
internal fun PlaybackException.problem(): PlaybackProblem = problemOf(errorCodeName, erroredFormat())
