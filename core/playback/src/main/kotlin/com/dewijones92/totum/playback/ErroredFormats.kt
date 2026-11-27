package com.dewijones92.totum.playback

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException

@UnstableApi
internal fun PlaybackException.erroredFormat(): ErroredFormat? {
    val fromMessage = ErroredFormat.fromMessage(message)
    val renderer = this as? ExoPlaybackException
    val format = renderer?.rendererFormat ?: return fromMessage
    return ErroredFormat(
        mimeType = format.sampleMimeType,
        codecs = format.codecs,
        width = format.width.takeIf { it > 0 } ?: fromMessage?.width,
        height = format.height.takeIf { it > 0 } ?: fromMessage?.height,
        formatSupported = if (renderer.type == ExoPlaybackException.TYPE_RENDERER) {
            renderer.rendererFormatSupport == C.FORMAT_HANDLED
        } else {
            fromMessage?.formatSupported
        },
    )
}

@UnstableApi
internal fun PlaybackException.problem(): PlaybackProblem = problemOf(errorCodeName, erroredFormat())
