package com.dewijones92.totum.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import com.dewijones92.totum.common.Diag

@OptIn(markerClass = [UnstableApi::class])
@UnstableApi
internal class SkipSilenceOutputBuffer(
    private val skipping: () -> Boolean
) : DefaultAudioSink.AudioTrackBufferSizeProvider {

    private val roomy = DefaultAudioTrackBufferSizeProvider.Builder()
        .setMinPcmBufferDurationUs(ROOMY_US)
        .setMaxPcmBufferDurationUs(ROOMY_US)
        .build()

    override fun getBufferSizeInBytes(
        minBufferSizeInBytes: Int,
        encoding: Int,
        outputMode: Int,
        pcmFrameSize: Int,
        sampleRate: Int,
        bitrate: Int,
        maxAudioTrackPlaybackSpeed: Double,
    ): Int {
        val cutting = skipping()
        val provider = if (cutting) roomy else DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT
        val bytes = provider.getBufferSizeInBytes(
            minBufferSizeInBytes,
            encoding,
            outputMode,
            pcmFrameSize,
            sampleRate,
            bitrate,
            maxAudioTrackPlaybackSpeed,
        )
        val millis = if (pcmFrameSize > 0 && sampleRate > 0 && pcmFrameSize != C.LENGTH_UNSET) {
            "${bytes.toLong() / pcmFrameSize * MILLIS_PER_SECOND / sampleRate}ms"
        } else {
            "$bytes bytes"
        }
        Diag.log("silence", "audio output buffer $millis (skip-silence=$cutting)")
        return bytes
    }

    private companion object {
        const val ROOMY_US = 1_000_000
        const val MILLIS_PER_SECOND = 1_000L
    }
}
